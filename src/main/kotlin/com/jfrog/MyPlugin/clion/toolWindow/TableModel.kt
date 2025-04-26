package com.jfrog.MyPlugin.clion.toolWindow

import javax.swing.table.AbstractTableModel

class TableModel(data: List<Array<String>>) : AbstractTableModel() {

    private val originalData = data.toMutableList() // Исходные данные
    private val currentData = originalData.toMutableList() // Текущие отображаемые данные

    override fun getRowCount(): Int = currentData.size
    override fun getColumnCount(): Int = 3 // OBJECT, ADDRESS, SIZE

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = currentData[rowIndex][columnIndex]

    override fun getColumnName(column: Int): String {
        return when (column) {
            0 -> "OBJECT"
            1 -> "ADDRESS"
            2 -> "SIZE"
            else -> ""
        }
    }

    // Для корректной сортировки числовых значений в столбцах ADDRESS и SIZE
    override fun getColumnClass(columnIndex: Int): Class<*> {
        return when (columnIndex) {
            0 -> String::class.java // OBJECT - строка
            1 -> String::class.java // ADDRESS - строка (можно сделать Long, если это всегда hex)
            2 -> String::class.java // SIZE - строка (можно сделать Int, если это всегда число)
            else -> Any::class.java
        }
    }

    // Обновление данных после сортировки
    fun setData(newData: List<Array<String>>) {
        currentData.clear()
        currentData.addAll(newData)
        fireTableDataChanged() // Уведомляем таблицу об изменении данных
    }

    // Возврат к исходному порядку
    fun resetToOriginal() {
        setData(originalData)
    }
}