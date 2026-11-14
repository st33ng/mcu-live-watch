package dev.st33ng.mculivewatch.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.st33ng.mculivewatch.bridge.BridgeProcessService
import dev.st33ng.mculivewatch.plot.GraphPanel

/**
 * Builds the tool window contents.
 *
 * The old factory started the Python bridge right here and ignored the returned process, so every
 * tool window created another copy. The bridge is now owned by [BridgeProcessService].
 */
class McuLiveWatchToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        toolWindow.contentManager.addContent(
            contentFactory.createContent(ElfParsePanel(project), "Elf Parser", false)
        )
        toolWindow.contentManager.addContent(
            contentFactory.createContent(LiveExpressionPanel(project), "Live Expressions", false)
        )
        toolWindow.contentManager.addContent(
            contentFactory.createContent(GraphPanel(project), "Graph", false)
        )
    }
}
