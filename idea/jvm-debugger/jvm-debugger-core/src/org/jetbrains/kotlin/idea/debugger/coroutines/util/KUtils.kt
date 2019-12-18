/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutines.util

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.memory.utils.StackFrameItem
import com.intellij.debugger.ui.impl.watch.MethodsTracker
import com.intellij.debugger.ui.impl.watch.NodeDescriptorImpl
import com.intellij.debugger.ui.impl.watch.StackFrameDescriptorImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.sun.jdi.ClassType
import javaslang.control.Either
import org.jetbrains.kotlin.idea.debugger.coroutines.data.CoroutineInfoData
import org.jetbrains.kotlin.idea.debugger.coroutines.data.SuspendStackFrameDescriptor
import org.jetbrains.kotlin.idea.debugger.coroutines.proxy.ApplicationThreadExecutor
import org.jetbrains.kotlin.idea.debugger.coroutines.proxy.LookupContinuation
import org.jetbrains.kotlin.idea.debugger.evaluate.ExecutionContext

fun getPosition(stackTraceElement: StackTraceElement, project: Project): XSourcePosition? {
    val psiFacade = JavaPsiFacade.getInstance(project)

    val psiClass = ApplicationThreadExecutor().invoke {
        @Suppress("DEPRECATION")
        psiFacade.findClass(
            stackTraceElement.className.substringBefore("$"), // find outer class, for which psi exists TODO
            GlobalSearchScope.everythingScope(project))
    }

    val classFile = psiClass?.containingFile?.virtualFile
    // to convert to 0-based line number or '-1' to do not move
    val lineNumber = if (stackTraceElement.lineNumber > 0) stackTraceElement.lineNumber - 1 else return null
    return XDebuggerUtil.getInstance().createPosition(classFile, lineNumber)
}

class EmptyStackFrameDescriptor(val frame: StackTraceElement, proxy: StackFrameProxyImpl) :
    StackFrameDescriptorImpl(proxy, MethodsTracker()) {
}
