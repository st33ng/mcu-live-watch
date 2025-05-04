package com.jfrog.MyPlugin.clion.plot

import com.jfrog.MyPlugin.clion.toolWindow.CppValue
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import java.util.concurrent.ConcurrentHashMap

object PlotManager {
    val dataset = XYSeriesCollection()
    private val seriesMap = ConcurrentHashMap<String, XYSeries>()
    private val lock = Any()

    fun addVariable(variableName: String) {
        synchronized(lock) {
            if (seriesMap.containsKey(variableName)) return
            val series = XYSeries(variableName)
            seriesMap[variableName] = series
            dataset.addSeries(series)
            println("Added series: $variableName")
        }
    }

    fun removeVariable(variableName: String) {
        synchronized(lock) {
            seriesMap.remove(variableName)?.let {
                dataset.removeSeries(it)
                println("Removed series: $variableName")
            }
        }
    }

    fun forEachSeries(block: (XYSeries) -> Unit) {
        synchronized(lock) {
            seriesMap.values.forEach(block)
        }
    }
}