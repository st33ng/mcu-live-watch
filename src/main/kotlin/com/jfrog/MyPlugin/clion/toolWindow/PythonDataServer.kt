package com.jfrog.MyPlugin.clion.toolWindow

import java.io.File

class PythonDataServer {
    fun start(scriptPath: String) {
        ProcessBuilder("python3", scriptPath)
            .directory(File("."))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    }
}