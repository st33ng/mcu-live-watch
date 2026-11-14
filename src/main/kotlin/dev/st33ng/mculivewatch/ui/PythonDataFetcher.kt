package dev.st33ng.mculivewatch.ui

import com.intellij.openapi.diagnostic.thisLogger
import dev.st33ng.mculivewatch.plot.SampleStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Runs the ELF symbol extractor and parses its output.
 *
 * The extractor writes one JSON object per line (`--json` mode). Parsing a locale-independent
 * format replaces the previous regex over Russian prose (`"Адрес:"`, `"Размер:"`, `"байт"`), which
 * broke as soon as the script's language or wording changed.
 *
 * Process handling fixes from the old version:
 *  - stderr is drained concurrently, so a chatty script can no longer deadlock the reader;
 *  - a non-zero exit is reported without discarding whatever symbols were parsed;
 *  - the process is forcibly killed on timeout instead of leaking a `python3` child.
 */
class PythonDataFetcher(
    private val pythonExecutable: String,
    private val pythonScriptPath: String,
) {

    fun fetchData(vararg args: String): List<ElfSymbol> {
        val command = buildList {
            add(pythonExecutable)
            add(pythonScriptPath)
            addAll(args)
        }

        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        val stdoutLines = mutableListOf<String>()
        val stderr = StringBuilder()

        val stderrThread = Thread({
            try {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { synchronized(stderr) { stderr.appendLine(it) } }
                }
            } catch (_: Exception) {
                // Stream closed by process termination; nothing to report.
            }
        }, "mcu-elf-parser-stderr").apply { isDaemon = true; start() }

        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader: BufferedReader ->
                reader.forEachLine { stdoutLines.add(it) }
            }
        } catch (e: Exception) {
            thisLogger().warn("Reading ELF parser stdout failed", e)
        }

        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException(
                "ELF parser did not finish within ${PROCESS_TIMEOUT_SECONDS}s and was terminated"
            )
        }
        stderrThread.join(1_000)

        val exitCode = process.exitValue()
        val symbols = parseOutput(stdoutLines)

        if (exitCode != 0 && symbols.isEmpty()) {
            throw IllegalStateException("ELF parser failed (exit $exitCode): ${stderr.toString().trim()}")
        }
        if (exitCode != 0) {
            thisLogger().warn("ELF parser exited $exitCode but produced ${symbols.size} symbols")
        }

        return symbols
    }

    /**
     * Accepts both output formats:
     *  - preferred: one JSON object per line (`{"name":..,"addr":..,"size":..,"type":..}`);
     *  - legacy: prose lines `name - Адрес: 0x... - Размер: N байт - Тип данных: type`.
     *
     * Keeping the legacy path means the plugin works with the existing parser script unchanged,
     * while `--json` can be enabled later without touching the Kotlin side.
     */
    private fun parseOutput(lines: List<String>): List<ElfSymbol> {
        val result = ArrayList<ElfSymbol>(lines.size)
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("{")) {
                parseSymbolJson(trimmed)?.let(result::add)
            } else {
                parseSymbolLegacy(trimmed)?.let(result::add)
            }
        }
        return result
    }

    /** Exposed for tests: parses a single output line, or returns null when it is not a symbol. */
    internal fun parseLineForTest(line: String): ElfSymbol? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("{")) parseSymbolJson(trimmed) else parseSymbolLegacy(trimmed)
    }

    /** Parses the legacy `... - Адрес: - Размер: ... байт - Тип данных: ...` line. */
    private fun parseSymbolLegacy(line: String): ElfSymbol? {
        val addressMatch = ADDRESS_PATTERN.find(line) ?: return null
        val address = addressMatch.groupValues[1].removePrefix("0x").removePrefix("0X")
            .toLongOrNull(16) ?: return null

        val name = line.substring(0, addressMatch.range.first)
            .trim()
            .removeSuffix("-")
            .trim()
        if (name.isEmpty()) return null

        val size = SIZE_PATTERN.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val type = TYPE_PATTERN.find(line)?.groupValues?.get(1)?.trim().orEmpty()

        return ElfSymbol(name = name, address = address, size = size, type = type)
    }

    private fun parseSymbolJson(json: String): ElfSymbol? {
        val name = extractString(json, "name") ?: return null
        val address = extractLong(json, "addr") ?: extractLong(json, "address") ?: return null
        val size = extractLong(json, "size") ?: 0L
        val type = extractString(json, "type") ?: ""
        return ElfSymbol(name = name, address = address, size = size, type = type)
    }

    private fun extractString(json: String, field: String): String? {
        val key = "\"$field\""
        val idx = json.indexOf(key)
        if (idx < 0) return null
        val colon = json.indexOf(':', idx + key.length)
        if (colon < 0) return null
        val start = json.indexOf('"', colon + 1)
        if (start < 0) return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    val next = json[i + 1]
                    sb.append(
                        when (next) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            '"' -> '"'
                            '\\' -> '\\'
                            else -> next
                        }
                    )
                    i += 2
                }
                c == '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    private fun extractLong(json: String, field: String): Long? {
        val key = "\"$field\""
        val idx = json.indexOf(key)
        if (idx < 0) return null
        val colon = json.indexOf(':', idx + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && (json[i].isWhitespace() || json[i] == '"')) i++
        val start = i
        while (i < json.length && (json[i].isLetterOrDigit() || json[i] == 'x' || json[i] == 'X')) i++
        if (i == start) return null
        val token = json.substring(start, i)
        return token.removePrefix("0x").removePrefix("0X").toLongOrNull(if (token.startsWith("0x", true)) 16 else 10)
    }

    companion object {
        private const val PROCESS_TIMEOUT_SECONDS = 120L

        /** `Адрес: 0x20000b0c` — the address token of the legacy line. */
        private val ADDRESS_PATTERN = Regex("""Адрес:\s*(0x[0-9a-fA-F]+)""")

        /** `Размер: 12 байт`. */
        private val SIZE_PATTERN = Regex("""Размер:\s*(\d+)""")

        /** `Тип данных: uint32_t`. */
        private val TYPE_PATTERN = Regex("""Тип данных:\s*(.+)$""")
    }
}

/** One symbol extracted from the ELF file. */
data class ElfSymbol(
    val name: String,
    val address: Long,
    val size: Long,
    val type: String,
) {
    val addressHex: String get() = "0x" + address.toString(16)
}
