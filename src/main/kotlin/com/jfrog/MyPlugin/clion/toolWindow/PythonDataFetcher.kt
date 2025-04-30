package com.jfrog.MyPlugin.clion.toolWindow

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

class PythonDataFetcher(private val pythonExecutable: String, private val pythonScriptPath: String) {

    fun fetchData(vararg args: String): List<Array<String>> {
        val output = mutableListOf<String>()
        val errorOutput = mutableListOf<String>()

        try {
            // Собираем полную команду: python скрипт + аргументы
            val command = mutableListOf(pythonExecutable, pythonScriptPath)
            command.addAll(args)

            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(false)

            val process = processBuilder.start()

            // Чтение stdout и stderr в отдельных потоках для избежания deadlock
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            // Запускаем чтение ошибок в отдельном потоке
            val errorThread = Thread {
                var line: String?
                while (stderrReader.readLine().also { line = it } != null) {
                    errorOutput.add(line!!)
                }
            }
            errorThread.start()

            // Читаем основной вывод
            var line: String?
            while (stdoutReader.readLine().also { line = it } != null) {
                output.add(line!!)
            }

            // Ждем завершения потоков
            errorThread.join()
            val exitCode = process.waitFor()

            if (exitCode != 0 || errorOutput.isNotEmpty()) {
                println("Python script error (exit code $exitCode):")
                println(errorOutput.joinToString("\n"))
                throw RuntimeException("Python script failed: ${errorOutput.joinToString("\n")}")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }

        return parseData(output)
    }

    private fun parseData(output: List<String>): List<Array<String>> {
        // Улучшенное регулярное выражение для сложных имен функций
        val pattern = Pattern.compile("(.+?)\\s+-\\s+Адрес:\\s+(0x[0-9a-fA-F]+)\\s+-\\s+Размер:\\s+(\\d+)\\s+байт")
        val parsedData = mutableListOf<Array<String>>()

        for (line in output) {
            val matcher = pattern.matcher(line)
            if (matcher.find()) {
                parsedData.add(arrayOf(
                    matcher.group(1)?.trim() ?: "",  // objectName
                    matcher.group(2) ?: "",          // address
                    "${matcher.group(3)}"       // size
                ))
            } else if (line.isNotBlank()) {
                println("Failed to parse line: $line")
            }
        }

        return parsedData
    }
}