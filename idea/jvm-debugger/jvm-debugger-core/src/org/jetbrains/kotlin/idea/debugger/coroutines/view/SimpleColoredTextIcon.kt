/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutines.view

import com.intellij.debugger.impl.DebuggerUtilsEx
import com.intellij.debugger.settings.ThreadsViewSettings
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleColoredText
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.impl.ui.DebuggerUIUtil
import com.intellij.xdebugger.impl.ui.XDebuggerUIConstants
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import org.jetbrains.kotlin.idea.debugger.coroutines.data.CoroutineInfoData
import javax.swing.Icon

class SimpleColoredTextIcon(val icon: Icon?, val text: SimpleColoredText) {
    fun appendToComponent(component: ColoredTextContainer) =
        text.appendToComponent(component)

    fun forEachTextBlock(f: (Pair<String, SimpleTextAttributes>) -> Unit) {
        for (pair in text.texts zip text.attributes)
            f(pair)
    }

    fun simpleString(): String {
        val component = SimpleColoredComponent()
        appendToComponent(component)
        return component.getCharSequence(false).toString()
    }
}

class SimpleColoredTextIconPresentationRenderer {
    private val settings: ThreadsViewSettings = ThreadsViewSettings.getInstance()

    fun render(infoData: CoroutineInfoData): SimpleColoredTextIcon {
        val thread = infoData.thread
        val name = thread?.name()?.substringBefore(" @${infoData.name}") ?: ""
        val threadState = if (thread != null) DebuggerUtilsEx.getThreadStatusText(thread.status()) else ""

        val icon = when (infoData.state) {
            CoroutineInfoData.State.SUSPENDED -> AllIcons.Debugger.ThreadSuspended
            CoroutineInfoData.State.RUNNING -> AllIcons.Debugger.ThreadRunning
            CoroutineInfoData.State.CREATED -> AllIcons.Debugger.ThreadStates.Idle
        }

        val label = SimpleColoredText()
        label.append("\"")
        label.appendValue(infoData.name)
        label.append("\": ${infoData.state}")
        if(name.isNotEmpty()) {
            label.append(" on thread \"")
            label.appendValue(name)
            label.append("\": $threadState")
        }
        return SimpleColoredTextIcon(icon, label)
    }

    /**
     * Taken from #StackFrameDescriptorImpl.calcRepresentation
     */
    fun render(location: Location): SimpleColoredTextIcon {
        val label = SimpleColoredText()
        DebuggerUIUtil.getColorScheme(null)
        if (location.method() != null) {
            val myName = location.method().name()
            val methodDisplay = if (settings.SHOW_ARGUMENTS_TYPES)
                DebuggerUtilsEx.methodNameWithArguments(location.method())
            else
                myName
            label.append(methodDisplay, XDebuggerUIConstants.VALUE_NAME_ATTRIBUTES)
        }
        if (settings.SHOW_LINE_NUMBER) {
            label.append(":")
            label.append("" + DebuggerUtilsEx.getLineNumber(location, false))
        }
        if (settings.SHOW_CLASS_NAME) {
            val name: String?
            name = try {
                val refType: ReferenceType = location.declaringType()
                refType.name()
            } catch (e: InternalError) {
                e.toString()
            }
            if (name != null) {
                label.append(", ")
                val dotIndex = name.lastIndexOf('.')
                if (dotIndex < 0) {
                    label.append(name)
                } else {
                    label.append(name.substring(dotIndex + 1))
                    if (settings.SHOW_PACKAGE_NAME) {
                        label.append(" (${name.substring( 0, dotIndex)})")
                    }
                }
            }
        }
        if (settings.SHOW_SOURCE_NAME) {
            label.append(", ")
            val sourceName = DebuggerUtilsEx.getSourceName(location) { e: Throwable? -> "Unknown Source" }
            label.append(sourceName)
        }
        return SimpleColoredTextIcon(null, label)
    }

    fun renderCreationNode(infoData: CoroutineInfoData): SimpleColoredTextIcon {
        val label = SimpleColoredText()
        label.append("Creation stack frame of ${infoData.name}")
        return SimpleColoredTextIcon(AllIcons.Debugger.ThreadSuspended, label)
    }

    // extensions for code readability
    fun SimpleColoredText.append(text: String) =
        append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)

    fun SimpleColoredText.appendValue(text: String) =
        append(text, XDebuggerUIConstants.VALUE_NAME_ATTRIBUTES)

    fun renderErrorNode(error: String): SimpleColoredTextIcon {
        val label = SimpleColoredText()
        label.append(error)
        return SimpleColoredTextIcon(AllIcons.Debugger.ThreadStates.Exception, label)

    }

    fun renderRoorNode(text: String): SimpleColoredTextIcon {
        val label = SimpleColoredText()
        label.append(text)
        return SimpleColoredTextIcon(null, label)
    }
}