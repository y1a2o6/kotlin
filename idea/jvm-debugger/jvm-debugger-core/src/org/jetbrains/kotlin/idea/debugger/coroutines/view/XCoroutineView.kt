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
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueContainerNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueGroupNodeImpl
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
import java.util.*
import javax.swing.Icon


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
            panel.tree.setRoot(emptyNode(), false)
        }
    }

    private fun emptyNode() =
        object: XValueContainerNode<XValueContainer>(panel.tree, null, true, object : XValueContainer() {}) {}

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
        XValueGroupNodeImpl(panel.tree, null, CoroutineGroupContainer(suspendContext))

    inner class CoroutineGroupContainer(val suspendContext: XSuspendContext) : XValueGroup("default") {
        override fun computeChildren(node: XCompositeNode) {
            val children = XValueChildrenList.bottomGroup(CoroutineContainer(suspendContext as SuspendContextImpl, "Default group"))
            node.addChildren(children, true)
        }
    }

    inner class CoroutineContainer(
        val suspendContext: SuspendContextImpl,
        val groupName: String
    ) : XValueGroup(groupName) {
        override fun computeChildren(node: XCompositeNode) {
            managerThreadExecutor.noschedule {
//                Thread.sleep(5500)
                val rnd = Random().nextInt()
                val debugProbesProxy = CoroutinesDebugProbesProxy(suspendContext)

                var coroutineCache = debugProbesProxy.dumpCoroutines()
                if(coroutineCache.isOk()) {
                    val children = XValueChildrenList()
                    coroutineCache.cache.forEach {
                        children.addTopGroup(FramesContainer(it, suspendContext))
                    }
                    node.addChildren(children, true)
                } else {
                    node.addChildren(XValueChildrenList.singleton(ErrorNode("Error occured while fetching information")), true)
                }
            }
        }

        override fun isRestoreExpansion() =
            true

        override fun getIcon(): Icon? {
            return AllIcons.Debugger.ThreadGroup
        }
    }

    inner class ErrorNode(val error: String) : XNamedValue(error) {
        override fun computePresentation(node: XValueNode, place: XValuePlace) {
            applyPresentation(node, renderer.renderErrorNode(error), false)
        }

    }

    inner class FramesContainer(
        private val infoData: CoroutineInfoData,
        private val suspendContext: SuspendContextImpl
    ) : XValueGroup(infoData.name) {
        override fun computeChildren(node: XCompositeNode) {
            managerThreadExecutor.schedule {
                val debugProbesProxy = CoroutinesDebugProbesProxy(suspendContext)
                val children = XValueChildrenList()
                debugProbesProxy.frameBuilder().build(infoData)
                val creationStack = mutableListOf<CreationCoroutineStackFrameItem>()
                infoData.stackFrameList.forEach {
                    if(it is CreationCoroutineStackFrameItem)
                        creationStack.add(it)
                    else
                        children.add("", CoroutineFrameValue(it))
                }
                children.addBottomGroup(CreationFramesContainer(creationStack))
                node.addChildren(children, true)
            }
        }

        override fun isRestoreExpansion() =
            true

        override fun getIcon() : Icon =
            when (infoData.state) {
                CoroutineInfoData.State.SUSPENDED -> AllIcons.Debugger.ThreadSuspended
                CoroutineInfoData.State.RUNNING -> AllIcons.Debugger.ThreadRunning
                CoroutineInfoData.State.CREATED -> AllIcons.Debugger.ThreadStates.Idle
            }

        fun computePresentation(node: XValueNode, place: XValuePlace) {
            applyPresentation(node, renderer.render(infoData), true)
        }
    }

    inner class CreationFramesContainer(private val creationFrames: List<CreationCoroutineStackFrameItem>) : XValueGroup("Creation stack frame") {
        override fun computeChildren(node: XCompositeNode) {
            val children = XValueChildrenList()

            creationFrames.forEach {
                children.add("", CoroutineFrameValue(it))
            }
            node.addChildren(children, true)
        }

        override fun getIcon() = AllIcons.Debugger.ThreadSuspended

        override fun isRestoreExpansion() =
            true
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

    fun resetRoot(suspendContext: XSuspendContext) {
        log.error("Resetted root")
        panel.tree.setRoot(createRoot(suspendContext), false)
    }
}








