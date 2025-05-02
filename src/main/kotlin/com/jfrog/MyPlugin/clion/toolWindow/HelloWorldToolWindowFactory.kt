package com.jfrog.MyPlugin.clion.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

//TODO после выхода из дебага остановить запросы.
class HelloWorldToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        // ELF PARSER VIEW.
        val elfParsePanel = ElfParsePanel(project)
        val content = contentFactory.createContent(elfParsePanel, "Elf Parser", false)
        toolWindow.contentManager.addContent(content)

        // LIVE EXPR VIEW.
        val liveExprPanel = LiveExperssionPanel()
        val liveExprContent = contentFactory.createContent(liveExprPanel, "Live Expressions", false)
        toolWindow.contentManager.addContent(liveExprContent)


        val pythonServer = PythonDataServer()
        pythonServer.start("/Users/timtim/PycharmProjects/pythonProject/sockets_var.py")
    }
}