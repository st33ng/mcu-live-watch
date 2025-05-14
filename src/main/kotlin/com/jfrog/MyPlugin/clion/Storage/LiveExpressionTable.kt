package com.jfrog.MyPlugin.clion.Storage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.table.JBTable
import com.jfrog.MyPlugin.clion.StateServices.ReadThreadRunningStateService
import com.jfrog.MyPlugin.clion.Storage.ElfTable.tableElfPars
import com.jfrog.MyPlugin.clion.Storage.ElfTable.tableModelElfPars
import com.jfrog.MyPlugin.clion.plot.PlotManager
import com.jfrog.MyPlugin.clion.toolWindow.CppValue
import com.jfrog.MyPlugin.clion.toolWindow.SocketClient
import com.sun.java.accessibility.util.AWTEventMonitor.addMouseListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

object LiveExpressionTable {
    private val _tableModelLiveExpression = createTableModel()
    private val _tableLiveExpression = createTable()
    val valueHistory: MutableMap<String, MutableList<Pair<Long, CppValue>>> = mutableMapOf()

    val tableModelLiveExpression: DefaultTableModel
        get() = _tableModelLiveExpression

    val tableLiveExpression: JBTable
        get() = _tableLiveExpression

    private fun createTableModel(): DefaultTableModel {
        return object : DefaultTableModel(arrayOf("Expression", "Value", "On server", "Frq"), 0) {
            override fun isCellEditable(row: Int, column: Int) = row != rowCount - 1
        }.apply {
            addRow(arrayOf<Any>("+", "", false, ""))
        }
    }

    private fun createTable(): JBTable {
        return JBTable(tableModelLiveExpression).apply {
            fillsViewportHeight = true
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) { // Проверяем правый клик
                        val row = rowAtPoint(e.point)
                        if (row >= 0 && row < tableModelLiveExpression.rowCount) {
                            showContextMenu(e, row) // Открываем меню для выбранной строки
                        }
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) { // Поддержка разных платформ
                        val row = rowAtPoint(e.point)
                        if (row >= 0 && row < tableModelLiveExpression.rowCount) {
                            showContextMenu(e, row) // Открываем меню для выбранной строки
                        }
                    }
                }
            })
        }
    }

    private fun showContextMenu(e: MouseEvent, row: Int) {
        val popupMenu = JPopupMenu()
        val variableName = tableModelLiveExpression.getValueAt(row, 0).toString()

        // Для строки с "+" (первая строка) — только "Add new variable"
        if (row == 0 && variableName == "+") {
            val actionAddVar = JMenuItem("Add new variable")
            actionAddVar.addActionListener {
                addNewVariableRow()
            }
            popupMenu.add(actionAddVar)
        } else if (variableName.isNotBlank()) {
            val actionAddGraph = JMenuItem("Add to Graph")
            actionAddGraph.addActionListener {
                SwingUtilities.invokeLater {
                    PlotManager.addVariable(variableName)
                    println("Added to graph: $variableName")
                }
            }
            val actionChangeVariableFrq = JMenuItem("Change variable frq")
            actionChangeVariableFrq.addActionListener {
                SwingUtilities.invokeLater {
                    val input = JOptionPane.showInputDialog(
                        e.component,
                        "Enter new frequency (in hz):",
                        "Change Frequency",
                        JOptionPane.PLAIN_MESSAGE
                    )

                    if (input != null && input.isNotBlank()) {
                        try {
                            val newTimeoutUS = input.toInt()
                            if (newTimeoutUS <= 0) {
                                JOptionPane.showMessageDialog(
                                    e.component,
                                    "Frequency timeout must be positive!",
                                    "Error",
                                    JOptionPane.ERROR_MESSAGE
                                )
                                return@invokeLater
                            }

                            SwingUtilities.invokeLater {
                                findTableRowByName(variableName)?.let { row ->
                                    tableModelLiveExpression.setValueAt(newTimeoutUS, row, 3)
                                }
                            }
                            SocketClient.send(SocketClient.createSocketMessage("setVariableReadFrq", variableName, newTimeoutUS))
                            println("Changed frequency for $variableName to $newTimeoutUS us")
                        } catch (ex: NumberFormatException) {
                            JOptionPane.showMessageDialog(
                                e.component,
                                "Invalid input! Please enter a valid number.",
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                            )
                        }
                    }
                }
            }
            popupMenu.add(actionChangeVariableFrq)
            popupMenu.add(actionAddGraph)
        }

        popupMenu.show(e.component, e.x, e.y)
    }

    fun updateValue(variable: String, newValue: String) {
        SwingUtilities.invokeLater {
            findTableRowByName(variable)?.let { row ->
                tableModelLiveExpression.setValueAt(newValue, row, 1)
            }
        }

        val now = System.currentTimeMillis()
        val parsedValue = CppValue.parse(newValue)
        valueHistory.getOrPut(variable) { mutableListOf() }.add(now to parsedValue)
    }

    private fun findTableRowByName(name: String): Int? {
        for (row in 0 until tableModelLiveExpression.rowCount) {
            if (tableModelLiveExpression.getValueAt(row, 0) == name) {
                return row
            }
        }
        return null
    }

    internal fun resetTableModel() {
        for (row in 0 until tableModelLiveExpression.rowCount) {
            tableModelLiveExpression.setValueAt("", row, 1)
            tableModelLiveExpression.setValueAt(false, row, 2)
        }
    }

    //todo отправка добавленного на сервер
    private fun addNewVariableRow() {
        val variableName = JOptionPane.showInputDialog(
            tableLiveExpression,
            "Enter variable name (must exist in ELF table):",
            "Add Expression",
            JOptionPane.PLAIN_MESSAGE
        )?.takeIf { it.isNotBlank() } ?: return

        if (!isVariableInElfTable(variableName)) {
            showVariableNotFoundWarning(variableName)
            return
        }

        tableModelLiveExpression.insertRow(
            tableModelLiveExpression.rowCount - 1,
            arrayOf<Any>(variableName, "", false, "1000")
        )
    }

    private fun isVariableInElfTable(name: String): Boolean {
        return (0 until tableModelElfPars.rowCount).any { i ->
            tableModelElfPars.getValueAt(i, 0).toString().trim().equals(name.trim(), ignoreCase = true)
        }
    }

    private fun showVariableNotFoundWarning(name: String) {
        JOptionPane.showMessageDialog(
            tableLiveExpression,
            "Variable '$name' not found in ELF table",
            "Error",
            JOptionPane.ERROR_MESSAGE
        )
    }
}