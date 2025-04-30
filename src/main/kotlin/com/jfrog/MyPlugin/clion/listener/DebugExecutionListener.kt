package com.jfrog.MyPlugin.clion.listener

import com.intellij.execution.ExecutionListener
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.ApplicationManager
import com.jfrog.MyPlugin.clion.StateServices.DebugSessionStateService
import kotlin.jvm.java

class DebugExecutionListener : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (executorId == DefaultDebugExecutor.EXECUTOR_ID) {
            ApplicationManager.getApplication().getService(DebugSessionStateService::class.java).isDebugActive = true
            println("🔍 CLion started a Debug session.")
        }
    }
    override fun processTerminated(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler,
        exitCode: Int
    ) {
        ApplicationManager.getApplication().getService(DebugSessionStateService::class.java).isDebugActive = false
        println("🔍 CLion stopped a Debug session.")
    }
}