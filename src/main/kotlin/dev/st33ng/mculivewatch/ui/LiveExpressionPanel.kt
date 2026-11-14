package dev.st33ng.mculivewatch.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.st33ng.mculivewatch.session.LiveSessionService
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Live expressions table plus its controls.
 *
 * The session itself lives in [LiveSessionService], not here: IntelliJ calls `removeNotify()` when
 * a tool-window tab is merely deselected, and tearing the session down there made switching to the
 * Graph tab silently stop sampling. This panel is now pure view — it can be created, hidden and
 * shown without affecting the capture.
 */
class LiveExpressionPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val table = dev.st33ng.mculivewatch.storage.LiveExpressionsService.getInstance(project).table
    private val session: LiveSessionService get() = LiveSessionService.getInstance(project)

    /** One line of feedback: a silent Start/Stop looks broken even when it works. */
    private val statusLabel = JBLabel("Idle")

    private val statusListener = LiveSessionService.StatusListener { text ->
        SwingUtilities.invokeLater { statusLabel.text = text }
    }

    init {
        setupUi()
        session.addStatusListener(statusListener)
        statusLabel.text = session.status
    }

    private fun setupUi() {
        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JButton("Start").apply { addActionListener { startSession() } })
            add(JButton("Stop").apply { addActionListener { session.stop() } })
            add(statusLabel.apply {
                border = JBUI.Borders.emptyLeft(8)
                foreground = JBColor.GRAY
            })
        }

        add(controls, BorderLayout.NORTH)
        add(JBScrollPane(table.table), BorderLayout.CENTER)
    }

    private fun startSession() {
        session.start()?.let { reason ->
            JOptionPane.showMessageDialog(
                this,
                reason,
                "Live Expressions",
                JOptionPane.WARNING_MESSAGE,
            )
        }
    }

    override fun removeNotify() {
        // Detach only this view's listener. The capture keeps running while another tab is shown.
        session.removeStatusListener(statusListener)
        super.removeNotify()
    }

    override fun addNotify() {
        super.addNotify()
        session.addStatusListener(statusListener)
        statusLabel.text = session.status
    }
}
