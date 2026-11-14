package dev.st33ng.mculivewatch.plot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the chart's retention and reset behaviour.
 *
 * `clearData()` exists so a new capture starts on a clean time axis while the variables stay
 * registered (the legend and colour assignments must survive). Clearing the whole dataset instead
 * would silently drop the user's selection between Start presses.
 */
class PlotManagerTest {

    @Test
    fun `adding the same variable twice keeps one series`() {
        PlotManager.clearAll()
        try {
            PlotManager.addVariable("ticks")
            PlotManager.addVariable("ticks")

            assertEquals(1, PlotManager.knownVariables().size)
        } finally {
            PlotManager.clearAll()
        }
    }

    @Test
    fun `clearData empties points but keeps the variable registered`() {
        PlotManager.clearAll()
        try {
            PlotManager.addVariable("ticks")
            PlotManager.forEachSeries { it.add(1.0, 42.0) }

            PlotManager.clearData()

            assertEquals(listOf("ticks"), PlotManager.knownVariables())
            PlotManager.forEachSeries {
                assertEquals("series should keep its key", "ticks", it.key.toString())
                assertEquals("points should be gone", 0, it.itemCount)
            }
        } finally {
            PlotManager.clearAll()
        }
    }

    @Test
    fun `series never retains more than the configured point budget`() {
        PlotManager.clearAll()
        try {
            PlotManager.addVariable("ticks")
            PlotManager.forEachSeries { series ->
                repeat(PlotManager.MAX_POINTS_PER_SERIES + 500) { i ->
                    series.addOrUpdate(i.toDouble(), i.toDouble())
                }
                assertTrue(
                    "expected <= ${PlotManager.MAX_POINTS_PER_SERIES}, got ${series.itemCount}",
                    series.itemCount <= PlotManager.MAX_POINTS_PER_SERIES,
                )
            }
        } finally {
            PlotManager.clearAll()
        }
    }
}
