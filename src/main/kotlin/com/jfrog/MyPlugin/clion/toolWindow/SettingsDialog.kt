package com.jfrog.MyPlugin.clion.toolWindow

import javax.swing.JFileChooser
import java.io.File

class SettingsDialog {

    fun openSettingsDialog(): File? {
        val fileChooser = JFileChooser()
        fileChooser.dialogTitle = "Выберите файл для парсинга"
        fileChooser.fileSelectionMode = JFileChooser.FILES_ONLY

        val result = fileChooser.showOpenDialog(null)

        return if (result == JFileChooser.APPROVE_OPTION) {
            fileChooser.selectedFile
        } else {
            null
        }
    }
}
