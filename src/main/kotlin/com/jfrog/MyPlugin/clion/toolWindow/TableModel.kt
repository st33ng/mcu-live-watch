package com.jfrog.MyPlugin.clion.toolWindow

import javax.swing.table.AbstractTableModel

class TableModel(private val data: List<Array<String>>) : AbstractTableModel() {

    override fun getRowCount(): Int = data.size
    override fun getColumnCount(): Int = 3  // 3 колонки: OBJECT, ADDRESS, SIZE

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = data[rowIndex][columnIndex]

    override fun getColumnName(column: Int): String {
        return when (column) {
            0 -> "OBJECT"
            1 -> "ADDRESS"
            2 -> "SIZE"
            else -> ""
        }
    }
}
