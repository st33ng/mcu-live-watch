package dev.st33ng.mculivewatch.plot

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * Renders the real chart panel and checks that a live sample becomes a visible point.
 *
 * This covers the other half of "the Graph tab is empty": even when the series exists and receives
 * points, a chart bound to a dataset that was empty at construction can still keep a collapsed
 * axis range. The assertion is on the *axis*, not just on the series, because a point outside the
 * visible range is invisible to the user even though the data is there.
 */
class GraphPanelTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Otherwise the fixture's tearDown reports "Code insight settings damaged".
        setDefaultCodeInsightSettings(CodeInsightSettings.getInstance())
        PlotManager.clearAll()
        SampleStore.clear()
    }

    override fun tearDown() {
        try {
            PlotManager.clearAll()
            SampleStore.clear()
        } finally {
            super.tearDown()
        }
    }

    fun `test a live sample becomes a point inside the visible range`() {
        val panel = onEdt { GraphPanel(project).also { it.addNotify() } }
        try {
            val now = System.currentTimeMillis()
            SampleStore.bufferFor("v").add(now - 500, 1.0)
            SampleStore.bufferFor("v").add(now, 2.0)

            assertTrue(
                "the series must receive the samples",
                pumpUntil(5_000) { itemCount("v") >= 2 },
            )
            assertTrue(
                "the domain axis must cover the samples, otherwise the line is off-screen",
                onEdt {
                    val axis = chartOf(panel).xyPlot.domainAxis
                    axis.lowerBound <= (now - 500) / 1000.0 && axis.upperBound >= now / 1000.0
                },
            )
            assertTrue(
                "the value axis must cover the samples",
                onEdt {
                    val axis = chartOf(panel).xyPlot.rangeAxis
                    axis.lowerBound <= 1.0 && axis.upperBound >= 2.0
                },
            )
        } finally {
            onEdt { panel.removeNotify() }
        }
    }

    private fun chartOf(panel: GraphPanel) =
        GraphPanel::class.java.getDeclaredField("chart").apply { isAccessible = true }.get(panel)
            as org.jfree.chart.JFreeChart

    private fun itemCount(variable: String): Int {
        var count = 0
        PlotManager.forEachSeries { if (it.key.toString() == variable) count = it.itemCount }
        return count
    }

    private fun <T> onEdt(block: () -> T): T {
        if (ApplicationManager.getApplication().isDispatchThread) return block()
        var result: T? = null
        UIUtil.invokeAndWaitIfNeeded { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun pumpUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) return true
            Thread.sleep(20)
        }
        UIUtil.dispatchAllInvocationEvents()
        return condition()
    }
}
