package dev.st33ng.mculivewatch.plot

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import org.jfree.data.xy.XYSeries
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Single shared chart updater for the whole IDE.
 *
 * Why this exists: the old [GraphPanel] created its own `ScheduledExecutorService`, copied the
 * entire sample history on every tick and re-inserted every point into the series. With several
 * tool windows open that cost multiplied, and `executor.shutdown()` in `removeNotify` meant the
 * updater could never be restarted.
 *
 * This service runs one timer, drains each [SampleBuffer] incrementally from a per-variable cursor,
 * and appends only the new samples. Points are decimated to a pixel budget so the render cost stays
 * flat no matter how long sampling runs.
 */
@Service(Service.Level.APP)
class PlotUpdaterService : Disposable {

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mcu-live-plot-updater").apply { isDaemon = true }
        }

    private val cursors = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val running = AtomicBoolean(true)
    private val panels = java.util.concurrent.CopyOnWriteArrayList<GraphPanel>()

    /** Points appended to the chart per tick, so a burst does not flood the EDT. */
    private val maxPointsPerTick = 2000

    init {
        executor.scheduleAtFixedRate(
            { safeUpdate() },
            UPDATE_INTERVAL_MS,
            UPDATE_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun registerPanel(panel: GraphPanel) {
        if (!panels.contains(panel)) panels.add(panel)
    }

    fun unregisterPanel(panel: GraphPanel) {
        panels.remove(panel)
    }

    /**
     * Forgets the drain position of every variable.
     *
     * Called when a new capture starts: [SampleStore] is emptied at that point, so cursors would
     * otherwise point past the end of the fresh buffers and the first samples would be skipped.
     */
    fun resetCursors() {
        cursors.clear()
    }

    private fun safeUpdate() {
        if (!running.get()) return
        try {
            update()
        } catch (t: Throwable) {
            thisLogger().warn("Live plot update failed", t)
        }
    }

    private fun update() {
        val variables = SampleStore.variables()
        if (variables.isEmpty()) {
            notifyPanels(0, 0)
            return
        }

        val drained = ArrayList<Pair<String, List<SampleBuffer.Sample>>>(variables.size)
        for (variable in variables) {
            val buffer = SampleStore.bufferFor(variable)
            val samples = ArrayList<SampleBuffer.Sample>(256)
            cursors[variable] = buffer.drain(cursors[variable] ?: 0L, samples)
            if (samples.isNotEmpty()) drained.add(variable to samples)
        }

        // Forget cursors of variables that disappeared, so the map cannot grow forever.
        cursors.keys.retainAll(variables)

        if (drained.isEmpty()) {
            notifyPanels(variables.size, totalPoints())
            return
        }

        SwingUtilities.invokeLater {
            var appended = 0
            for ((variable, samples) in drained) {
                // Create the series on demand. A variable can be sampled without anyone having
                // called PlotManager.addVariable for it — e.g. when it was typed into the table's
                // "+" row. Skipping it dropped every point and left the Graph tab empty while the
                // Value column kept updating; the user has no way to tell why.
                val series = seriesFor(variable) ?: PlotManager.addVariable(variable).also {
                    thisLogger().info("Chart series created on demand for '$variable'")
                }
                val decimated = decimate(samples, maxPointsPerTick)
                for (sample in decimated) {
                    series.addOrUpdate(
                        sample.timestampMs / 1000.0,
                        sample.value,
                    )
                    appended++
                }
            }
            notifyPanels(variables.size, totalPoints() + appended)
        }
    }

    private fun seriesFor(variable: String): XYSeries? {
        var found: XYSeries? = null
        PlotManager.forEachSeries { series ->
            if (series.key.toString() == variable) found = series
        }
        return found
    }

    /**
     * Min/max decimation: keeps the first, last, minimum and maximum of each bucket, so spikes are
     * not lost while the number of appended points stays bounded by [budget].
     */
    private fun decimate(samples: List<SampleBuffer.Sample>, budget: Int): List<SampleBuffer.Sample> {
        if (samples.size <= budget) return samples

        val bucketCount = budget / 4
        val bucketSize = (samples.size + bucketCount - 1) / bucketCount
        val result = ArrayList<SampleBuffer.Sample>(budget)

        var i = 0
        while (i < samples.size) {
            val end = minOf(i + bucketSize, samples.size)
            var minIdx = i
            var maxIdx = i
            for (j in i until end) {
                if (samples[j].value < samples[minIdx].value) minIdx = j
                if (samples[j].value > samples[maxIdx].value) maxIdx = j
            }
            val first = minOf(i, minIdx, maxIdx)
            val last = maxOf(end - 1, minIdx, maxIdx)
            result.add(samples[first])
            if (minIdx != first) result.add(samples[minOf(minIdx, maxIdx)])
            if (last != first) result.add(samples[last])
            i = end
        }
        return result
    }

    private fun totalPoints(): Int {
        var total = 0
        PlotManager.forEachSeries { total += it.itemCount }
        return total
    }

    private fun notifyPanels(variableCount: Int, totalPoints: Int) {
        if (panels.isEmpty()) return
        SwingUtilities.invokeLater {
            for (panel in panels) panel.onDataUpdated(variableCount, totalPoints)
        }
    }

    override fun dispose() {
        running.set(false)
        panels.clear()
        executor.shutdownNow()
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 200L

        fun getInstance(): PlotUpdaterService = service()
    }
}
