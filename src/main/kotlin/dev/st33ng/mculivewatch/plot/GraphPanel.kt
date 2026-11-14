package dev.st33ng.mculivewatch.plot

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.st33ng.mculivewatch.session.LiveSessionService
import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartPanel
import org.jfree.chart.JFreeChart
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Renders live samples as line charts.
 *
 * Data flow: [SampleStore] ring buffers -> [PlotUpdaterService] (one shared timer, off the EDT)
 * -> this panel (EDT, incremental append only).
 *
 * The panel attaches to [PlotUpdaterService] in `addNotify()`, not in the constructor: IntelliJ
 * calls `removeNotify()` whenever the tool-window tab becomes invisible, so registering in the
 * constructor meant the chart stopped refreshing the first time another tab was selected.
 * Sampling itself never depends on this panel — [LiveSessionService] owns it — so the dataset keeps
 * filling while the graph is hidden and appears complete when the tab is opened again.
 */
class GraphPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val chart: JFreeChart = ChartFactory.createXYLineChart(
        "Live Variables", "Time (s)", "Value", PlotManager.dataset
    )

    private val chartPanel = ChartPanel(chart).apply {
        preferredSize = Dimension(600, 320)
        isDomainZoomable = true
        isRangeZoomable = true
    }

    private val statusLabel = JBLabel("Ожидание данных")
    private val session: LiveSessionService get() = LiveSessionService.getInstance(project)

    private val statusListener = LiveSessionService.StatusListener { text ->
        SwingUtilities.invokeLater { sessionStatusLabel.text = text }
    }

    /** Mirror of the panel's own data counter, kept separate from the session status text. */
    private val sessionStatusLabel = JBLabel("Idle")

    init {
        chart.xyPlot.renderer = XYLineAndShapeRenderer(true, false).apply {
            defaultStroke = BasicStroke(1.2f)
            // Drawing all points of a long series as separate shapes is the main repaint cost.
            drawSeriesLineAsPath = true
        }
        chart.xyPlot.domainAxis.isAutoRange = true
        chart.xyPlot.rangeAxis.isAutoRange = true

        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JButton("Start").apply {
                addActionListener {
                    session.start()?.let { reason ->
                        javax.swing.JOptionPane.showMessageDialog(
                            this@GraphPanel,
                            reason,
                            "Live Expressions",
                            javax.swing.JOptionPane.WARNING_MESSAGE,
                        )
                    }
                }
            })
            add(JButton("Stop").apply { addActionListener { session.stop() } })
            add(sessionStatusLabel.apply {
                border = JBUI.Borders.emptyLeft(8)
                foreground = JBColor.GRAY
                text = session.status
            })
        }

        val header = JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.WEST)
            add(statusLabel.apply { border = JBUI.Borders.emptyLeft(8) }, BorderLayout.CENTER)
        }

        add(header, BorderLayout.NORTH)
        add(chartPanel, BorderLayout.CENTER)
    }

    /** Called on the EDT by [PlotUpdaterService]. */
    internal fun onDataUpdated(variableCount: Int, totalPoints: Int) {
        statusLabel.text = if (variableCount == 0) {
            "Ожидание данных"
        } else {
            "Переменных: $variableCount · точек: $totalPoints"
        }
        chartPanel.repaint()
    }

    override fun addNotify() {
        super.addNotify()
        PlotUpdaterService.getInstance().registerPanel(this)
        session.addStatusListener(statusListener)
        sessionStatusLabel.text = session.status
        // Show whatever was captured while this tab was hidden.
        chartPanel.repaint()
    }

    override fun removeNotify() {
        // Only detach this panel; the shared updater belongs to the project and keeps running.
        PlotUpdaterService.getInstance().unregisterPanel(this)
        session.removeStatusListener(statusListener)
        super.removeNotify()
    }
}
