package dev.st33ng.mculivewatch.storage

import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/**
 * ELF symbol table.
 *
 * Now holds typed rows instead of `Array<String>`, which removes the fragile parsing of the
 * "123 байт" size column and the `toInt(16)` on addresses that could overflow 32 bits.
 */
object ElfTable {
    @Volatile
    var tableElfPars: JTable? = null

    @Volatile
    var tableModelElfPars: SortableTableModel? = null

    private val symbolsByName = HashMap<String, dev.st33ng.mculivewatch.ui.ElfSymbol>()

    val symbols: Collection<dev.st33ng.mculivewatch.ui.ElfSymbol>
        get() = symbolsByName.values

    fun hasSymbol(name: String): Boolean = symbolsByName.containsKey(name)

    fun symbol(name: String): dev.st33ng.mculivewatch.ui.ElfSymbol? = symbolsByName[name]

    fun update(symbols: List<dev.st33ng.mculivewatch.ui.ElfSymbol>) {
        synchronized(symbolsByName) {
            symbolsByName.clear()
            for (symbol in symbols) symbolsByName[symbol.name] = symbol
        }
        tableModelElfPars = SortableTableModel(symbols)
    }

    class SortableTableModel(private var data: List<dev.st33ng.mculivewatch.ui.ElfSymbol>) :
        AbstractTableModel() {

        private var sortColumn = -1
        private var ascending = true

        override fun getRowCount() = data.size
        override fun getColumnCount() = 4

        override fun getValueAt(row: Int, col: Int): Any {
            val symbol = data[row]
            return when (col) {
                0 -> symbol.name
                1 -> symbol.addressHex
                2 -> symbol.size.toString()
                3 -> symbol.type
                else -> ""
            }
        }

        override fun getColumnName(col: Int) = when (col) {
            0 -> "OBJECT"
            1 -> "ADDRESS"
            2 -> "SIZE"
            3 -> "TYPE"
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

        fun sortByColumn(column: Int) {
            ascending = if (column == sortColumn) !ascending else true
            sortColumn = column

            data = when (column) {
                0 -> data.sortedBy { it.name }
                1 -> data.sortedBy { it.address }
                2 -> data.sortedBy { it.size }
                3 -> data.sortedBy { it.type }
                else -> data
            }.let { if (ascending) it else it.reversed() }

            fireTableDataChanged()
        }

        fun symbolAt(row: Int): dev.st33ng.mculivewatch.ui.ElfSymbol? =
            data.getOrNull(row)
    }
}
