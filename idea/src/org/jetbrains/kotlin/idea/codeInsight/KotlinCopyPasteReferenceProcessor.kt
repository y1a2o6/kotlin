/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.codeInsight

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction.nonBlocking
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.caches.resolve.allowResolveInDispatchThread
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.caches.resolve.resolveImportReference
import org.jetbrains.kotlin.idea.codeInsight.shorten.performDelayedRefactoringRequests
import org.jetbrains.kotlin.idea.core.util.end
import org.jetbrains.kotlin.idea.core.util.range
import org.jetbrains.kotlin.idea.core.util.start
import org.jetbrains.kotlin.idea.imports.importableFqName
import org.jetbrains.kotlin.idea.kdoc.KDocReference
import org.jetbrains.kotlin.idea.references.*
import org.jetbrains.kotlin.idea.util.ImportInsertHelper
import org.jetbrains.kotlin.idea.util.ProgressIndicatorUtils
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.idea.util.getFileResolutionScope
import org.jetbrains.kotlin.idea.util.getSourceRoot
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.kdoc.psi.api.KDocElement
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.utils.findFunction
import org.jetbrains.kotlin.resolve.scopes.utils.findVariable
import org.jetbrains.kotlin.types.ErrorUtils
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import java.util.*
import java.util.function.Consumer

class KotlinCopyPasteReferenceProcessor : CopyPastePostProcessor<BasicKotlinReferenceTransferableData>() {

    override fun extractTransferableData(content: Transferable): List<BasicKotlinReferenceTransferableData> {
        if (CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE != CodeInsightSettings.NO) {
            try {
                val flavor = KotlinReferenceData.dataFlavor ?: return listOf()
                val data = content.getTransferData(flavor) as? BasicKotlinReferenceTransferableData ?: return listOf()
                return listOf(data)
            } catch (ignored: UnsupportedFlavorException) {
            } catch (ignored: IOException) {
            }
        }

        return listOf()
    }

    override fun collectTransferableData(
        file: PsiFile,
        editor: Editor,
        startOffsets: IntArray,
        endOffsets: IntArray
    ): List<BasicKotlinReferenceTransferableData> {
        if (file !is KtFile || DumbService.getInstance(file.getProject()).isDumb) return listOf()

        val ranges = toTextRanges(startOffsets, endOffsets)
        val textBlocks = ranges.map { TextBlock(it.startOffset, it.endOffset, editor.document.getText(it)) }

        val pkg = file.packageDirective?.fqName?.asString()
        val imports = file.importDirectives.map { it.text }
        val endOfImportsOffset = file.importDirectives.map { it.endOffset }.min() ?: file.packageDirective?.endOffset ?: 0
        val text = file.text.substring(endOfImportsOffset)

////        // TODO: leftover
//        val collectedData = try {
//            collectReferenceData(file, startOffsets, endOffsets)
//        } catch (e: ProcessCanceledException) {
//            // supposedly analysis can only be canceled from another thread
//            // do not log ProcessCanceledException as it is rethrown by IdeaLogger and code won't be copied
//            LOG.debug("ProcessCanceledException while analyzing references in ${file.getName()}. References can't be processed.")
//            return listOf()
//        } catch (e: Throwable) {
//            LOG.error("Exception in processing references for copy paste in file ${file.getName()}}", e)
//            return listOf()
//        }

        return listOf(
            BasicKotlinReferenceTransferableData(
                sourceFileUrl = file.virtualFile.url,
                pkg = pkg,
                imports = imports,
                endOfImportsOffset = endOfImportsOffset,
                sourceText = text,
                textBlocks = textBlocks
            )
        )
    }

    fun collectReferenceData(
        file: KtFile,
        startOffsets: IntArray,
        endOffsets: IntArray
    ): List<KotlinReferenceData> {
        val ranges = toTextRanges(startOffsets, endOffsets)
        val elementsByRange = ranges.associateBy({ it }, { textRange ->
            file.elementsInRange(textRange).filter { it is KtElement || it is KDocElement }
        })

        val allElementsToResolve = elementsByRange.values.flatten().flatMap { it.collectDescendantsOfType<KtElement>() }
        val bindingContext =
            allowResolveInDispatchThread {
                file.getResolutionFacade().analyze(allElementsToResolve, BodyResolveMode.PARTIAL)
            }

        val result = ArrayList<KotlinReferenceData>()
        for ((range, elements) in elementsByRange) {
            for (element in elements) {
                result.addReferenceDataInsideElement(element, file, range.start, startOffsets, endOffsets, bindingContext)
            }
        }
        return result
    }

    private fun MutableCollection<KotlinReferenceData>.addReferenceDataInsideElement(
        element: PsiElement,
        file: KtFile,
        startOffset: Int,
        startOffsets: IntArray,
        endOffsets: IntArray,
        bindingContext: BindingContext
    ) {
        if (PsiTreeUtil.getNonStrictParentOfType(element, *IGNORE_REFERENCES_INSIDE) != null) return

        element.forEachDescendantOfType<KtElement>(canGoInside = { it::class.java as Class<*> !in IGNORE_REFERENCES_INSIDE }) { ktElement ->
            val reference = ktElement.mainReference ?: return@forEachDescendantOfType

            val descriptors = resolveReference(reference, bindingContext)
            //check whether this reference is unambiguous
            if (reference !is KtMultiReference<*> && descriptors.size > 1) return@forEachDescendantOfType

            for (descriptor in descriptors) {
                val effectiveReferencedDescriptors = DescriptorToSourceUtils.getEffectiveReferencedDescriptors(descriptor).asSequence()
                val declaration = effectiveReferencedDescriptors
                    .map { DescriptorToSourceUtils.getSourceFromDescriptor(it) }
                    .singleOrNull()
                if (declaration != null && declaration.isInCopiedArea(file, startOffsets, endOffsets)) continue

                if (!reference.canBeResolvedViaImport(descriptor, bindingContext)) continue

                val fqName = descriptor.importableFqName ?: continue
                val kind = KotlinReferenceData.Kind.fromDescriptor(descriptor) ?: continue
                val isQualifiable = KotlinReferenceData.isQualifiable(ktElement, descriptor)
                val relativeStart = ktElement.range.start - startOffset
                val relativeEnd = ktElement.range.end - startOffset
                add(KotlinReferenceData(relativeStart, relativeEnd, fqName.asString(), isQualifiable, kind))
            }
        }
    }

    private data class ReferenceToRestoreData(
        val reference: KtReference,
        val refData: KotlinReferenceData
    )

    override fun processTransferableData(
        project: Project,
        editor: Editor,
        bounds: RangeMarker,
        caretOffset: Int,
        indented: Ref<Boolean>,
        values: List<BasicKotlinReferenceTransferableData>
    ) {
        if (DumbService.getInstance(project).isDumb ||
            CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE == CodeInsightSettings.NO
        ) return

        val document = editor.document
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document)
        if (file !is KtFile) return

        processReferenceData(project, file, bounds.startOffset, values.single())
    }

    fun processReferenceData(project: Project, file: KtFile, blockStart: Int, transferableData: BasicKotlinReferenceTransferableData) {
        val task: Task.Backgroundable = object : Task.Backgroundable(project, "Resolve pasted references", true) {
            override fun run(indicator: ProgressIndicator) {
                assert(!ApplicationManager.getApplication().isWriteAccessAllowed) {
                    "Resolving references on dispatch thread leads to live lock"
                }
                ProgressIndicatorUtils.awaitWithCheckCanceled(
                    nonBlocking<List<ReferenceToRestoreData>> {
                        return@nonBlocking findReferenceDataToRestore(
                            file,
                            blockStart,
                            transferableData
                        )
                    }
                        .finishOnUiThread(
                            ModalityState.defaultModalityState(),
                            Consumer<List<ReferenceToRestoreData>> { referencesPossibleToRestore ->
                                val selectedReferencesToRestore = showRestoreReferencesDialog(project, referencesPossibleToRestore)
                                if (selectedReferencesToRestore.isEmpty()) return@Consumer

                                project.executeWriteCommand("resolve pasted references") {
                                    restoreReferences(selectedReferencesToRestore, file)
                                }
                            }
                        )
                        .withDocumentsCommitted(project)
                        .submit(AppExecutorUtil.getAppExecutorService())
                )
            }
        }
        ProgressManager.getInstance().run(task)
    }

    fun processReferenceData(project: Project, file: KtFile, blockStart: Int, referenceData: Array<KotlinReferenceData>) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val referencesPossibleToRestore = findReferencesToRestore(file, blockStart, referenceData)

        val selectedReferencesToRestore = showRestoreReferencesDialog(project, referencesPossibleToRestore)
        if (selectedReferencesToRestore.isEmpty()) return

        runWriteAction {
            restoreReferences(selectedReferencesToRestore, file)
        }
    }

    private fun findReferenceDataToRestore(
        file: PsiFile,
        blockStart: Int,
        transferableData: BasicKotlinReferenceTransferableData
    ): List<ReferenceToRestoreData> {
        if (file !is KtFile) return emptyList()

        val sourcePkgName = transferableData.pkg ?: ""
        val imports: List<String> = transferableData.imports
        val textBlocks = transferableData.textBlocks

        fun isAnyUnresolvedReference(references: List<TextBlockReferences>) =
            references.any { it.refs.unresolved.isNotEmpty() }

        val fileResolutionScope = file.getResolutionFacade().getFileResolutionScope(file)

        // Step 0. Get base reference data - is it enough actual imports / data for pasted data
        val baseReferences =
            textBlocks.map { TextBlockReferences(it, findReferences(it, null, file, file, blockStart, null, sourcePkgName)) }

        if (!isAnyUnresolvedReference(baseReferences)) return emptyList()

        val originalFileImports = "\n${imports.joinToString("\n")}\n"
        if (imports.isNotEmpty()) {
            // Step 1. Attempt to resolve references using imports from a source file

            // original file imports have to be placed after existed imports or after package name
            val endOffset = file.importDirectives.map { it.endOffset }.min() ?: file.packageDirective?.endOffset ?: 0

            // insert ALL import instructions from a source file could lead to duplicates (that's an error for imports)
            // but it does not affect resolving
            val newFileText = file.text.substring(0, endOffset) + originalFileImports + file.text.substring(endOffset)

            val dummyImportsFile = KtPsiFactory(file.project).createAnalyzableFile("dummy-imports.kt", newFileText, file)

            val blockStartAfterImports = blockStart + originalFileImports.length
            val importBasedReferences = textBlocks.map {
                TextBlockReferences(it, findReferences(it, null, dummyImportsFile, file, blockStartAfterImports, null, sourcePkgName))
            }

            if (!isAnyUnresolvedReference(importBasedReferences)) {
                val referenceDataViaImports =
                    // here we have to map items from fake file that is just target file but shifted for `extraImports.length`
                    buildReferenceDataFromResolved(
                        fileResolutionScope, importBasedReferences, baseReferences,
                        blockStart = 0, resolvedOffset = originalFileImports.length
                    )

                // is it even possible that referenceDataViaImports is empty ?
                return referenceDataViaImports
            }
        }

        // Step 2. Recreate original source file (i.e. source file as it was on copy action) and resolve references from it
        val ctxFile = sourceFile(file.project, transferableData) ?: file
        // put original source file to some fake package to avoid ambiguous resolution ( a real file VS a virtual file )
        val fakePkgName = "__some.__funky.__package"
        val dummyOrigFileProlog = "package $fakePkgName\nimport ${sourcePkgName}.*\n$originalFileImports\n"
        val dummyOriginalFile = KtPsiFactory(file.project)
            .createAnalyzableFile(
                "dummy-original.kt",
                "$dummyOrigFileProlog${transferableData.sourceText}",
                ctxFile
            )

        val dummyOriginalFileTextBlocks =
            textBlocks.map {
                val offsetDelta = transferableData.endOfImportsOffset - dummyOrigFileProlog.length
                TextBlock(it.startOffset - offsetDelta, it.endOffset - offsetDelta, it.text)
            }

        val sourceFileBasedReferences =
            dummyOriginalFileTextBlocks.map {
                TextBlockReferences(
                    it,
                    findReferences(
                        it, dummyOriginalFileTextBlocks, dummyOriginalFile, file,
                        it.startOffset,
                        fakePkgName, sourcePkgName
                    )
                )
            }

        val missedRestoreData =
            // here we have to map items from original source file
            buildReferenceDataFromResolved(
                fileResolutionScope,
                sourceFileBasedReferences,
                baseReferences,
                blockStart
            )

        return missedRestoreData
    }

    private fun sourceFile(project: Project, transferableData: BasicKotlinReferenceTransferableData): KtFile? {
        val sourceFile = VirtualFileManager.getInstance().findFileByUrl(transferableData.sourceFileUrl) ?: return null
        if (sourceFile.getSourceRoot(project) == null) return null

        return PsiManager.getInstance(project).findFile(sourceFile) as? KtFile
    }

    private fun buildReferenceDataFromResolved(
        fileResolutionScope: LexicalScope,
        resolvedReferences: List<TextBlockReferences>,
        baseReferences: List<TextBlockReferences>,
        blockStart: Int,
        resolvedOffset: Int? = null
    ): List<ReferenceToRestoreData> {

        val resolvedFqNames = mutableMapOf<Int, MutableList<KotlinReferenceData>>()
        for (it in resolvedReferences) {
            for (resolved in it.refs.resolved) {
                val referenceData = resolved.second
                val offset = resolvedOffset ?: it.textBlock.startOffset
                val key = resolved.first.startOffset - offset
                if (resolvedFqNames[key] == null) resolvedFqNames[key] = mutableListOf()
                resolvedFqNames[key]!!.add(referenceData)
            }
        }

        val referenceData = mutableListOf<ReferenceToRestoreData>()
        for (it in baseReferences) {
            for (unresolved in it.refs.unresolved) {
                resolvedFqNames[unresolved.element.startOffset - blockStart]?.let { refDataList ->
                    refDataList.forEach { refData -> referenceData.add(ReferenceToRestoreData(unresolved, refData)) }
                }
            }
        }

        return referenceData.filter { data ->
            val refData = data.refData
            if (refData.isQualifiable) {
                return@filter true
            }

            val originalFqName = FqName(refData.fqName)
            val name = originalFqName.shortName()
            return@filter when (refData.kind) {
                // filter if function is already imported
                KotlinReferenceData.Kind.FUNCTION -> fileResolutionScope
                    .findFunction(name, NoLookupLocation.FROM_IDE) { it.importableFqName == originalFqName } == null
                // filter if property is already imported
                KotlinReferenceData.Kind.PROPERTY -> fileResolutionScope
                    .findVariable(name, NoLookupLocation.FROM_IDE) { it.importableFqName == originalFqName } == null
                else -> true
            }
        }
    }

    private fun findReferencesToRestore(
        file: PsiFile,
        blockStart: Int,
        referenceData: Array<out KotlinReferenceData>
    ): List<ReferenceToRestoreData> {
        if (file !is KtFile) return listOf()

        val references = referenceData
            .map { it to findReference(it, file, blockStart) }
        val bindingContext = try {
            file.getResolutionFacade().analyze(references.mapNotNull { it.second?.element }, BodyResolveMode.PARTIAL)
        } catch (e: Throwable) {
            if (e is ControlFlowException) throw e
            LOG.error("Failed to analyze references after copy paste", e)
            return emptyList()
        }
        val fileResolutionScope = file.getResolutionFacade().getFileResolutionScope(file)
        return references.mapNotNull { pair ->
            val data = pair.first
            val reference = pair.second
            if (reference != null)
                createReferenceToRestoreData(reference, data, file, fileResolutionScope, bindingContext)
            else
                null
        }
    }

    private fun findReference(data: KotlinReferenceData, file: KtFile, blockStart: Int): KtReference? =
        findReference(file, TextRange(data.startOffset + blockStart, data.endOffset + blockStart))

    private fun findReference(file: KtFile, desiredRange: TextRange): KtReference? {
        val element = file.findElementAt(desiredRange.startOffset) ?: return null
        return findReference(element, desiredRange)
    }

    private fun findReference(element: PsiElement, desiredRange: TextRange, ignoreElementRange: Boolean = false): KtReference? {
        for (current in element.parentsWithSelf) {
            val range = current.range
            if (current is KtElement && (ignoreElementRange || range == desiredRange)) {
                current.mainReference?.let { return it }
            }
            if (range !in desiredRange) return null
        }
        return null
    }

    private fun findReferences(
        data: TextBlock,
        textBlocks: List<TextBlock>?,
        file: KtFile,
        targetFile: KtFile,
        blockStart: Int,
        fakePackageName: String? = null,
        sourcePackageName: String
    ): References {
        val textRange = TextRange(blockStart, blockStart + data.endOffset - data.startOffset)

        val elementsInRange = file.elementsInRange(textRange).filter { it is KtElement || it is KDocElement }

        val allElementsInRange = elementsInRange.flatMap { it.collectDescendantsOfType<KtElement>() }

        val bindingContext =
            file.getResolutionFacade().analyze(allElementsInRange, BodyResolveMode.PARTIAL)

        val unresolvedReferences = mutableListOf<KtReference>()
        val resolvedReferences = mutableListOf<Pair<KtElement, KotlinReferenceData>>()

        elementsInRange.forEach { element ->
            element.forEachDescendantOfType<KtElement>(canGoInside = {
                it::class.java as Class<*> !in IGNORE_REFERENCES_INSIDE
            }) { ktElement ->
                if (PsiTreeUtil.getNonStrictParentOfType(ktElement, *IGNORE_REFERENCES_INSIDE) != null) return@forEachDescendantOfType

                val reference = ktElement.mainReference ?: return@forEachDescendantOfType

                val descriptors = resolveReference(reference, bindingContext)

                if (descriptors.isEmpty()) {
                    unresolvedReferences.add(reference)
                    return@forEachDescendantOfType
                }

                //check whether this reference is unambiguous
                if (reference !is KtMultiReference<*> && descriptors.size > 1) return@forEachDescendantOfType

                for (descriptor in descriptors) {
                    if (textBlocks != null) {
                        val effectiveReferencedDescriptors =
                            DescriptorToSourceUtils.getEffectiveReferencedDescriptors(descriptor).asSequence()
                        val declaration = effectiveReferencedDescriptors
                            .map { DescriptorToSourceUtils.getSourceFromDescriptor(it) }
                            .singleOrNull()
                        if (declaration != null && declaration.isInCopiedArea(file, textBlocks)) continue
                    }

                    if (ErrorUtils.isError(descriptor) || !reference.canBeResolvedViaImport(descriptor, bindingContext)) {
                        unresolvedReferences.add(reference)
                        continue
                    }

                    val importableFqName = descriptor.importableFqName?.asString()
                    if (importableFqName == null) {
                        unresolvedReferences.add(reference)
                        continue
                    }
                    val fqName =
                        // roll back to original package name when we faced faked pkg name
                        if (fakePackageName?.let { importableFqName.startsWith(it) } == true)
                            sourcePackageName + importableFqName.substring(fakePackageName!!.length)
                        else importableFqName

                    val kind = KotlinReferenceData.Kind.fromDescriptor(descriptor) ?: continue
                    val isQualifiable = KotlinReferenceData.isQualifiable(ktElement, descriptor)
                    resolvedReferences.add(ktElement to KotlinReferenceData(-1, -1, fqName, isQualifiable, kind))
                }
            }
        }

        val resolved = resolvedReferences.filter { pair ->
            val referenceData = pair.second
            // check that descriptor to import exists and is accessible from the current module
            val importableDescriptors = findImportableDescriptors(FqName(referenceData.fqName), targetFile)
            if (importableDescriptors.none { KotlinReferenceData.Kind.fromDescriptor(it) == referenceData.kind }) {
                return@filter false
            }

            true
        }

        val resolvedElementsStartOffset = resolved.map { it.first.startOffset }.toSet()
        // drop unresolved reference if there is a resolved ref at the same position
        val unresolved = unresolvedReferences.filterNot { it.element.startOffset in resolvedElementsStartOffset }

        return References(unresolved = unresolved, resolved = resolved)
    }

    private data class TextBlockReferences(val textBlock: TextBlock, val refs: References)


    private data class References(
        val resolved: List<Pair<KtElement, KotlinReferenceData>>,
        val unresolved: List<KtReference>
    )

    private fun createReferenceToRestoreData(
        reference: KtReference,
        refData: KotlinReferenceData,
        file: KtFile,
        fileResolutionScope: LexicalScope,
        bindingContext: BindingContext
    ): ReferenceToRestoreData? {
        val originalFqName = FqName(refData.fqName)
        val name = originalFqName.shortName()

        if (!refData.isQualifiable) {
            if (refData.kind == KotlinReferenceData.Kind.FUNCTION) {
                if (fileResolutionScope.findFunction(name, NoLookupLocation.FROM_IDE) { it.importableFqName == originalFqName } != null) {
                    return null // already imported
                }
            } else if (refData.kind == KotlinReferenceData.Kind.PROPERTY) {
                if (fileResolutionScope.findVariable(name, NoLookupLocation.FROM_IDE) { it.importableFqName == originalFqName } != null) {
                    return null // already imported
                }
            }
        }

        val referencedDescriptors = resolveReference(reference, bindingContext)
        val referencedFqNames = referencedDescriptors
            .filterNot { ErrorUtils.isError(it) }
            .mapNotNull { it.importableFqName }
            .toSet()
        if (referencedFqNames.singleOrNull() == originalFqName) return null

        // check that descriptor to import exists and is accessible from the current module
        if (findImportableDescriptors(originalFqName, file).none { KotlinReferenceData.Kind.fromDescriptor(it) == refData.kind }) {
            return null
        }

        return ReferenceToRestoreData(reference, refData)
    }

    private fun resolveReference(reference: KtReference, bindingContext: BindingContext): List<DeclarationDescriptor> {
        val element = reference.element
        if (element is KtNameReferenceExpression && reference is KtSimpleNameReference) {
            val classifierDescriptor = bindingContext[BindingContext.SHORT_REFERENCE_TO_COMPANION_OBJECT, element]
            (classifierDescriptor ?: bindingContext[BindingContext.REFERENCE_TARGET, element])?.let { return listOf(it) }
        }

        return reference.resolveToDescriptors(bindingContext).toList()
    }

    private fun restoreReferences(referencesToRestore: Collection<ReferenceToRestoreData>, file: KtFile) {
        val importHelper = ImportInsertHelper.getInstance(file.project)
        val smartPointerManager = SmartPointerManager.getInstance(file.project)

        data class BindingRequest(
            val pointer: SmartPsiElementPointer<KtSimpleNameExpression>,
            val fqName: FqName
        )

        val bindingRequests = ArrayList<BindingRequest>()
        val descriptorsToImport = ArrayList<DeclarationDescriptor>()

        for ((reference, refData) in referencesToRestore) {
            val fqName = FqName(refData.fqName)

            if (refData.isQualifiable) {
                if (reference is KtSimpleNameReference) {
                    val pointer = smartPointerManager.createSmartPsiElementPointer(reference.element, file)
                    bindingRequests.add(BindingRequest(pointer, fqName))
                } else if (reference is KDocReference) {
                    descriptorsToImport.addAll(findImportableDescriptors(fqName, file))
                }
            } else {
                descriptorsToImport.addIfNotNull(findCallableToImport(fqName, file))
            }
        }

        for (descriptor in descriptorsToImport) {
            importHelper.importDescriptor(file, descriptor)
        }
        for ((pointer, fqName) in bindingRequests) {
            pointer.element?.mainReference?.bindToFqName(fqName, KtSimpleNameReference.ShorteningMode.DELAYED_SHORTENING)
        }
        performDelayedRefactoringRequests(file.project)
    }

    private fun findImportableDescriptors(fqName: FqName, file: KtFile): Collection<DeclarationDescriptor> {
        return file.resolveImportReference(fqName).filterNot {
            /*TODO: temporary hack until we don't have ability to insert qualified reference into root package*/
            DescriptorUtils.getParentOfType(it, PackageFragmentDescriptor::class.java)?.fqName?.isRoot ?: false
        }
    }

    private fun findCallableToImport(fqName: FqName, file: KtFile): CallableDescriptor? =
        findImportableDescriptors(fqName, file).firstIsInstanceOrNull()

    private fun showRestoreReferencesDialog(
        project: Project,
        referencesToRestore: List<ReferenceToRestoreData>
    ): Collection<ReferenceToRestoreData> {
        val fqNames = referencesToRestore.asSequence().map { it.refData.fqName }.toSortedSet()

        if (ApplicationManager.getApplication().isUnitTestMode) {
            declarationsToImportSuggested = fqNames
        }

        val shouldShowDialog = CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE == CodeInsightSettings.ASK
        if (!shouldShowDialog || referencesToRestore.isEmpty()) {
            return referencesToRestore
        }

        val dialog = RestoreReferencesDialog(project, fqNames.toTypedArray())
        dialog.show()

        val selectedFqNames = dialog.selectedElements!!.toSet()
        return referencesToRestore.filter { selectedFqNames.contains(it.refData.fqName) }
    }

    private fun toTextRanges(startOffsets: IntArray, endOffsets: IntArray): List<TextRange> {
        assert(startOffsets.size == endOffsets.size)
        return startOffsets.indices.map { TextRange(startOffsets[it], endOffsets[it]) }
    }

    private fun PsiElement.isInCopiedArea(fileCopiedFrom: KtFile, startOffsets: IntArray, endOffsets: IntArray): Boolean {
        if (containingFile != fileCopiedFrom) return false
        return toTextRanges(startOffsets, endOffsets).any { this.range in it }
    }

    private fun PsiElement.isInCopiedArea(fileCopiedFrom: KtFile, textBlocks: List<TextBlock>): Boolean {
        if (containingFile != fileCopiedFrom) return false
        return textBlocks.map { TextRange(it.startOffset, it.endOffset) }.any { this.range in it }
    }

    companion object {
        @get:TestOnly
        var declarationsToImportSuggested: Collection<String> = emptyList()

        private val LOG = Logger.getInstance(KotlinCopyPasteReferenceProcessor::class.java)

        private val IGNORE_REFERENCES_INSIDE: Array<Class<out KtElement>> = arrayOf(
            KtImportList::class.java,
            KtPackageDirective::class.java
        )
    }

}
