package dev.st33ng.mculivewatch.device

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * Device chooser modelled on SEGGER's "Target Device Settings".
 *
 * The user picks a manufacturer, optionally filters, and selects a device. The table is filled from
 * [JLinkDeviceDatabase], which is J-Link Commander's own device list, so whatever is chosen here is
 * exactly what `JLink.connect()` accepts — no CubeMX/SEGGER spelling translation, and no risk of
 * connecting to the wrong part.
 *
 * The list opens instantly from the copy bundled with the plugin; "Refresh from J-Link" re-exports
 * it from the installed SEGGER software. Nothing here blocks on a subprocess to *open* the dialog,
 * and every terminal state sets the status line: a failure says why and offers Retry instead of
 * leaving "Reading device list..." on screen forever.
 */
class DeviceSelectionDialog(
    project: Project,
    private val database: JLinkDeviceDatabase,
    private val initialDevice: String?,
) : DialogWrapper(project, true) {

    private val manufacturerBox = JComboBox<String>()
    private val filterField = JBTextField()
    private val tableModel = DeviceTableModel()
    private val table = JTable(tableModel)
    private val statusLabel = JBLabel(" ")
    private val retryButton = JButton("Retry").apply { isVisible = false }
    private val refreshButton = JButton("Refresh from J-Link").apply { isVisible = false }

    private var devices: List<JLinkDeviceDatabase.Device> = emptyList()

    var selectedDevice: String? = null
        private set

    init {
        title = "Select J-Link Device"
        isModal = true
        setSize(760, 520)

        retryButton.addActionListener { refreshFromJLink() }
        refreshButton.addActionListener { refreshFromJLink() }
        loadInitial()
        init()
    }

    /**
     * Fills the table straight from [JLinkDeviceDatabase]: the bundled or previously exported table
     * is already in memory, so this is a few tens of milliseconds and needs no thread and no
     * `invokeLater`. Doing it synchronously is deliberate — an empty dialog with a spinner was the
     * symptom of a background fill that never reached the screen.
     */
    private fun loadInitial() {
        val loaded = runCatching { database.devices() }
            .onFailure { LOG.warn("Cannot read the J-Link device list", it) }
            .getOrDefault(emptyList())
        LOG.info("Showing ${loaded.size} device(s) from ${database.source}")
        devices = loaded
        if (loaded.isEmpty()) {
            statusLabel.text = database.error
                ?: "The device list shipped with the plugin is missing. Reinstall the plugin."
            retryButton.isVisible = true
            return
        }
        populate(initialDevice)
    }

    /**
     * Re-exports the table from the installed J-Link software. This one does spawn a process, so it
     * runs off the EDT; on failure the already-shown list is kept.
     */
    private fun refreshFromJLink() {
        manufacturerBox.isEnabled = false
        filterField.isEnabled = false
        retryButton.isVisible = false
        refreshButton.isVisible = false
        statusLabel.text = "Reading the device list from SEGGER J-Link Commander..."

        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = database.refresh()
            val loaded = if (failure == null) {
                runCatching { database.devices() }.getOrDefault(devices)
            } else {
                devices
            }
            SwingUtilities.invokeLater { finishLoading(loaded, failure) }
        }
    }

    private fun finishLoading(loaded: List<JLinkDeviceDatabase.Device>, failure: String?) {
        LOG.info("Device list refresh finished: failure=$failure, devices=${loaded.size}")
        if (loaded.isNotEmpty()) {
            devices = loaded
            populate(initialDevice)
            if (failure != null) {
                // The list on screen is still usable; report the export problem alongside it.
                statusLabel.text = "${statusLabel.text} — $failure"
            }
            return
        }
        statusLabel.text = failure
            ?: database.error
            ?: "The device list shipped with the plugin is missing. Reinstall the plugin."
        retryButton.isVisible = true
        manufacturerBox.isEnabled = devices.isNotEmpty()
        filterField.isEnabled = devices.isNotEmpty()
        refreshButton.isVisible = devices.isNotEmpty()
    }

    private fun populate(preselect: String?) {
        val manufacturers = devices.map { it.manufacturer }.distinct()
            .sortedWith(compareBy({ it == JLinkDeviceDatabase.UNSPECIFIED }, { it.lowercase() }))

        manufacturerBox.removeAllItems()
        val preferred = devices.firstOrNull { it.name.equals(preselect, true) }?.manufacturer
            ?: manufacturers.firstOrNull { it != JLinkDeviceDatabase.UNSPECIFIED }
            ?: manufacturers.first()

        manufacturers.forEach { manufacturerBox.addItem(it) }
        manufacturerBox.isEnabled = true
        filterField.isEnabled = true
        retryButton.isVisible = false
        refreshButton.isVisible = true
        manufacturerBox.selectedItem = preferred
        refreshTable(preselect)
    }

    private fun refreshTable(preselect: String? = null) {
        val manufacturer = manufacturerBox.selectedItem as? String
        val needle = filterField.text.trim()
        val filtered = devices.asSequence()
            .filter { manufacturer == null || it.manufacturer == manufacturer }
            .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
            .sortedBy { it.name }
            .toList()

        tableModel.setDevices(filtered)
        val origin = when (database.source) {
            JLinkDeviceDatabase.Source.BUNDLED -> "bundled list"
            JLinkDeviceDatabase.Source.EXPORTED -> "read from your J-Link installation"
        }
        statusLabel.text = when {
            filtered.isEmpty() -> "No match for \"$needle\""
            else -> "${filtered.size} of ${devices.size} device(s), $origin"
        }

        if (preselect != null) {
            val index = filtered.indexOfFirst { it.name.equals(preselect, true) }
            if (index >= 0) {
                table.selectionModel.setSelectionInterval(index, index)
                table.scrollRectToVisible(table.getCellRect(index, 0, true))
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val top = JPanel(BorderLayout(8, 6)).apply {
            val manufacturerRow = JPanel(BorderLayout(8, 0)).apply {
                add(JBLabel("Manufacturer:").apply { preferredSize = Dimension(110, preferredSize.height) },
                    BorderLayout.WEST)
                add(manufacturerBox, BorderLayout.CENTER)
            }
            val filterRow = JPanel(BorderLayout(8, 0)).apply {
                add(JBLabel("Filter:").apply { preferredSize = Dimension(110, preferredSize.height) },
                    BorderLayout.WEST)
                add(filterField, BorderLayout.CENTER)
            }
            val rows = JPanel(java.awt.GridLayout(0, 1, 0, 4)).apply {
                add(manufacturerRow)
                add(filterRow)
            }
            add(rows, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
                add(statusLabel)
                add(retryButton)
                add(refreshButton)
            }, BorderLayout.SOUTH)
        }

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.setShowGrid(true)
        table.rowHeight = JBUI.scale(18)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(220)
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                selectedDevice = tableModel.deviceAt(table.selectedRow)?.name
            }
        }
        // Double-click confirms, matching the SEGGER dialog's behaviour.
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val row = table.rowAtPoint(e.point)
                    if (row >= 0) {
                        selectedDevice = tableModel.deviceAt(row)?.name
                        doOKAction()
                    }
                }
            }
        })

        manufacturerBox.addActionListener { refreshTable() }
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshTable()
            override fun removeUpdate(e: DocumentEvent) = refreshTable()
            override fun changedUpdate(e: DocumentEvent) = refreshTable()
        })

        return JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = filterField

    override fun doOKAction() {
        if (selectedDevice == null) {
            statusLabel.text = "Select a device first."
            return
        }
        super.doOKAction()
    }

    private class DeviceTableModel : AbstractTableModel() {
        private var rows: List<JLinkDeviceDatabase.Device> = emptyList()

        fun setDevices(devices: List<JLinkDeviceDatabase.Device>) {
            rows = devices
            fireTableDataChanged()
        }

        fun deviceAt(row: Int): JLinkDeviceDatabase.Device? = rows.getOrNull(row)

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Manufacturer" else "Device"
        override fun isCellEditable(row: Int, column: Int) = false

        override fun getValueAt(row: Int, column: Int): Any {
            val device = rows[row]
            return if (column == 0) device.manufacturer else device.name
        }
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(DeviceSelectionDialog::class.java)
    }
}
