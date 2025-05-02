package com.jfrog.MyPlugin.clion.toolWindow

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.jfrog.MyPlugin.clion.StateServices.DebugSessionStateService
import com.jfrog.MyPlugin.clion.StateServices.ReadAllowStateService
import com.jfrog.MyPlugin.clion.StateServices.ReadThreadRunningStateService
import com.jfrog.MyPlugin.clion.Storage.ElfTable.tableElfPars
import com.jfrog.MyPlugin.clion.Storage.ElfTable.tableModelElfPars
import com.jfrog.MyPlugin.clion.Storage.LiveExperssionTable.tableLiveExpression
import com.jfrog.MyPlugin.clion.Storage.LiveExperssionTable.tableModelLiveExpression
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

class LiveExperssionPanel : JPanel(BorderLayout()) {

    val Application.readAllowState: ReadAllowStateService
        get() = getService(ReadAllowStateService::class.java)

    val Application.debugSessionState: DebugSessionStateService
        get() = getService(DebugSessionStateService::class.java)

    val Application.readThreadState: ReadThreadRunningStateService
        get() = getService(ReadThreadRunningStateService::class.java)

    private var clientSocket: Socket? = null

    init {
        val liveExprControlPanel = JPanel()
        liveExprControlPanel.layout = BoxLayout(liveExprControlPanel, BoxLayout.X_AXIS)

        // Кнопка Start
        val startButton = JButton("Start")
        startButton.addActionListener {
            // Проверка: активна ли отладка и уже не запущено ли чтение
            if(ApplicationManager.getApplication().getService(ReadThreadRunningStateService::class.java).readRunning) {
                PrintWriter(clientSocket!!.getOutputStream(), true).println(createSocketMsg("start"))
                //TODO добавить переотправку после нажатия(если вдруг были добавлены новые)
            }
            ApplicationManager.getApplication().getService(ReadAllowStateService::class.java).readAllow = true
            if (ApplicationManager.getApplication().getService(DebugSessionStateService::class.java).isDebugActive) {
                if (!ApplicationManager.getApplication().getService(ReadThreadRunningStateService::class.java).readRunning) {
                    ApplicationManager.getApplication().getService(ReadThreadRunningStateService::class.java).readRunning = true  // Устанавливаем до запуска потока
                    Thread {
                        try {
                            clientSocket = Socket("localhost", 12345)
                            val out = PrintWriter(clientSocket!!.getOutputStream(), true)
                            val `in` = BufferedReader(InputStreamReader(clientSocket!!.getInputStream()))

                            val tableModelLiveExpression = tableLiveExpression.model as DefaultTableModel
                            val rowCount = tableModelLiveExpression.rowCount

                            for (i in 1 until rowCount) { // Пропускаем первую строку с "+"
                                val varName = tableModelLiveExpression.getValueAt(i, 0) as String
                                var elfData: List<String>? = null

                                for (j in 0 until tableModelElfPars.rowCount) {
                                    val elfVarName = tableModelElfPars.getValueAt(j, 0)
                                    if (elfVarName == varName) {
                                        elfData = (1 until tableModelElfPars.columnCount).map {
                                            tableModelElfPars.getValueAt(j, it).toString()
                                        }
                                        break
                                    }
                                }

                                if (elfData != null ) {
                                    if(tableModelLiveExpression.getValueAt(i, 2) as Boolean == false) {
                                        val address = elfData[0].removePrefix("0x").toInt(16)
                                        val message = createSocketMsg("put", varName, elfData[1].toInt(), address, elfData[2])
                                        out.println(message)

                                        tableModelLiveExpression.setValueAt(true, i, 2)
                                    }
                                } else {
                                    println("No data found for $varName")
                                }
                            }

                            out.println(createSocketMsg("start"))

                            // Чтение данных от сервера
                            var data: String?
                            while (`in`.readLine().also { data = it } != null) {
                                if(ApplicationManager.getApplication().getService(ReadAllowStateService::class.java).readAllow ) {
                                    val text = data!!.trim()
                                    val parts = text.split(";Data:")
                                    if (parts.size == 2) {
                                        val name = parts[0]
                                        val value = parts[1]

                                        for (row in 0 until tableModelLiveExpression.rowCount) {
                                            val rowName = tableModelLiveExpression.getValueAt(row, 0) as? String
                                            if (rowName == name) {
                                                tableModelLiveExpression.setValueAt(value, row, 1)
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } finally {
                            // TODO обнулять все (все в false) в tableModelLiveExpression
                            clientSocket!!.close()
                            ApplicationManager.getApplication().getService(ReadThreadRunningStateService::class.java).readRunning = false  // Поток завершился — можно снова запускать
                        }
                    }.start()
                } else {
                    println("Already running, skipping")
                }
            } else {
                println("Debug not started")
            }
        }

        // Кнопка Stop
        val stopButton = JButton("Stop")
        stopButton.addActionListener {
            ApplicationManager.getApplication().getService(ReadAllowStateService::class.java).readAllow  = false
            PrintWriter(clientSocket!!.getOutputStream(), true).println(createSocketMsg("stop"))
        }

        // Добавляем кнопки на панель
        liveExprControlPanel.add(startButton)
        liveExprControlPanel.add(stopButton)

        // Добавляем controlPanel в liveExprPanel

        this.add(liveExprControlPanel, BorderLayout.NORTH)
        setupLiveExprTable()
        val scrollPane = JBScrollPane(tableLiveExpression)
        this.add(scrollPane, BorderLayout.CENTER)
    }

    private fun setupLiveExprTable() {
        val columnNames = arrayOf("Expression", "Value", "On server")
        tableModelLiveExpression = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                // Разрешить редактирование всех строк, кроме "плюса"
                return row != rowCount - 1
            }
        }

        tableModelLiveExpression.addRow(arrayOf<Any>("+", "", false))

        tableLiveExpression = JBTable(tableModelLiveExpression)

        // Обработка клика на строку "+", добавление новой строки
        tableLiveExpression.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = tableLiveExpression.rowAtPoint(e.point)
                if (row == tableModelLiveExpression.rowCount - 1) {
                    // Получаем предполагаемое имя переменной (можно также сразу фокус туда поставить и подождать ввода)
                    val variableName = JOptionPane.showInputDialog(
                        tableLiveExpression,
                        "Введите имя переменной (должна быть в ELF таблице):",
                        "Добавление выражения",
                        JOptionPane.PLAIN_MESSAGE
                    ) ?: return

                    // Проверка: есть ли такое имя в elfParsTable
                    val found = (0 until tableElfPars.rowCount).any { i ->
                        val value = tableElfPars.getValueAt(i, 0)?.toString()?.trim()
                        value.equals(variableName.trim(), ignoreCase = true)
                    }

                    if (!found) {
                        JOptionPane.showMessageDialog(
                            tableLiveExpression,
                            "Переменная '$variableName' не найдена в ELF таблице.",
                            "Ошибка",
                            JOptionPane.ERROR_MESSAGE
                        )
                        return
                    }

                    // Добавляем строку с именем переменной, второй столбец — пустой
                    tableModelLiveExpression.insertRow(tableModelLiveExpression.rowCount - 1, arrayOf<Any>(variableName, "", false))
                }
            }
        })
        tableLiveExpression.fillsViewportHeight = true
    }
    private fun createSocketMsg(command: String, varName: String, varSize: Int, varAddress: Int, varType: String, updFrqMs: Int = 1000): String {
        val hexAddress = varAddress.toString(16)
        return "${command};${varName};${varSize};0x$hexAddress;${updFrqMs};${varType}"

    }

    private fun createSocketMsg(command: String): String {
        return command
    }
}