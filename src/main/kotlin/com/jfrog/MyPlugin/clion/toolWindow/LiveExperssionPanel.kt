package com.jfrog.MyPlugin.clion.toolWindow

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.jfrog.MyPlugin.clion.StateServices.DebugSessionStateService
import com.jfrog.MyPlugin.clion.StateServices.ReadAllowStateService
import com.jfrog.MyPlugin.clion.StateServices.ReadThreadRunningStateService
import com.jfrog.MyPlugin.clion.Storage.ElfTable.tableModelElfPars
import com.jfrog.MyPlugin.clion.Storage.LiveExpressionTable
import com.jfrog.MyPlugin.clion.Storage.LiveExpressionTable.tableLiveExpression
import com.jfrog.MyPlugin.clion.Storage.LiveExpressionTable.tableModelLiveExpression
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel
import kotlin.concurrent.thread

class LiveExperssionPanel : JPanel(BorderLayout()) {
    private val app = ApplicationManager.getApplication()
    private val Application.services get() = Services(app)

    private class Services(val app: Application) {
        val readAllow: ReadAllowStateService get() = app.getService(ReadAllowStateService::class.java)
        val debugSession: DebugSessionStateService get() = app.getService(DebugSessionStateService::class.java)
        val readThread: ReadThreadRunningStateService get() = app.getService(ReadThreadRunningStateService::class.java)
    }

    private var readingThread: Thread? = null
    private val socketPort = 12345

    init {
        setupUI()
    }

    private fun setupUI() {
        val controlPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(createStartButton())
            add(createStopButton())
        }

        add(controlPanel, BorderLayout.NORTH)
        add(JBScrollPane(tableLiveExpression), BorderLayout.CENTER)
    }

    private fun createStartButton(): JButton = JButton("Start").apply {
        addActionListener {
            if (!app.services.debugSession.isDebugActive) {
                showDebugNotActiveWarning()
                return@addActionListener
            }

            startLiveExpressionSession()
        }
    }

    private fun createStopButton(): JButton = JButton("Stop").apply {
        addActionListener {
            stopLiveExpressionSession()
        }
    }

    private fun startLiveExpressionSession() {
        app.services.readAllow.readAllow = true

        if (app.services.readThread.readRunning) {
            SocketClient.resume()
            SocketClient.send("start")
            return
        }

        app.services.readThread.readRunning = true
        readingThread = thread(isDaemon = true, name = "LiveExpressionThread") {
            try {
                initializeSocketConnection()
                registerVariables()
                startDataListening()
            } catch (e: Exception) {
                handleSessionError(e)
            } finally {
                cleanupAfterSession()
            }
        }
    }

    private fun initializeSocketConnection() {
        println("initializeSocketConnection")
        if (!SocketClient.isConnected && !SocketClient.connect(socketPort)) {
            throw IllegalStateException("Failed to connect to socket server")
        }
    }

    private fun registerVariables() {
        println("registerVariables")
        val model = tableLiveExpression.model as DefaultTableModel
        for (i in 1 until model.rowCount) {
            val varName = model.getValueAt(i, 0) as? String ?: continue
            val isRegistered = model.getValueAt(i, 2) as? Boolean == true

            if (!isRegistered) {
                registerVariable(varName, i, model)
            }
        }
        SocketClient.send("start")
    }

    private fun registerVariable(varName: String, row: Int, model: DefaultTableModel) {
        findElfData(varName)?.let { elfData ->
            val address = elfData[0].removePrefix("0x").toInt(16)
            val message = SocketClient.createSocketMessage("put", varName, elfData[1].toInt(), address, elfData[2], 1000)
            SocketClient.send(message)
            model.setValueAt(true, row, 2)
        } ?: println("No ELF data found for $varName")
    }

    private fun findElfData(varName: String): List<String>? {
        for (j in 0 until tableModelElfPars.rowCount) {
            if (tableModelElfPars.getValueAt(j, 0) == varName) {
                return (1 until tableModelElfPars.columnCount).map {
                    tableModelElfPars.getValueAt(j, it).toString()
                }
            }
        }
        return null
    }

    private fun startDataListening() {
        while (app.services.readThread.readRunning) {
            try {
                if (SocketClient.isPaused()) {
                    Thread.sleep(100) // Короткая пауза при остановке
                    continue
                }

                SocketClient.readLine()?.takeIf { app.services.readAllow.readAllow }?.let {
                    processIncomingData(it)
                }
            } catch (e: Exception) {
                handleReadError(e)
            }
        }
    }

    private fun reconnect(): Boolean {
        Thread.sleep(1000)
        return SocketClient.connect(socketPort)
    }

    private fun processIncomingData(data: String) {
        val (name, value) = data.trim().split(";Data:").takeIf { it.size == 2 } ?: return

        LiveExpressionTable.updateValue(name, value)
    }

    private fun handleSessionError(e: Exception) {
        e.printStackTrace()
        println("Live expression session failed")
    }

    private fun handleReadError(e: Exception) {
        println("Read error: ${e.message}")
            //SocketClient.disconnect()
    }

    private fun cleanupAfterSession() {
        SwingUtilities.invokeLater {
            //LiveExpressionTable.resetTableModel()
        }
        app.services.readThread.readRunning = false
        println("Live expression session paused")
    }

    private fun stopLiveExpressionSession() {
        app.services.readAllow.readAllow = false
        SocketClient.pause()
        SocketClient.send("stop")
    }

    private fun showDebugNotActiveWarning() {
        JOptionPane.showMessageDialog(
            this,
            "Debug session not active",
            "Warning",
            JOptionPane.WARNING_MESSAGE
        )
    }
}