package dev.st33ng.mculivewatch.storage

import com.intellij.ui.table.JBTable
import dev.st33ng.mculivewatch.plot.SampleStore
import dev.st33ng.mculivewatch.ui.CppValue
import dev.st33ng.mculivewatch.ui.SocketClient
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

/**
 * Live expression table and its sample routing.
 *
 * Replaces the old global `valueHistory: MutableMap<String, MutableList<Pair<Long, CppValue>>>`,
 * which grew without any limit. Samples now go into fixed-size ring buffers in [SampleStore].
 *
 * The table itself is an instance (not a global singleton), so several projects no longer share
 * one JTable and one model.
 */
class LiveExpressionTable {

    val tableModel: DefaultTableModel = createTableModel()
    val table: JBTable = createTable()

    private fun createTableModel(): DefaultTableModel =
        object : DefaultTableModel(arrayOf("Expression", "Value", "On server", "Frq"), 0) {
            // The "+" row is a placeholder, never a real expression: it must not be editable.
            // Checked against this model's own data, because the outer `tableModel` field is not
            // assigned yet while this object is still being constructed.
            override fun isCellEditable(row: Int, column: Int): Boolean =
                getValueAt(row, 0)?.toString()?.trim() != NEW_ROW_MARKER
        }.apply {
            addRow(arrayOf<Any>(NEW_ROW_MARKER, "", false, ""))
        }

    /** True when [row] is the "+" placeholder row (it always sits last, but check by content). */
    private fun isNewRowMarker(row: Int): Boolean =
        row in 0 until tableModel.rowCount &&
            tableModel.getValueAt(row, 0)?.toString()?.trim() == NEW_ROW_MARKER

    private fun createTable(): JBTable = JBTable(tableModel).apply {
        fillsViewportHeight = true
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowMenu(e)

            private fun maybeShowMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val row = rowAtPoint(e.point)
                if (row in 0 until tableModel.rowCount) showContextMenu(e, row)
            }
        })
    }

    private fun showContextMenu(e: MouseEvent, row: Int) {
        val popupMenu = JPopupMenu()
        val variableName = tableModel.getValueAt(row, 0).toString()

        if (isNewRowMarker(row)) {
            popupMenu.add(JMenuItem("Add new variable").apply {
                addActionListener { addNewVariableRow() }
            })
        } else if (variableName.isNotBlank()) {
            popupMenu.add(JMenuItem("Add to Graph").apply {
                addActionListener {
                    dev.st33ng.mculivewatch.plot.PlotManager.addVariable(variableName)
                }
            })
            popupMenu.add(JMenuItem("Change variable frq").apply {
                addActionListener { changeFrequency(e.component, variableName) }
            })
        }

        popupMenu.show(e.component, e.x, e.y)
    }

    private fun changeFrequency(parent: java.awt.Component, variableName: String) {
        val input = JOptionPane.showInputDialog(
            parent,
            "Enter new frequency (in Hz):",
            "Change Frequency",
            JOptionPane.PLAIN_MESSAGE,
        )?.takeIf { it.isNotBlank() } ?: return

        val hz = input.toIntOrNull()
        if (hz == null || hz <= 0) {
            JOptionPane.showMessageDialog(
                parent,
                "Frequency must be a positive integer!",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }

        findRowByName(variableName)?.let { tableModel.setValueAt(hz, it, 3) }
        SocketClient.send(SocketClient.createSocketMessage("setVariableReadFrq", variableName, hz))
    }

    /**
     * Latest display text per variable, waiting to be painted.
     *
     * Samples arrive on the socket thread at up to 1 kHz per variable. Posting one `invokeLater`
     * per sample flooded the EDT with thousands of tasks per second; the queue never drained, so
     * clicks on Start/Stop sat behind it and the panel looked frozen ("Stop doesn't stop").
     * Instead, only the newest value per variable is kept and one flush is scheduled at a time.
     */
    private val pendingValues = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var flushScheduled = false

    /** Called from the socket reader thread; touches Swing only through the EDT. */
    fun updateValue(variable: String, newValue: String) {
        val parsed = CppValue.parse(newValue)
        val timestamp = System.currentTimeMillis()

        parsed.toPlotValue()?.let { value ->
            SampleStore.bufferFor(variable).add(timestamp, value)
        }

        pendingValues[variable] = parsed.displayText()
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        SwingUtilities.invokeLater { flushPendingValues() }
    }

    private fun flushPendingValues() {
        // Clear the guard before reading, so a sample that arrives during this flush schedules
        // another one instead of being lost.
        flushScheduled = false

        // Remove entry by entry (conditionally on the value we saw) instead of clear(): a sample
        // written between the snapshot and a clear() would be dropped *and* its producer already
        // saw the guard as free, so nobody would schedule another flush. remove(key, value) keeps
        // a newer sample in the map, where it is picked up by the flush below or the next one.
        val batch = ArrayList<Pair<String, String>>(pendingValues.size)
        for ((variable, text) in pendingValues) {
            if (pendingValues.remove(variable, text)) batch += variable to text
        }

        for ((variable, text) in batch) {
            findRowByName(variable)?.let { row -> tableModel.setValueAt(text, row, 1) }
        }

        // Rows can be added/removed concurrently; anything still queued needs a new flush.
        if (pendingValues.isNotEmpty()) scheduleFlush()
    }

    /** Drops queued values; called when a session stops so stale text cannot appear later. */
    fun discardPendingValues() {
        pendingValues.clear()
    }

    fun findRowByName(name: String): Int? = runOnEdtAndGet {
        (0 until tableModel.rowCount).firstOrNull { tableModel.getValueAt(it, 0) == name }
    }

    /**
     * Clears every Value and "On server" cell.
     *
     * Blocks until the EDT has applied it: the caller uses this to force re-registration, and an
     * asynchronous reset would let [registeredVariables] still report the old flags.
     */
    fun resetValues() {
        runOnEdtAndWait {
            for (row in 0 until tableModel.rowCount) {
                tableModel.setValueAt("", row, 1)
                tableModel.setValueAt(false, row, 2)
            }
        }
    }

    fun markRegistered(variableName: String, registered: Boolean) {
        runOnEdt {
            findRowByName(variableName)?.let { tableModel.setValueAt(registered, it, 2) }
        }
    }

    /**
     * Clears the "On server" flag for every row, leaving expressions and values intact.
     *
     * Used on Stop: the column describes the bridge's streaming state, so a stopped session must
     * not keep showing `true` for every variable.
     */
    fun markAllUnregistered() {
        runOnEdtAndWait {
            for (row in 0 until tableModel.rowCount) {
                tableModel.setValueAt(false, row, 2)
            }
        }
    }

    fun addVariable(variableName: String, registered: Boolean, frequencyHz: Int) {
        runOnEdt {
            // Chart the variable as soon as it is added to the table. Requiring a separate
            // "Add to Graph" made the Graph tab look empty and broken: the chart only ever drew
            // variables that had been added through a context menu nobody knew about.
            dev.st33ng.mculivewatch.plot.PlotManager.addVariable(variableName)

            if (findRowByName(variableName) != null) return@runOnEdt
            tableModel.insertRow(
                tableModel.rowCount - 1,
                arrayOf<Any>(variableName, "", registered, frequencyHz.toString()),
            )
        }
    }

    /**
     * Runs [block] on the EDT, or inline when the caller is already there.
     *
     * `invokeLater` alone would defer even on the EDT, making the table's state unobservable
     * immediately after a call (and untestable without pumping the event queue).
     */
    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }

    /** Like [runOnEdt], but the caller needs the effect to be visible on return. */
    private fun runOnEdtAndWait(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    /** Like [runOnEdtAndWait] for a value the caller needs synchronously. */
    private fun <T> runOnEdtAndGet(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: T? = null
        SwingUtilities.invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * Rows that carry a real expression, with their frequency and registration state.
     *
     * The "+" placeholder is filtered **by content**, not by index. It used to be skipped as
     * `(1 until rowCount)`, which was only correct when it stayed at row 0; it is created once and
     * then pushed down by every `insertRow(rowCount - 1, ...)`, so the first real variable ended up
     * at row 0 and was silently never registered while "+" was sent to the bridge instead.
     */
    fun registeredVariables(): List<Triple<String, Int, Boolean>> = runOnEdtAndGet {
        (0 until tableModel.rowCount).mapNotNull { row ->
            val name = tableModel.getValueAt(row, 0) as? String ?: return@mapNotNull null
            if (name.isBlank() || isNewRowMarker(row)) return@mapNotNull null
            val frequency = tableModel.getValueAt(row, 3).toString().toIntOrNull() ?: 1000
            val registered = tableModel.getValueAt(row, 2) as? Boolean == true
            Triple(name, frequency, registered)
        }
    }

    private fun addNewVariableRow() {
        val variableName = JOptionPane.showInputDialog(
            table,
            "Enter variable name (must exist in ELF table):",
            "Add Expression",
            JOptionPane.PLAIN_MESSAGE,
        )?.takeIf { it.isNotBlank() } ?: return

        if (!ElfTable.hasSymbol(variableName)) {
            JOptionPane.showMessageDialog(
                table,
                "Variable '$variableName' not found in ELF table",
                "Error",
                JOptionPane.ERROR_MESSAGE,
            )
            return
        }

        // Go through addVariable() so the column layout, the chart series and the dedup check all
        // stay in one place. Inserting the row directly here left the variable without an
        // XYSeries, which is what made the Graph tab stay empty for hand-typed expressions.
        addVariable(variableName, registered = false, frequencyHz = 1000)
    }

    companion object {
        /** Trailing placeholder row used as the "add an expression" affordance. */
        const val NEW_ROW_MARKER = "+"

        /** Kept for call sites that only need column metadata. */
        fun columns(): Array<String> = arrayOf("Expression", "Value", "On server", "Frq")

        fun emptyTable(): JTable = JTable(DefaultTableModel(columns(), 0))
    }
}
