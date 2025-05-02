package com.jfrog.MyPlugin.clion.StateServices

import com.intellij.openapi.components.Service

@Service
class ReadThreadRunningStateService {
    @Volatile
    var readRunning : Boolean = false
}