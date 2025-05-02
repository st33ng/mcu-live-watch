package com.jfrog.MyPlugin.clion.Storage

import javax.swing.JTable
import javax.swing.table.AbstractTableModel

object ElfTable {
    lateinit var tableElfPars: JTable
    lateinit var tableModelElfPars: SortableTableModel

    class SortableTableModel(private var data: List<Array<String>>) : AbstractTableModel() {
        private var sortColumn = -1
        private var ascending = true

        override fun getRowCount() = data.size
        override fun getColumnCount() = 4
        override fun getValueAt(row: Int, col: Int) = data[row][col]
        override fun getColumnName(col: Int) = when (col) {
            0 -> "OBJECT"
            1 -> "ADDRESS"
            2 -> "SIZE"
            3 -> "TYPE"
            else -> ""
        }

        fun sortByColumn(column: Int) {
            if (column == sortColumn) {
                ascending = !ascending
            } else {
                sortColumn = column
                ascending = true
            }

            data = when (column) {
                0 -> data.sortedBy { it[0] }
                1 -> data.sortedBy { it[1].removePrefix("0x").toLong(16) }
                2 -> data.sortedBy { it[2].substringBefore(" ").toInt() }
                else -> data
            }.let { if (ascending) it else it.reversed() }

            fireTableDataChanged()
        }
    }
}