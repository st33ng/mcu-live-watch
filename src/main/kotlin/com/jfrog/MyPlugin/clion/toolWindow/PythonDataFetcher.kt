package com.jfrog.MyPlugin.clion.toolWindow

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

class PythonDataFetcher(private val pythonExecutable: String, private val pythonScriptPath: String) {

    fun fetchData(elfFilePath: String): List<Array<String>> {  // Принимаем аргумент
        val output = mutableListOf<String>()
        val errorOutput = mutableListOf<String>()

        try {
            val processBuilder = ProcessBuilder(pythonExecutable, pythonScriptPath, elfFilePath) // Передаём файл
            processBuilder.redirectErrorStream(false)

            val process = processBuilder.start()

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (stdoutReader.readLine().also { line = it } != null) {
                output.add(line!!)
            }

            while (stderrReader.readLine().also { line = it } != null) {
                errorOutput.add(line!!)
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                println("Ошибка при выполнении Python-скрипта: ${errorOutput.joinToString("\n")}")
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return parseData(output)
    }

    private fun parseData(output: List<String>): List<Array<String>> {
        val pattern = Pattern.compile("(\\w+)\\s+-\\s+Адрес:\\s+(0x[0-9a-fA-F]+)\\s+-\\s+Размер:\\s+(\\d+)\\s+байт")
        val parsedData = mutableListOf<Array<String>>()

        for (line in output) {
            val matcher = pattern.matcher(line)
            if (matcher.find()) {
                val objectName = matcher.group(1)
                val address = matcher.group(2)
                val size = matcher.group(3)
                parsedData.add(arrayOf(objectName, address, "$size байт"))
            }
        }

        return parsedData
    }
}

