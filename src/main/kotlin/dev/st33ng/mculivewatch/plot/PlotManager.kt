package dev.st33ng.mculivewatch.plot

import dev.st33ng.mculivewatch.storage.LiveExpressionTable
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the chart dataset and the per-variable retention policy.
 *
 * Retention is enforced inside [XYSeries] through [XYSeries.setMaximumItemCount] instead of the
 * previous `while (itemCount > max) series.remove(0)` loop: `remove(0)` is O(n) per call and the
 * loop ran on every repaint tick, which made the chart progressively slower until the IDE froze.
 */
object PlotManager {
    /** Hard cap of retained points per series. 2000 points is the same budget the old code aimed for. */
    const val MAX_POINTS_PER_SERIES = 2000

    val dataset = XYSeriesCollection()
    private val seriesMap = ConcurrentHashMap<String, XYSeries>()
    private val lock = Any()

    /** Returns the series for [variableName], creating it when it does not exist yet. */
    fun addVariable(variableName: String): XYSeries {
        synchronized(lock) {
            seriesMap[variableName]?.let { return it }
            // autoSort = false, allowDuplicateXValues = false: samples arrive in time order and a
            // repeated timestamp updates the existing point instead of appending a duplicate.
            val series = XYSeries(variableName, false, false).apply {
                maximumItemCount = MAX_POINTS_PER_SERIES
            }
            seriesMap[variableName] = series
            dataset.addSeries(series)
            return series
        }
    }

    fun removeVariable(variableName: String) {
        synchronized(lock) {
            seriesMap.remove(variableName)?.let { dataset.removeSeries(it) }
        }
    }

    fun clearAll() {
        synchronized(lock) {
            seriesMap.keys.toList().forEach { removeVariable(it) }
        }
    }

    /**
     * Empties every series but keeps the variables registered, so the chart keeps its legend and
     * colour assignments while a new capture starts from a clean time axis.
     *
     * Must be called on the EDT: it fires dataset change events that Swing repaints from.
     */
    fun clearData() {
        synchronized(lock) {
            seriesMap.values.forEach { series -> series.clear() }
        }
    }

    fun knownVariables(): List<String> = seriesMap.keys().toList()

    fun forEachSeries(block: (XYSeries) -> Unit) {
        synchronized(lock) {
            seriesMap.values.forEach(block)
        }
    }
}
