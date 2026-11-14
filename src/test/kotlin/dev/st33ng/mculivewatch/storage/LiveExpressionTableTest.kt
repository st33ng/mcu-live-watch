package dev.st33ng.mculivewatch.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.SwingUtilities

/**
 * Guards the row layout of the live-expressions table.
 *
 * The "+" placeholder is added once at construction and then pushed downwards by every
 * `insertRow(rowCount - 1, ...)`, so real variables end up *above* it. The registration loop used
 * to skip row 0 by index, which meant the first real variable was never registered while "+" was
 * sent to the bridge as a variable name — the session then reported
 * `Variables not registered: +` and the Value column never updated.
 */
class LiveExpressionTableTest {

    /** The table mutates Swing state, so every operation must happen on the EDT. */
    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }

    @Test
    fun `first added variable is reported for registration`() {
        val table = LiveExpressionTable()
        onEdt { table.addVariable("counter", registered = false, frequencyHz = 1000) }

        val names = onEdtGet { table.registeredVariables().map { it.first } }

        assertEquals(listOf("counter"), names)
    }

    @Test
    fun `placeholder row is never reported as an expression`() {
        val table = LiveExpressionTable()

        assertTrue(onEdtGet { table.registeredVariables() }.isEmpty())

        onEdt {
            table.addVariable("a", registered = false, frequencyHz = 1000)
            table.addVariable("b", registered = false, frequencyHz = 500)
        }

        val reported = onEdtGet { table.registeredVariables() }
        assertFalse(reported.any { it.first == LiveExpressionTable.NEW_ROW_MARKER })
    }

    @Test
    fun `every added variable is reported with its own frequency`() {
        val table = LiveExpressionTable()
        onEdt {
            table.addVariable("a", registered = false, frequencyHz = 1000)
            table.addVariable("b", registered = false, frequencyHz = 250)
        }

        val reported = onEdtGet { table.registeredVariables() }

        assertEquals(listOf("a", "b"), reported.map { it.first })
        assertEquals(listOf(1000, 250), reported.map { it.second })
    }

    @Test
    fun `variable above the placeholder is found by name`() {
        val table = LiveExpressionTable()
        onEdt { table.addVariable("counter", registered = false, frequencyHz = 1000) }

        val row = onEdtGet { table.findRowByName("counter") }

        assertEquals(0, row)
    }

    @Test
    fun `placeholder row is not editable`() {
        val table = LiveExpressionTable()
        onEdt { table.addVariable("counter", registered = false, frequencyHz = 1000) }

        val markerRow = onEdtGet { table.findRowByName(LiveExpressionTable.NEW_ROW_MARKER) }

        assertEquals(1, markerRow)
        assertFalse(onEdtGet { table.tableModel.isCellEditable(markerRow!!, 0) })
        assertTrue(onEdtGet { table.tableModel.isCellEditable(0, 0) })
    }

    @Test
    fun `a burst of samples ends with the newest value visible`() {
        val table = LiveExpressionTable()
        onEdt { table.addVariable("counter", registered = false, frequencyHz = 1000) }

        // Reader-thread behaviour: 1000 samples in a burst must not queue 1000 EDT tasks,
        // but the last value must still win.
        repeat(1_000) { i -> table.updateValue("counter", i.toString()) }
        flushEdt()

        val shown = onEdtGet { table.tableModel.getValueAt(0, 1).toString() }

        assertEquals("999", shown)
    }

    private fun flushEdt() {
        if (!SwingUtilities.isEventDispatchThread()) SwingUtilities.invokeAndWait { }
    }

    private fun <T> onEdtGet(block: () -> T): T {
        var result: T? = null
        onEdt { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
