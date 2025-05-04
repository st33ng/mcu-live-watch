package com.jfrog.MyPlugin.clion.Storage

import com.intellij.ui.table.JBTable
import com.jfrog.MyPlugin.clion.Storage.ElfTable.tableModelElfPars
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JOptionPane
import javax.swing.table.DefaultTableModel

object LiveExpressionTable {
    private val _tableModelLiveExpression = createTableModel()
    private val _tableLiveExpression = createTable()

    val tableModelLiveExpression: DefaultTableModel
        get() = _tableModelLiveExpression

    val tableLiveExpression: JBTable
        get() = _tableLiveExpression

    private fun createTableModel(): DefaultTableModel {
        return object : DefaultTableModel(arrayOf("Expression", "Value", "On server"), 0) {
            override fun isCellEditable(row: Int, column: Int) = row != rowCount - 1
        }.apply {
            addRow(arrayOf<Any>("+", "", false))
        }
    }

    private fun createTable(): JBTable {
        return JBTable(tableModelLiveExpression).apply {
            fillsViewportHeight = true
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (rowAtPoint(e.point) == tableModelLiveExpression.rowCount - 1) {
                        addNewVariableRow()
                    }
                }
            })
        }
    }

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
            arrayOf<Any>(variableName, "", false)
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