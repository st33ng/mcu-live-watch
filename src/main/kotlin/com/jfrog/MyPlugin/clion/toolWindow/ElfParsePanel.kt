package com.jfrog.MyPlugin.clion.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.table.*

import com.intellij.openapi.project.Project
import com.jfrog.MyPlugin.clion.StateServices.ReadThreadRunningStateService
import com.jfrog.MyPlugin.clion.Storage.ElfTable
import com.jfrog.MyPlugin.clion.Storage.ElfTable.tableModelElfPars
import com.jfrog.MyPlugin.clion.Storage.ElfTable.tableElfPars
import com.jfrog.MyPlugin.clion.Storage.LiveExpressionTable

class ElfParsePanel(project: Project) : JPanel(BorderLayout()) {
    private var tableVisible = true

    private val pythonExecutable = "/Users/timtim/.local/pipx/venvs/pyelftools/bin/python3"
    private val pythonScriptPath = "/Users/timtim/PycharmProjects/pythonProject1/main.py"

    private var currentElfPath: String? = null
    private var demangleEnabled = false

    private var currentData: List<Array<String>> = emptyList()
    private var lastPythonArgs: Array<String>? = null

    init {
        val tablePanel = JPanel(BorderLayout())
        this.add(tablePanel, BorderLayout.CENTER)

        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        this.add(infoPanel, BorderLayout.NORTH)

        val controlPanel = JPanel()
        controlPanel.layout = BoxLayout(controlPanel, BoxLayout.X_AXIS)

        val toggleTableButton = JButton("Hide Table")
        toggleTableButton.addActionListener {
            tableVisible = !tableVisible
            toggleTableButton.text = if (tableVisible) "Hide Table" else "Show Table"
            tablePanel.isVisible = tableVisible
            tablePanel.revalidate()
            tablePanel.repaint()
        }
        controlPanel.add(toggleTableButton)

        val settingsButton = JButton("Settings")
        settingsButton.addActionListener {
            val settingsDialog = SettingsDialog(currentElfPath, demangleEnabled)
            if (settingsDialog.showAndGet()) {
                currentElfPath = settingsDialog.selectedFile?.absolutePath
                demangleEnabled = settingsDialog.demangleEnabled

                updateInfoPanel(infoPanel)
                if (currentElfPath != null) {
                    updateTable(tablePanel, buildPythonArgs(), forceRefresh = true)
                }
            }
        }
        controlPanel.add(settingsButton)

        this.add(controlPanel, BorderLayout.SOUTH)

        val projectDir = project.basePath?.let { File(it) } ?: run {
            File(System.getProperty("user.home"))
        }

        val elfFiles = projectDir.walk().filter { it.isFile && it.extension == "elf" }.toList()
        currentElfPath = if (elfFiles.isNotEmpty()) elfFiles.first().absolutePath else null

        updateInfoPanel(infoPanel)
        updateTable(tablePanel, buildPythonArgs())
    }

    private fun updateTable(tablePanel: JPanel, pythonArgs: Array<String>?, forceRefresh: Boolean = false) {
        tablePanel.removeAll()
        println("Python args in updateTable(): ${pythonArgs?.joinToString(" ") ?: "null"}")

        if (pythonArgs == null) {
            showMessage(tablePanel, "No ELF file selected")
            return
        }

        val elfPath = pythonArgs[1]
        if (!File(elfPath).exists()) {
            showMessage(tablePanel, "ELF file not found: $elfPath")
            return
        }

        if (!forceRefresh && currentData.isNotEmpty() && pythonArgs.contentEquals(lastPythonArgs)) {
            createAndShowTable(tablePanel, currentData)
            return
        }

        showMessage(tablePanel, "Loading...")
        lastPythonArgs = pythonArgs

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val newData = PythonDataFetcher(pythonExecutable, pythonScriptPath).fetchData(*pythonArgs)
                currentData = newData
                ApplicationManager.getApplication().invokeLater {
                    createAndShowTable(tablePanel, newData)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    showMessage(tablePanel, "Error: ${e.message}")
                }
            }
        }
    }

    private fun createAndShowTable(tablePanel: JPanel, data: List<Array<String>>) {
        tablePanel.removeAll()

        if (data.isEmpty()) {
            showMessage(tablePanel, "No data received")
            return
        }

        tableModelElfPars = ElfTable.SortableTableModel(data)
        tableElfPars = JBTable(tableModelElfPars).apply {
            setDefaultRenderer(Any::class.java, CenterCellRenderer())
        }

        tableElfPars.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val column = tableElfPars.columnAtPoint(e.point)
                tableModelElfPars.sortByColumn(column)
            }
        })

        val scrollPane = JBScrollPane(tableElfPars)
        tablePanel.add(scrollPane, BorderLayout.CENTER)
        tablePanel.isVisible = tableVisible
        tablePanel.revalidate()
        tablePanel.repaint()

        tableElfPars.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e))  {
                    showContextMenu(e, tableElfPars.selectedRow)
                }
            }
        })

    }

    private fun updateInfoPanel(panel: JPanel) {
        panel.removeAll()
        panel.add(JBLabel("Current ELF: ${currentElfPath ?: "Not selected"}"))
        panel.add(JBLabel("Demangle: ${if (demangleEnabled) "Enabled" else "Disabled"}"))
        panel.revalidate()
        panel.repaint()
    }

    private fun buildPythonArgs(): Array<String>? {
        if (currentElfPath == null) return null

        val args = mutableListOf("-p", currentElfPath!!) // path
        if (demangleEnabled) args.add("-d") // demangle
        args.add("-t") // types
        return args.toTypedArray()
    }

    private fun showMessage(panel: JPanel, message: String) {
        panel.removeAll()
        panel.add(JLabel(message), BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    internal class CenterCellRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = CENTER }
    }

    // Метод для выпадающего списка после пкм
    private fun showContextMenu(e: MouseEvent, row: Int) {
        val popupMenu = JPopupMenu()

        val actionItem = JMenuItem("Add to live expession")
        actionItem.addActionListener {
            val objectName = tableElfPars.getValueAt(row, 0).toString()
            var isSendToServer = false
            var baseFrq = 1000

            if(ApplicationManager.getApplication().getService(ReadThreadRunningStateService::class.java).readRunning){
                var elfData: List<String>? = null

                elfData = (1 until tableModelElfPars.columnCount).map {
                    tableModelElfPars.getValueAt(row, it).toString()
                }

                val address = elfData[0].removePrefix("0x").toInt(16)
                SocketClient.send(SocketClient.createSocketMessage("put", objectName, elfData[1].toInt(), address, elfData[2], baseFrq))

                isSendToServer = true
            }
            LiveExpressionTable.tableModelLiveExpression.addRow(arrayOf<Any>(objectName, "", isSendToServer, baseFrq.toString()))
        }
        popupMenu.add(actionItem)
        popupMenu.show(e.component, e.x, e.y)
    }

    private class SettingsDialog(
        initialPath: String?,
        initialDemangle: Boolean
    ) : DialogWrapper(true) {
        private val fileChooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = object : javax.swing.filechooser.FileFilter() {
                override fun accept(f: File) = f.isDirectory || f.extension.equals("elf", true)
                override fun getDescription() = "ELF Files (*.elf)"
            }
        }

        private val pathField = JBTextField(initialPath ?: "")
        private val demangleCheckBox = JBCheckBox("Demangle names", initialDemangle)

        val selectedFile: File? get() = pathField.text.takeIf { it.isNotEmpty() }?.let { File(it) }
        val demangleEnabled: Boolean get() = demangleCheckBox.isSelected

        init {
            title = "ELF Viewer Settings"
            init()
        }

        override fun createCenterPanel(): JComponent {
            return JPanel(GridLayout(0, 1, 5, 5)).apply {
                add(JPanel(BorderLayout(5, 5)).apply {
                    add(JLabel("ELF File Path:"), BorderLayout.WEST)
                    add(JPanel(BorderLayout(5, 5)).apply {
                        add(pathField, BorderLayout.CENTER)
                        add(JButton("Browse...").apply {
                            addActionListener {
                                if (fileChooser.showOpenDialog(this@apply) == JFileChooser.APPROVE_OPTION) {
                                    pathField.text = fileChooser.selectedFile.absolutePath
                                }
                            }
                        }, BorderLayout.EAST)
                    }, BorderLayout.CENTER)
                })
                add(demangleCheckBox)
            }
        }

        override fun createActions() = arrayOf(OKAction(), CancelAction())

        private inner class OKAction : AbstractAction("OK") {
            override fun actionPerformed(e: ActionEvent) {
                if (pathField.text.isBlank()) {
                    JOptionPane.showMessageDialog(contentPane, "Please select an ELF file", "Error", JOptionPane.ERROR_MESSAGE)
                    return
                }
                doOKAction()
            }
        }

        private inner class CancelAction : AbstractAction("Cancel") {
            override fun actionPerformed(e: ActionEvent) = doCancelAction()
        }
    }
}