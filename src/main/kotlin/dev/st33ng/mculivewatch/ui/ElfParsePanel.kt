package dev.st33ng.mculivewatch.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import dev.st33ng.mculivewatch.storage.ElfTable
import dev.st33ng.mculivewatch.settings.LiveExpressionSettings
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities

/**
 * ELF symbol browser.
 *
 * Changes from the old panel:
 *  - no absolute script/python paths: both come from [LiveExpressionSettings];
 *  - the project scan for `*.elf` runs on a pooled thread, never on the EDT;
 *  - the Python process is created once per refresh with a timeout, not once per dialog action;
 *  - symbols are typed ([ElfSymbol]) instead of `Array<String>`.
 */
class ElfParsePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val settings: LiveExpressionSettings = LiveExpressionSettings.getInstance(project)

    private val infoPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val tablePanel = JPanel(BorderLayout())
    private val statusLabel = JBLabel("No ELF file selected")

    private var tableVisible = true
    private var loading = false

    init {
        add(infoPanel, BorderLayout.NORTH)
        add(tablePanel, BorderLayout.CENTER)

        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JButton("Hide Table").apply {
                addActionListener {
                    tableVisible = !tableVisible
                    text = if (tableVisible) "Hide Table" else "Show Table"
                    tablePanel.isVisible = tableVisible
                    tablePanel.revalidate()
                    tablePanel.repaint()
                }
            })
            add(JButton("Settings").apply { addActionListener { editSettings() } })
            add(JButton("Reload").apply { addActionListener { loadSymbols(settings.state.elfFilePath) } })
        }
        add(controls, BorderLayout.SOUTH)

        refreshInfo()
        detectElfAsync()
    }

    /**
     * Finds a usable ELF file without blocking the EDT.
     *
     * The old code called `projectDir.walk()` inside the panel constructor, which froze the IDE
     * while a large build tree was traversed.
     */
    private fun detectElfAsync() {
        if (settings.state.elfFilePath.isNotBlank()) {
            loadSymbols(settings.state.elfFilePath)
            return
        }

        val basePath = project.basePath ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val candidate = runCatching {
                File(basePath).walkTopDown()
                    .onEnter { it.name != ".git" && it.name != "node_modules" }
                    .firstOrNull { it.isFile && it.extension.equals("elf", ignoreCase = true) }
            }.getOrNull()

            if (candidate != null) {
                ApplicationManager.getApplication().invokeLater {
                    settings.setElfFile(candidate.absolutePath)
                    refreshInfo()
                    loadSymbols(candidate.absolutePath)
                }
            }
        }
    }

    private fun editSettings() {
        val dialog = SettingsDialog(project, settings)
        if (dialog.showAndGet()) {
            settings.setElfFile(dialog.elfPath)
            settings.setBridgeScript(dialog.bridgeScript)
            settings.setPythonExecutable(dialog.pythonExecutable)
            settings.setElfParserScript(dialog.parserScript)
            settings.setDeviceName(dialog.deviceName)
            settings.setDemangle(dialog.demangle)
            refreshInfo()
            if (settings.state.elfFilePath.isNotBlank()) {
                loadSymbols(settings.state.elfFilePath)
            }
        }
    }

    private fun loadSymbols(elfPath: String) {
        if (loading) return

        if (elfPath.isBlank()) {
            showMessage("No ELF file selected")
            return
        }

        val file = File(elfPath)
        if (!file.isFile) {
            showMessage("ELF file not found: $elfPath")
            return
        }

        // Both resolve from the plugin's resources unless overridden in settings.
        val python = settings.pythonForParser()
        if (python == null) {
            showMessage(
                "No Python interpreter with pyelftools found. Install it " +
                    "(pip install pyelftools) or set the interpreter in Settings."
            )
            return
        }

        val parserScript = try {
            settings.parserScript()
        } catch (e: Exception) {
            showMessage("Cannot extract the bundled parser script: ${e.message}")
            return
        }

        loading = true
        showMessage("Loading...")

        ApplicationManager.getApplication().executeOnPooledThread {
            val args = buildList {
                add("-p")
                add(elfPath)
                if (settings.demangle) add("-d")
                add("-t")
                add("--json")
            }.toTypedArray()

            val result = runCatching {
                PythonDataFetcher(python, parserScript).fetchData(*args)
            }

            loading = false
            ApplicationManager.getApplication().invokeLater {
                result.onSuccess { symbols ->
                    ElfTable.update(symbols)
                    showTable()
                }.onFailure { error ->
                    thisLogger().warn("ELF parse failed", error)
                    showMessage("Error: ${error.message}")
                }
            }
        }
    }

    private fun showTable() {
        tablePanel.removeAll()

        val model = ElfTable.tableModelElfPars ?: run {
            showMessage("No data received")
            return
        }

        if (model.rowCount == 0) {
            showMessage("No symbols found in ELF file")
            return
        }

        val table = JBTable(model)
        ElfTable.tableElfPars = table

        // The status label used to keep the "Loading..." text after a successful load, which made a
        // finished parse look stuck. Replace it with the real outcome.
        statusLabel.text = "${model.rowCount} symbols from ${File(settings.state.elfFilePath).name}"

        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val column = table.columnAtPoint(e.point)
                if (column >= 0) model.sortByColumn(column)
            }
        })

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showContextMenu(table, e)
                }
            }
        })

        tablePanel.add(JBScrollPane(table), BorderLayout.CENTER)
        tablePanel.isVisible = tableVisible
        tablePanel.revalidate()
        tablePanel.repaint()
    }

    private fun showContextMenu(table: JBTable, e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        val symbol = ElfTable.tableModelElfPars?.symbolAt(row) ?: return

        val popupMenu = JPopupMenu()
        popupMenu.add(JMenuItem("Add to live expressions").apply {
            addActionListener { addToLiveExpressions(symbol) }
        })
        popupMenu.show(e.component, e.x, e.y)
    }

    private fun addToLiveExpressions(symbol: ElfSymbol) {
        val frequency = settings.defaultUpdateRateHz
        val table = dev.st33ng.mculivewatch.storage.LiveExpressionsService
            .getInstance(project)
            .table
        table.addVariable(symbol.name, registered = false, frequencyHz = frequency)
        // If a capture is already running, send it to the bridge right away; otherwise it would
        // only start streaming after the next Stop/Start cycle.
        dev.st33ng.mculivewatch.session.LiveSessionService
            .getInstance(project)
            .registerVariableIfRunning(symbol.name)
    }

    private fun refreshInfo() {
        infoPanel.removeAll()
        val elf = settings.state.elfFilePath.ifBlank { "not selected" }
        infoPanel.add(JBLabel("ELF: $elf"))
        infoPanel.add(JBLabel("Demangle: ${if (settings.demangle) "Enabled" else "Disabled"}"))
        infoPanel.add(statusLabel)
        infoPanel.revalidate()
        infoPanel.repaint()
    }

    private fun showMessage(message: String) {
        statusLabel.text = message
        tablePanel.removeAll()
        tablePanel.add(JLabel(message), BorderLayout.NORTH)
        tablePanel.revalidate()
        tablePanel.repaint()
    }

    private class SettingsDialog(
        private val project: Project,
        private val settings: LiveExpressionSettings,
    ) : DialogWrapper(project, true) {

        private val elfField = TextFieldWithBrowseButton(JBTextField(settings.state.elfFilePath).apply {
            emptyText.text = "detected automatically"
        }).apply {
            addBrowseFolderListener(
                TextBrowseFolderListener(
                    FileChooserDescriptorFactory.singleFile()
                        .withFileFilter { it.extension.equals("elf", true) },
                    project,
                )
            )
        }

        private val pythonField = TextFieldWithBrowseButton(JBTextField(settings.state.pythonExecutable).apply {
            emptyText.text = "detected automatically"
        }).apply {
            addBrowseFolderListener(
                TextBrowseFolderListener(FileChooserDescriptorFactory.singleFile(), project)
            )
        }

        private val parserField =
            TextFieldWithBrowseButton(JBTextField(settings.state.elfParserScriptPath).apply {
                emptyText.text = "bundled elf_symbols.py"
            }).apply {
                addBrowseFolderListener(
                    TextBrowseFolderListener(
                        FileChooserDescriptorFactory.singleFile()
                            .withFileFilter { it.extension.equals("py", true) },
                        project,
                    )
                )
            }

        private val bridgeField =
            TextFieldWithBrowseButton(JBTextField(settings.state.bridgeScriptPath).apply {
                emptyText.text = "bundled jlink_bridge.py"
            }).apply {
                addBrowseFolderListener(
                    TextBrowseFolderListener(
                        FileChooserDescriptorFactory.singleFile()
                            .withFileFilter { it.extension.equals("py", true) },
                        project,
                    )
                )
            }

        private val deviceField = JBTextField(settings.state.deviceName).apply {
            emptyText.text = "detected from project"
        }

        private val deviceStatus = JBLabel(" ").apply {
            foreground = com.intellij.ui.JBColor.GRAY
        }

        private val demangleBox = JBCheckBox("Demangle C++ names (slow for large binaries)", settings.demangle)

        val elfPath: String get() = elfField.text.trim()
        val pythonExecutable: String get() = pythonField.text.trim()
        val parserScript: String get() = parserField.text.trim()
        val bridgeScript: String get() = bridgeField.text.trim()
        val deviceName: String get() = deviceField.text.trim()
        val demangle: Boolean get() = demangleBox.isSelected

        init {
            title = "MCU Live Watch Settings"
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout(0, 12))

            val hint = JBLabel(
                "<html><body style='width:420px'>" +
                    "Empty fields fall back to the bundled scripts and to an automatically " +
                    "detected Python interpreter — no configuration is required. " +
                    "Fill a field only to override the default.</body></html>"
            ).apply { foreground = com.intellij.ui.JBColor.GRAY }

            val deviceRow = JPanel(BorderLayout(8, 0)).apply {
                val selectButton = JButton("Select...").apply {
                    toolTipText = "Choose a device from the SEGGER J-Link device list"
                    addActionListener { selectDevice() }
                }
                val testButton = JButton("Test J-Link").apply {
                    toolTipText = "Connect once through the SEGGER J-Link Commander CLI"
                    addActionListener { testWithSegger() }
                }
                val buttons = JPanel(BorderLayout(4, 0)).apply {
                    add(selectButton, BorderLayout.WEST)
                    add(testButton, BorderLayout.EAST)
                }
                add(deviceField, BorderLayout.CENTER)
                add(buttons, BorderLayout.EAST)
            }

            val deviceBlock = JPanel(BorderLayout(0, 4)).apply {
                add(deviceRow, BorderLayout.NORTH)
                add(deviceStatus, BorderLayout.SOUTH)
            }

            val grid = JPanel(GridLayout(0, 1, 8, 6)).apply {
                add(labelled("ELF file:", elfField))
                add(labelled("J-Link device:", deviceBlock))
                add(labelled("Python:", pythonField))
                add(labelled("ELF parser script:", parserField))
                add(labelled("J-Link bridge script:", bridgeField))
                add(demangleBox)
            }


            // Reflect the current value right away; the picker fills it in on demand.
            deviceStatus.text = when {
                settings.state.deviceName.isNotBlank() -> "device: ${settings.state.deviceName}"
                else -> "device: not selected — press Select..."
            }

            panel.add(hint, BorderLayout.NORTH)
            panel.add(grid, BorderLayout.CENTER)
            return panel
        }

        /** Opens the SEGGER-style device picker and stores the chosen name. */
        private fun selectDevice() {
            val database = dev.st33ng.mculivewatch.device.JLinkDeviceDatabase.getInstance(project)
            val dialog = dev.st33ng.mculivewatch.device.DeviceSelectionDialog(
                project,
                database,
                deviceField.text.trim().ifBlank { null },
            )
            if (dialog.showAndGet()) {
                val chosen = dialog.selectedDevice ?: return
                deviceField.text = chosen
                deviceStatus.text = "device: $chosen (from the J-Link device list)"
            }
        }

        /**
         * Connects once through SEGGER's own CLI, so a mismatch between the plugin and the official
         * toolchain shows up here rather than as a silent failure while plotting.
         */
        private fun testWithSegger() {
            if (dev.st33ng.mculivewatch.device.JLinkCommander.findExecutable() == null) {
                Messages.showWarningDialog(
                    "SEGGER J-Link software was not found in /Applications/SEGGER.",
                    "J-Link not installed",
                )
                return
            }

            val device = deviceField.text.trim().ifBlank { "(empty)" }
            deviceStatus.text = "testing with SEGGER J-Link Commander ($device)..."

            ApplicationManager.getApplication().executeOnPooledThread {
                val result = dev.st33ng.mculivewatch.device.JLinkCommander
                    .testConnection(device, settings.jlinkSpeedKhz.takeIf { it > 0 } ?: 4000)

                ApplicationManager.getApplication().invokeLater {
                    if (!deviceStatus.isShowing) return@invokeLater
                    if (result == null) {
                        deviceStatus.text = "device: SEGGER J-Link Commander not found"
                        return@invokeLater
                    }
                    val verdict = when {
                        result.connected -> "connected"
                        result.failed -> "SEGGER reports a failure — see log"
                        else -> "exit ${result.exitCode}"
                    }
                    deviceStatus.text = "SEGGER J-Link Commander: $verdict"
                    thisLogger().info("J-Link Commander output:\n${result.output}")

                    if (!result.connected) {
                        Messages.showInfoMessage(
                            result.output.takeLast(1500),
                            "SEGGER J-Link Commander",
                        )
                    }
                }
            }
        }

        private fun labelled(label: String, field: JComponent): JPanel =
            JPanel(BorderLayout(8, 0)).apply {
                add(JBLabel(label).apply {
                    preferredSize = java.awt.Dimension(140, preferredSize.height)
                }, BorderLayout.WEST)
                add(field, BorderLayout.CENTER)
            }
    }
}
