package com.jfrog.MyPlugin.clion.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.io.File
import javax.swing.*
import java.awt.BorderLayout  // Добавлен импорт
import com.intellij.openapi.application.ApplicationManager
import javax.swing.table.DefaultTableCellRenderer

class HelloWorldToolWindowFactory : ToolWindowFactory {

    private val pythonExecutable = "/Users/timtim/.local/pipx/venvs/pyelftools/bin/python3"
    private val pythonScriptPath = "/Users/timtim/PycharmProjects/pythonProject1/main.py"

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Основная панель с BorderLayout
        val panel = JPanel()
        panel.layout = BorderLayout()

        // Панель для таблицы или сообщения
        val tablePanel = JPanel()
        tablePanel.layout = BorderLayout()
        panel.add(tablePanel, BorderLayout.CENTER)

        // Кнопка настроек
        val settingsButton = JButton("Settings")
        settingsButton.addActionListener {
            val settingsDialog = SettingsDialog()
            val selectedFile = settingsDialog.openSettingsDialog()
            if (selectedFile != null) {
                updateTable(tablePanel, "-p" + selectedFile.absolutePath)
            }
        }
        panel.add(settingsButton, BorderLayout.SOUTH)

        // Автоматический поиск файла .elf в директории проекта
        val projectDir = File(project.basePath ?: return)
        val elfFiles = projectDir.walk().filter { it.isFile && it.extension == "elf" }.toList()
        val elfFilePath = if (elfFiles.isNotEmpty()) elfFiles.first().absolutePath else null

        // Инициализация содержимого
        updateTable(tablePanel, elfFilePath)

        // Добавление контента в окно инструмента
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun updateTable(tablePanel: JPanel, elfFilePath: String?) {
        tablePanel.removeAll()
        if (elfFilePath != null) {
            // Показываем "Loading..." перед началом загрузки данных
            tablePanel.add(JLabel("Loading..."), BorderLayout.CENTER)
            tablePanel.revalidate()
            tablePanel.repaint()

            // Загрузка данных в фоновом потоке
            ApplicationManager.getApplication().executeOnPooledThread {
                val pythonFetcher = PythonDataFetcher(pythonExecutable, pythonScriptPath)
                val data = pythonFetcher.fetchData(elfFilePath)
                // Обновление UI в основном потоке
                ApplicationManager.getApplication().invokeLater {
                    tablePanel.removeAll()
                    val tableModel = TableModel(data)
                    val table = JTable(tableModel)

                    // Установка выравнивания по центру для всех ячеек
                    val centerRenderer = DefaultTableCellRenderer()
                    centerRenderer.horizontalAlignment = DefaultTableCellRenderer.CENTER
                    for (i in 0 until table.columnCount) {
                        table.getColumnModel().getColumn(i).cellRenderer = centerRenderer
                    }

                    val scrollPane = JScrollPane(table)
                    tablePanel.add(scrollPane, BorderLayout.CENTER)
                    tablePanel.revalidate()
                    tablePanel.repaint()
                }
            }
        } else {
            // Если файл не выбран, показываем сообщение
            tablePanel.add(JLabel("No ELF file selected"), BorderLayout.CENTER)
            tablePanel.revalidate()
            tablePanel.repaint()
        }
    }
}