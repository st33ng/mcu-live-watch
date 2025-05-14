package com.jfrog.MyPlugin.clion.toolWindow

import com.jfrog.MyPlugin.clion.Storage.LiveExpressionTable
import com.jfrog.MyPlugin.clion.Storage.LiveExpressionTable.valueHistory
import com.jfrog.MyPlugin.clion.plot.PlotManager
import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartPanel
import org.jfree.chart.JFreeChart
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.xy.XYSeries
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.swing.*

class GraphPanel : JPanel(BorderLayout()) {
    private val chart: JFreeChart = ChartFactory.createXYLineChart(
        "Live Variables", "Time (s)", "Value", PlotManager.dataset
    )
    private val chartPanel = ChartPanel(chart)
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    init {
        setupUI()
        startUpdater()
    }

    private fun setupUI() {
        val controlPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("График переменных в реальном времени"))
        }

        add(controlPanel, BorderLayout.NORTH)
        add(chartPanel, BorderLayout.CENTER)

        chart.xyPlot.renderer = XYLineAndShapeRenderer(true, false).apply {
            setSeriesStroke(0, BasicStroke(1.5f)) // Тонкие линии
            drawSeriesLineAsPath = false // Оптимизация для большого числа точек
        }
    }

    private fun startUpdater() {
        executor.scheduleAtFixedRate({
            updateSeries()
        }, 0, 200, TimeUnit.MILLISECONDS) // Обновление каждые 500 мс
    }

    private fun updateSeries() {
        val seriesData = mutableMapOf<String, List<Pair<Double, Double>>>()
        val maxPointsPerSeries = 2000 // Максимум точек в серии

        PlotManager.forEachSeries { series ->
            val variableName = series.key.toString()
            val history = synchronized(valueHistory) {
                valueHistory[variableName]?.toList() ?: emptyList()
            }
            val dataPoints = history.mapNotNull { (time, value) ->
                value.toPlotValue()?.let { plotValue ->
                    time.toDouble() / 1000.0 to plotValue // Переводим время в секунды
                }
            }
            if (dataPoints.isNotEmpty()) {
                seriesData[variableName] = dataPoints
            }
        }

        SwingUtilities.invokeLater {
            PlotManager.forEachSeries { series ->
                val variableName = series.key.toString()
                seriesData[variableName]?.forEach { (time, plotValue) ->
                    series.addOrUpdate(time, plotValue)
                }
                while (series.itemCount > maxPointsPerSeries) {
                    series.remove(0) // Удаляем самые старые точки
                }
            }
            chartPanel.repaint()
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        executor.shutdown()
    }
}