package com.jfrog.MyPlugin.clion.StateServices

import com.intellij.openapi.components.Service

@Service
class DebugSessionStateService {
    var isDebugActive: Boolean = false
}