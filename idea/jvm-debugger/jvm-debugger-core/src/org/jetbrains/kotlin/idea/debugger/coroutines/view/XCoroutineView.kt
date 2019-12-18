/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutines.view

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.JavaDebugProcess
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.util.SingleAlarm
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTreePanel
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTreeState
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueContainerNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.debugger.coroutines.CoroutineDebuggerContentInfo
import org.jetbrains.kotlin.idea.debugger.coroutines.CoroutineDebuggerContentInfo.Companion.XCOROUTINE_POPUP_ACTION_GROUP
import org.jetbrains.kotlin.idea.debugger.coroutines.command.CoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutines.command.CreationCoroutineStackFrameItem
import org.jetbrains.kotlin.idea.debugger.coroutines.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutines.proxy.CoroutinesDebugProbesProxy
import org.jetbrains.kotlin.idea.debugger.coroutines.proxy.ManagerThreadExecutor
import org.jetbrains.kotlin.idea.debugger.coroutines.util.CreateContentParams
import org.jetbrains.kotlin.idea.debugger.coroutines.util.CreateContentParamsProvider
import org.jetbrains.kotlin.idea.debugger.coroutines.util.XDebugSessionListenerProvider
import org.jetbrains.kotlin.idea.debugger.coroutines.util.logger


class XCoroutineView(val project: Project, val session: XDebugSession) :
    Disposable, XDebugSessionListenerProvider, CreateContentParamsProvider {
    val log by logger
    val splitter = OnePixelSplitter("SomeKey", 0.25f)
    val panel = XDebuggerTreePanel(project, session.debugProcess.editorsProvider, this, null, XCOROUTINE_POPUP_ACTION_GROUP, null)
    val alarm = SingleAlarm(Runnable { clear() }, VIEW_CLEAR_DELAY, this)
    val debugProcess: DebugProcessImpl = (session.debugProcess as JavaDebugProcess).debuggerSession.process
    val renderer = SimpleColoredTextIconPresentationRenderer()
    val managerThreadExecutor = ManagerThreadExecutor(debugProcess)

    companion object {
        private val VIEW_CLEAR_DELAY = 100 //ms
    }

    init {
        splitter.firstComponent = panel.mainPanel
    }

    fun clear() {
        DebuggerUIUtil.invokeLater {
            panel.tree
                .setRoot(object : XValueContainerNode<XValueContainer>(panel.tree, null, true, object : XValueContainer() {}) {}, false)
        }
    }

    override fun dispose() {
    }

    fun forceClear() {
        alarm.cancel()
        clear()
    }

    fun createRoot(suspendContext: XSuspendContext) =
        XCoroutinesRootNode(suspendContext)

    override fun debugSessionListener(session: XDebugSession) =
        CoroutineViewDebugSessionListener(session, this)

    override fun createContentParams(): CreateContentParams =
        CreateContentParams(
            CoroutineDebuggerContentInfo.XCOROUTINE_THREADS_CONTENT,
            splitter,
            KotlinBundle.message("debugger.session.tab.xcoroutine.title"),
            null,
            panel.tree
        )

    inner class XCoroutinesRootNode(suspendContext: XSuspendContext) :
        XValueContainerNode<CoroutineGroupContainer>(panel.tree, null, false, CoroutineGroupContainer(suspendContext))

    inner class CoroutineGroupContainer(val suspendContext: XSuspendContext) : XValueContainer() {
        override fun computeChildren(node: XCompositeNode) {
            val children = XValueChildrenList()
            children.add(CoroutineContainer(suspendContext as SuspendContextImpl, "Default group"))
            node.addChildren(children, true)
        }
    }

    inner class CoroutineContainer(
        val suspendContext: SuspendContextImpl,
        val groupName: String
    ) : XNamedValue("") {
        override fun computeChildren(node: XCompositeNode) {
            managerThreadExecutor.schedule {
                val debugProbesProxy = CoroutinesDebugProbesProxy(suspendContext)

                var coroutineCache = debugProbesProxy.dumpCoroutines()
                if(coroutineCache.isOk()) {
                    val children = XValueChildrenList()
                    coroutineCache.cache.forEach {
                        children.add(FramesContainer(it, debugProbesProxy))
                    }
                    node.addChildren(children, true)
                } else
                    node.addChildren(XValueChildrenList.EMPTY, true)
            }
        }

        override fun computePresentation(node: XValueNode, place: XValuePlace) {
            node.setPresentation(AllIcons.Debugger.ThreadGroup, XRegularValuePresentation(groupName, null, ""), true)
        }
    }

    inner class FramesContainer(
        private val infoData: CoroutineInfoData,
        private val debugProbesProxy: CoroutinesDebugProbesProxy
    ) : XNamedValue(infoData.name) {
        override fun computeChildren(node: XCompositeNode) {
            managerThreadExecutor.schedule {
                val children = XValueChildrenList()
                debugProbesProxy.frameBuilder().build(infoData)
                val creationStack = mutableListOf<CreationCoroutineStackFrameItem>()
                infoData.stackFrameList.forEach {
                    if(it is CreationCoroutineStackFrameItem)
                        creationStack.add(it)
                    else
                        children.add("", CoroutineFrameValue(it))
                }
                children.add("", CreationFramesContainer(creationStack))
                node.addChildren(children, true)
            }
        }

        override fun computePresentation(node: XValueNode, place: XValuePlace) {
            applyPresentation(node, renderer.render(infoData), true)
        }
    }

    inner class CreationFramesContainer(private val creationFrames: List<CreationCoroutineStackFrameItem>) : XValue() {
        override fun computeChildren(node: XCompositeNode) {
            val children = XValueChildrenList()

            creationFrames.forEach {
                children.add("", CoroutineFrameValue(it))
            }
            node.addChildren(children, true)
        }

        override fun computePresentation(node: XValueNode, place: XValuePlace) {
            applyPresentation(node, renderer.renderCreationNode(), true)
        }
    }

    inner class CoroutineFrameValue(val frame: CoroutineStackFrameItem) : XValue() {
        override fun computePresentation(node: XValueNode, place: XValuePlace) =
            applyPresentation(node, renderer.render(frame.location()), false)
    }

    private fun applyPresentation(node: XValueNode, presentation: SimpleColoredTextIcon, hasChildren: Boolean) {
        // set b&w by default
        blackWhiteProcess(presentation, node, hasChildren)

        // replace with colored text if supported
        if (node is XValueNodeImpl)
            colorOverride(presentation, node)
    }

    private fun colorOverride(coloredText: SimpleColoredTextIcon, node: XValueNodeImpl) {
        node.text.clear()
        coloredText.forEachTextBlock { (text, attribute) ->
            node.text.append(text, attribute)
        }
    }

    private fun blackWhiteProcess(coloredText: SimpleColoredTextIcon, node: XValueNode, hasChildren: Boolean) {
        // black&white
        val component = SimpleColoredComponent()
        coloredText.appendToComponent(component)
        val valuePresentation = XRegularValuePresentation(component.getCharSequence(false).toString(), null, "")
        node.setPresentation(coloredText.icon, valuePresentation, hasChildren)
    }

    fun saveAndRestore(suspendContext: XSuspendContext) {
        val treeState = XDebuggerTreeState.saveState(panel.tree)
        panel.tree.setRoot(createRoot(suspendContext), false)
        panel.tree.rebuildAndRestore(treeState)
    }
}








