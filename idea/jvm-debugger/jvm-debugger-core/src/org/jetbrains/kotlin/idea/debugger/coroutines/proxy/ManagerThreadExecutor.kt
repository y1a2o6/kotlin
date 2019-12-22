/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutines.proxy

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.impl.PrioritizedTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable

class ManagerThreadExecutor(val debugProcess: DebugProcessImpl) {
    fun on(suspendContext: SuspendContextImpl, priority: PrioritizedTask.Priority = PrioritizedTask.Priority.NORMAL) =
        ManagerThreadExecutorInstance(suspendContext, priority)

    inner class ManagerThreadExecutorInstance(
        val suspendContext: SuspendContextImpl,
        val priority: PrioritizedTask.Priority = PrioritizedTask.Priority.NORMAL
    ) {

        fun schedule(f: (SuspendContextImpl) -> Unit) {
            val suspendContextCommand = object : SuspendContextCommandImpl(suspendContext) {
                override fun getPriority() = this@ManagerThreadExecutorInstance.priority

                override fun contextAction(suspendContext: SuspendContextImpl) {
                    f(suspendContext)
                }
            }
            debugProcess.managerThread.invoke(suspendContextCommand)
        }
    }
}

class ApplicationThreadExecutor {
    fun <T> invoke(f: () -> T): T {
        return ApplicationManager.getApplication().runReadAction(Computable(f))
    }
}
