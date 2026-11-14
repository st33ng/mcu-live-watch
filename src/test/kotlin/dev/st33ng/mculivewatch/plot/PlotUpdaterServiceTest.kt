package dev.st33ng.mculivewatch.plot

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

/**
 * The chart must draw every variable that is being sampled, not only the ones that happened to
 * pass through `PlotManager.addVariable`.
 *
 * Regression: a variable added through the table's "+" row went into the table only. Its samples
 * landed in [SampleStore], but [PlotUpdaterService] found no [org.jfree.data.xy.XYSeries] for it and
 * silently dropped every point, so the Graph tab stayed empty while the Value column updated fine.
 */
class PlotUpdaterServiceTest : BasePlatformTestCase() {

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

    fun `test a sampled variable without a series gets one instead of losing its points`() {
        // Start the shared updater; it schedules its timer in its constructor.
        PlotUpdaterService.getInstance()

        // Nobody called PlotManager.addVariable for this name — exactly what the "+" row does.
        assertFalse(PlotManager.knownVariables().contains("orphan"))
        SampleStore.bufferFor("orphan").add(System.currentTimeMillis(), 42.0)

        assertTrue(
            "the updater must chart a variable that is being sampled",
            pumpUntil(5_000) { PlotManager.knownVariables().contains("orphan") },
        )
        assertTrue(
            "and the sample must reach the series",
            pumpUntil(5_000) { seriesItemCount("orphan") > 0 },
        )
    }

    private fun seriesItemCount(variable: String): Int {
        var count = 0
        PlotManager.forEachSeries { if (it.key.toString() == variable) count = it.itemCount }
        return count
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
