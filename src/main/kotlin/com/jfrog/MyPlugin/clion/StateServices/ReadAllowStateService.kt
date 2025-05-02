package com.jfrog.MyPlugin.clion.StateServices

import com.intellij.openapi.components.Service

@Service
class ReadAllowStateService {
    @Volatile
    var readAllow: Boolean = false
}