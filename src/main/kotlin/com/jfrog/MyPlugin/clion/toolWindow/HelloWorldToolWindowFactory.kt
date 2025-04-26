package com.jfrog.MyPlugin.clion.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.io.File
import javax.swing.*
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import com.intellij.openapi.application.ApplicationManager
import javax.swing.table.*
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBLabel
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent

class HelloWorldToolWindowFactory : ToolWindowFactory {

    private val pythonExecutable = "/Users/timtim/.local/pipx/venvs/pyelftools/bin/python3"
    private val pythonScriptPath = "/Users/timtim/PycharmProjects/pythonProject1/main.py"

    // Настройки по умолчанию
    private var currentElfPath: String? = null
    private var demangleEnabled = false
    private var showHexAddresses = false
    private var showSourceFiles = false

    // Состояние сортировки и таблицы
    private var currentData: List<Array<String>> = emptyList()
    private var lastPythonArgs: Array<String>? = null
    private var tableVisible = true
    private lateinit var table: JTable
    private lateinit var tableModel: SortableTableModel

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel()
        panel.layout = BorderLayout()

        val tablePanel = JPanel()
        tablePanel.layout = BorderLayout()
        panel.add(tablePanel, BorderLayout.CENTER)

        // Панель для информации о текущих настройках
        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        panel.add(infoPanel, BorderLayout.NORTH)

        // Панель управления (кнопки)
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
            val settingsDialog = SettingsDialog(
                currentElfPath,
                demangleEnabled,
                showHexAddresses,
                showSourceFiles
            )
            if (settingsDialog.showAndGet()) {
                currentElfPath = settingsDialog.selectedFile?.absolutePath
                demangleEnabled = settingsDialog.demangleEnabled
                showHexAddresses = settingsDialog.showHexAddresses
                showSourceFiles = settingsDialog.showSourceFiles

                updateInfoPanel(infoPanel)
                if (currentElfPath != null) {
                    updateTable(tablePanel, buildPythonArgs(), forceRefresh = true)
                }
            }
        }
        controlPanel.add(settingsButton)

        panel.add(controlPanel, BorderLayout.SOUTH)

        // Автоматический поиск файла .elf
        val projectDir = File(project.basePath ?: return)
        val elfFiles = projectDir.walk().filter { it.isFile && it.extension == "elf" }.toList()
        currentElfPath = if (elfFiles.isNotEmpty()) elfFiles.first().absolutePath else null

        updateInfoPanel(infoPanel)
        updateTable(tablePanel, buildPythonArgs())

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun updateInfoPanel(panel: JPanel) {
        panel.removeAll()
        panel.add(JBLabel("Current ELF: ${currentElfPath ?: "Not selected"}"))
        panel.add(JBLabel("Demangle: ${if (demangleEnabled) "Enabled" else "Disabled"}"))
        panel.add(JBLabel("Hex addresses: ${if (showHexAddresses) "Shown" else "Hidden"}"))
        panel.add(JBLabel("Source files: ${if (showSourceFiles) "Shown" else "Hidden"}"))
        panel.revalidate()
        panel.repaint()
    }

    private fun buildPythonArgs(): Array<String>? {
        if (currentElfPath == null) return null

        val args = mutableListOf("-p", currentElfPath!!)
        if (demangleEnabled) args.addAll(listOf("-d"))
        if (showHexAddresses) args.add("--hex")
        if (showSourceFiles) args.add("--source")

        return args.toTypedArray()
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

        tableModel = SortableTableModel(data)
        table = JTable(tableModel).apply {
            setDefaultRenderer(Any::class.java, CenterCellRenderer())
        }

        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val column = table.columnAtPoint(e.point)
                tableModel.sortByColumn(column)
            }
        })

        val scrollPane = JScrollPane(table)
        tablePanel.add(scrollPane, BorderLayout.CENTER)
        tablePanel.isVisible = tableVisible
        tablePanel.revalidate()
        tablePanel.repaint()
    }

    private fun showMessage(panel: JPanel, message: String) {
        panel.removeAll()
        panel.add(JLabel(message), BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    private inner class SortableTableModel(private var data: List<Array<String>>) : AbstractTableModel() {
        private var sortColumn = -1
        private var ascending = true

        override fun getRowCount() = data.size
        override fun getColumnCount() = 3
        override fun getValueAt(row: Int, col: Int) = data[row][col]
        override fun getColumnName(col: Int) = when(col) { 0 -> "OBJECT"; 1 -> "ADDRESS"; 2 -> "SIZE"; else -> "" }

        fun sortByColumn(column: Int) {
            if (column == sortColumn) {
                ascending = !ascending
            } else {
                sortColumn = column
                ascending = true
            }

            data = when (column) {
                0 -> data.sortedBy { it[0] } // OBJECT
                1 -> data.sortedBy { it[1].removePrefix("0x").toLong(16) } // ADDRESS (hex)
                2 -> data.sortedBy { it[2].substringBefore(" ").toInt() } // SIZE
                else -> data
            }.let { if (ascending) it else it.reversed() }

            fireTableDataChanged()
        }
    }

    private class CenterCellRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = CENTER }
    }

    private inner class SettingsDialog(
        initialPath: String?,
        initialDemangle: Boolean,
        initialHex: Boolean,
        initialSource: Boolean
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
        private val hexCheckBox = JBCheckBox("Show hex addresses", initialHex)
        private val sourceCheckBox = JBCheckBox("Show source files", initialSource)

        val selectedFile: File? get() = pathField.text.takeIf { it.isNotEmpty() }?.let { File(it) }
        val demangleEnabled: Boolean get() = demangleCheckBox.isSelected
        val showHexAddresses: Boolean get() = hexCheckBox.isSelected
        val showSourceFiles: Boolean get() = sourceCheckBox.isSelected

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
                add(hexCheckBox)
                add(sourceCheckBox)
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