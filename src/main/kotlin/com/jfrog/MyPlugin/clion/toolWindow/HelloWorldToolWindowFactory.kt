package com.jfrog.MyPlugin.clion.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.jfrog.MyPlugin.clion.StateServices.DebugSessionStateService
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import javax.swing.*
import javax.swing.table.*

import java.io.*
import java.lang.management.ManagementFactory
import java.net.*

class HelloWorldToolWindowFactory : ToolWindowFactory {

    private val pythonExecutable = "/Users/timtim/.local/pipx/venvs/pyelftools/bin/python3"
    private val pythonScriptPath = "/Users/timtim/PycharmProjects/pythonProject1/main.py"

    private var currentElfPath: String? = null
    private var demangleEnabled = false

    private var currentData: List<Array<String>> = emptyList()
    private var lastPythonArgs: Array<String>? = null
    private var tableVisible = true

    private lateinit var tableElfPars: JTable
    private lateinit var tableModelElfPars: SortableTableModel

    private lateinit var tableLiveExpression: JTable
    private lateinit var tableModelLiveExpression: DefaultTableModel


    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout())

        val tablePanel = JPanel(BorderLayout())
        panel.add(tablePanel, BorderLayout.CENTER)

        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
        panel.add(infoPanel, BorderLayout.NORTH)

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

        panel.add(controlPanel, BorderLayout.SOUTH)

        val projectDir = File(project.basePath ?: return)
        val elfFiles = projectDir.walk().filter { it.isFile && it.extension == "elf" }.toList()
        currentElfPath = if (elfFiles.isNotEmpty()) elfFiles.first().absolutePath else null

        updateInfoPanel(infoPanel)
        updateTable(tablePanel, buildPythonArgs())

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(panel, "Elf Parser", false)
        toolWindow.contentManager.addContent(content)


        // LIVE EXPR VIEW.
        val liveExprPanel = JPanel(BorderLayout())

        // Создание controlPanel для кнопок "Start" и "Stop"
        val liveExprControlPanel = JPanel()
        liveExprControlPanel.layout = BoxLayout(liveExprControlPanel, BoxLayout.X_AXIS)

        // Кнопка Start
        val startButton = JButton("Start")
        startButton.addActionListener {
            //TODO проверка что таблица не пуста.
            if(ApplicationManager.getApplication().getService(DebugSessionStateService::class.java).isDebugActive) {
                Thread {
                    try {
                        // Создание сокета клиента
                        val clientSocket =
                            Socket("localhost", 12346) // подключаемся к серверу по локальному адресу и порту

                        // Создание потоков для отправки и получения данных
                        val out = PrintWriter(clientSocket.getOutputStream(), true)
                        val `in` = BufferedReader(InputStreamReader(clientSocket.getInputStream()))

                        val tableModelLiveExpression = tableLiveExpression.model as DefaultTableModel
                        val rowCount = tableModelLiveExpression.rowCount

                        // Проходим по всем строкам таблицы, исключая последнюю с "+"
                        for (i in 1 until rowCount) {  // Начинаем с 1, чтобы пропустить первую строку с "+"
                            val varName = tableModelLiveExpression.getValueAt(i, 0) as String  // Имя из первой колонки

                            // Теперь извлекаем данные из tableModelElfPars по имени
                            var elfData: List<String>? = null

                            // Проходим по строкам tableModelElfPars, чтобы найти нужное имя
                            for (j in 0 until tableModelElfPars.rowCount) {
                                val elfVarName =
                                    tableModelElfPars.getValueAt(j, 0)  // Имя из первой колонки tableModelElfPars
                                if (elfVarName == varName) {
                                    // Если нашли совпадение, извлекаем данные
                                    elfData = (1 until tableModelElfPars.columnCount).map {
                                        tableModelElfPars.getValueAt(j, it)
                                    }
                                    break
                                }
                            }

                            if (elfData != null) {
                                // Создаем сообщение для этого имени
                                val address = elfData[0].removePrefix("0x").toInt(16)
                                val message = createSocketMsg("put", varName, elfData[1].toInt(), address)
                                out.println(message)
                            } else {
                                println("No data found for $varName")
                            }
                        }
                        out.println(createSocketMsg("start"))

                        // Чтение данных от сервера
                        var data: String?
                        while (`in`.readLine().also { data = it } != null) {
                            val text = data!!.trim()
                            println(text)
                        }

                        // Закрытие соединения
                        clientSocket.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }.start()
            }else{
                println("Debug not started")
            }
        }

        // Кнопка Stop
        val stopButton = JButton("Stop")
        stopButton.addActionListener {
            // Логика для остановки
            println("Stop button clicked")
            // Ваш код для остановки обработки
        }

        // Добавляем кнопки на панель
        liveExprControlPanel.add(startButton)
        liveExprControlPanel.add(stopButton)

        // Добавляем controlPanel в liveExprPanel
        liveExprPanel.add(liveExprControlPanel, BorderLayout.NORTH)

        // Настройка таблицы live expressions
        setupLiveExprTable()
        val scrollPane = JBScrollPane(tableLiveExpression)
        liveExprPanel.add(scrollPane, BorderLayout.CENTER)

        val liveExprContent = contentFactory.createContent(liveExprPanel, "Live Expressions", false)
        toolWindow.contentManager.addContent(liveExprContent)
    }

    private fun createSocketMsg(command: String, varName: String, varSize: Int, varAddress: Int, updFrqMs: Int = 1000): String {
        val hexAddress = varAddress.toString(16)
        return "${command};${varName};${varSize};0x$hexAddress;${updFrqMs}"

    }

    private fun createSocketMsg(command: String): String {
        return command
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

        val args = mutableListOf("-p", currentElfPath!!)
        if (demangleEnabled) args.add("-d")

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

    private fun setupLiveExprTable() {
        val columnNames = arrayOf("Expression", "Value")
        tableModelLiveExpression = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                // Разрешить редактирование всех строк, кроме "плюса"
                return row != rowCount - 1
            }
        }

        tableModelLiveExpression.addRow(arrayOf("+", ""))

        tableLiveExpression = JBTable(tableModelLiveExpression)

        // Обработка клика на строку "+", добавление новой строки
        // TODO добавить проверку что есть в раме.
        tableLiveExpression.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = tableLiveExpression.rowAtPoint(e.point)
                if (row == tableModelLiveExpression.rowCount - 1) {
                    // Получаем предполагаемое имя переменной (можно также сразу фокус туда поставить и подождать ввода)
                    val variableName = JOptionPane.showInputDialog(
                        tableLiveExpression,
                        "Введите имя переменной (должна быть в ELF таблице):",
                        "Добавление выражения",
                        JOptionPane.PLAIN_MESSAGE
                    ) ?: return

                    // Проверка: есть ли такое имя в elfParsTable
                    val found = (0 until tableElfPars.rowCount).any { i ->
                        val value = tableElfPars.getValueAt(i, 0)?.toString()?.trim()
                        value.equals(variableName.trim(), ignoreCase = true)
                    }

                    if (!found) {
                        JOptionPane.showMessageDialog(
                            tableLiveExpression,
                            "Переменная '$variableName' не найдена в ELF таблице.",
                            "Ошибка",
                            JOptionPane.ERROR_MESSAGE
                        )
                        return
                    }

                    // Добавляем строку с именем переменной, второй столбец — пустой
                    tableModelLiveExpression.insertRow(tableModelLiveExpression.rowCount - 1, arrayOf(variableName, ""))
                }
            }
        })

        tableLiveExpression.fillsViewportHeight = true
    }

    private fun createAndShowTable(tablePanel: JPanel, data: List<Array<String>>) {
        tablePanel.removeAll()

        if (data.isEmpty()) {
            showMessage(tablePanel, "No data received")
            return
        }

        tableModelElfPars = SortableTableModel(data)
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

    // Метод для выпадающего списка после пкм
    private fun showContextMenu(e: MouseEvent, row: Int) {
        val popupMenu = JPopupMenu()

        val actionItem = JMenuItem("Add to live expession")
        actionItem.addActionListener {
            val objectName = tableElfPars.getValueAt(row, 0).toString()
            tableModelLiveExpression.addRow(arrayOf(objectName, ""))
            // TODO добавить проверку что в раме.
        }
        popupMenu.add(actionItem)
        popupMenu.show(e.component, e.x, e.y)
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
                0 -> data.sortedBy { it[0] }
                1 -> data.sortedBy { it[1].removePrefix("0x").toLong(16) }
                2 -> data.sortedBy { it[2].substringBefore(" ").toInt() }
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