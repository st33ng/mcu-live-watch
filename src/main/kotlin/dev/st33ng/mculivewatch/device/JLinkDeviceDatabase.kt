package dev.st33ng.mculivewatch.device

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import dev.st33ng.mculivewatch.util.ProcessRunner
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The device list SEGGER's own software accepts, with the manufacturer each entry belongs to.
 *
 * This is the same table the "Target Device Settings" window shows. It comes from J-Link Commander:
 *
 * ```
 * ExpDevList <file>
 * exit
 * ```
 *
 * `ExpDevList` exports the DLL's internal device database, so it needs **no probe attached and no
 * Python**: it is a plain CLI call answered in about a second. The previous implementation went
 * through `pylink`, which made a mandatory step depend on finding an interpreter that can import a
 * third-party package — that is what left the dialog stuck on "Reading device list...".
 *
 * Opening a dialog must never depend on a subprocess, though, so a copy of the export is shipped
 * inside the plugin ([BUNDLED_RESOURCE], a gzipped `ExpDevList` table). The dialog therefore fills
 * instantly and offline; asking J-Link Commander is an explicit *refresh* the user can trigger to
 * pick up the table of the SEGGER version actually installed.
 *
 * Because every name here comes from the vendor's own table, choosing from it removes the
 * CubeMX/SEGGER spelling mismatch entirely instead of guessing (`STM32L100RCTx` simply does not
 * exist; `STM32L100RC` does).
 */
@Service(Service.Level.PROJECT)
class JLinkDeviceDatabase(private val project: Project) {

    /** One selectable device. */
    data class Device(val manufacturer: String, val name: String) {
        override fun toString(): String = name
    }

    /** Where the list currently in use came from. */
    enum class Source {
        /** Shipped inside the plugin; always available, no external process involved. */
        BUNDLED,

        /** Exported by the J-Link software installed on this machine. */
        EXPORTED,
    }

    @Volatile
    private var cached: List<Device>? = null

    @Volatile
    private var currentSource: Source = Source.BUNDLED

    @Volatile
    private var lastError: String? = null

    /** Human-readable reason the last export attempt failed, or null. */
    val error: String? get() = lastError

    /** Where [devices] came from, for the status line. */
    val source: Source get() = currentSource

    /**
     * All devices.
     *
     * Never blocks on an external process: the previously exported table is used when it is still
     * fresh, otherwise the bundled one. Returns an empty list only if both are unreadable, which
     * would mean the installed plugin is corrupt.
     */
    fun devices(): List<Device> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }

            val exported = exportFile()
            if (isFreshExport(exported)) {
                val parsed = runCatching { parseCsv(exported.readText()) }
                    .onFailure { thisLogger().warn("Cannot parse ${exported.absolutePath}", it) }
                    .getOrDefault(emptyList())
                if (parsed.isNotEmpty()) {
                    thisLogger().info("Using the device list exported by J-Link Commander: ${parsed.size} devices")
                    currentSource = Source.EXPORTED
                    cached = parsed
                    return parsed
                }
            }

            val bundled = loadBundled()
            thisLogger().info("Using the bundled device list: ${bundled.size} devices")
            if (bundled.isEmpty()) {
                lastError = "The device list shipped with the plugin is missing. Reinstall the plugin."
            }
            currentSource = Source.BUNDLED
            cached = bundled
            return bundled
        }
    }

    /** Manufacturers present in the list, sorted, with SEGGER's generic entries last. */
    fun manufacturers(): List<String> =
        devices().map { it.manufacturer }
            .distinct()
            .sortedWith(compareBy({ it == UNSPECIFIED }, { it.lowercase() }))

    fun devicesOf(manufacturer: String?, filterText: String? = null): List<Device> {
        val needle = filterText?.trim()?.lowercase().orEmpty()
        return devices().asSequence()
            .filter { manufacturer == null || it.manufacturer == manufacturer }
            .filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
            .sortedBy { it.name }
            .toList()
    }

    /**
     * Re-reads the table from the installed J-Link software.
     *
     * Returns null on success, or an error message on failure. On failure the current list is kept,
     * so a broken SEGGER installation degrades to the bundled table instead of an empty dialog.
     */
    fun refresh(): String? {
        val target = exportFile()
        thisLogger().info("Exporting the device list to ${target.absolutePath}")
        val failure = runExport(target)
        if (failure != null) {
            thisLogger().warn("J-Link device export failed: $failure")
            lastError = failure
            return failure
        }

        val parsed = runCatching { parseCsv(target.readText()) }
            .onFailure { thisLogger().warn("Cannot parse ${target.absolutePath}", it) }
            .getOrDefault(emptyList())

        if (parsed.isEmpty()) {
            val message = "J-Link Commander exported an empty device list. Reinstall the SEGGER " +
                "J-Link software and try again."
            thisLogger().warn(message)
            lastError = message
            return message
        }

        thisLogger().info("J-Link device export succeeded: ${parsed.size} devices")
        lastError = null
        currentSource = Source.EXPORTED
        cached = parsed
        return null
    }

    /** Forgets everything, including the exported copy, so the next [devices] call starts over. */
    fun invalidate() {
        synchronized(this) {
            cached = null
            lastError = null
            currentSource = Source.BUNDLED
            exportFile().delete()
        }
    }

    /**
     * Asks J-Link Commander to export its device table.
     *
     * Returns an error message on failure, null on success. The command file and the export are kept
     * in the same scratch directory, so the path handed to `ExpDevList` is a bare file name and can
     * never be broken by spaces or quoting.
     */
    private fun runExport(target: File): String? {
        val executable = JLinkCommander.findExecutable()
            ?: return "SEGGER J-Link software not found. Install it from segger.com/downloads/jlink " +
                "and restart the IDE."

        val dir = target.parentFile
        if (!dir.isDirectory && !dir.mkdirs()) {
            return "Cannot create the scratch directory ${dir.absolutePath}."
        }

        val script = File(dir, SCRIPT_NAME)
        return try {
            script.writeText("ExpDevList $EXPORT_NAME\nexit\n")
            target.delete()

            val result = ProcessRunner.run(
                command = listOf(executable.absolutePath, "-NoGui", "1", "-CommandFile", SCRIPT_NAME),
                timeoutMs = FETCH_TIMEOUT_MS,
                workingDir = dir,
            )

            when {
                result.timedOut -> "J-Link Commander did not finish exporting the device list within " +
                    "${FETCH_TIMEOUT_MS / 1000} s."

                result.exitCode != 0 -> buildString {
                    append("J-Link Commander failed to export the device list (exit code ")
                    append(result.exitCode).append(").")
                    val detail = (result.stderr.ifBlank { result.stdout }).trim().take(400)
                    if (detail.isNotEmpty()) append(' ').append(detail)
                }

                !target.isFile -> "J-Link Commander reported success but wrote no device list. " +
                    "Check the SEGGER installation at ${executable.absolutePath}."

                else -> null
            }
        } catch (e: Exception) {
            thisLogger().warn("Cannot run J-Link Commander", e)
            "Cannot run J-Link Commander: ${e.message}"
        } finally {
            script.delete()
        }
    }

    private fun exportFile(): File =
        File(File(System.getProperty("java.io.tmpdir"), SCRATCH_DIR), EXPORT_NAME)

    private fun isFreshExport(file: File): Boolean {
        if (!file.isFile) return false
        if (System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MS) return false
        // Trust only a file that is actually an ExpDevList export. A same-named file left by an
        // older tool (or an older format) must be replaced, not parsed as if it were a device table.
        return runCatching {
            file.bufferedReader().use { it.readLine() }.orEmpty().trimStart().startsWith("\"$HEADER_MANUFACTURER\"")
        }.getOrDefault(false)
    }

    internal fun parseCsv(raw: String): List<Device> = parseLines(raw)

    companion object {
        const val UNSPECIFIED = "Unspecified"

        /** Gzipped `ExpDevList` table shipped in the plugin jar. */
        private const val BUNDLED_RESOURCE = "/device/jlink-devices.csv.gz"

        private const val SCRATCH_DIR = "mcu-live-watch"
        private const val EXPORT_NAME = "jlink-device-list.csv"
        private const val SCRIPT_NAME = "export-devices.jlink"
        private const val HEADER_MANUFACTURER = "Manufacturer"

        private const val FETCH_TIMEOUT_MS = 90_000L
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

        @Volatile
        private var bundledCache: List<Device>? = null

        /** Reads the table shipped inside the plugin. Cached for the JVM's lifetime. */
        fun loadBundled(): List<Device> {
            bundledCache?.let { return it }
            synchronized(JLinkDeviceDatabase::class.java) {
                bundledCache?.let { return it }
                val parsed = runCatching {
                    val stream = JLinkDeviceDatabase::class.java.getResourceAsStream(BUNDLED_RESOURCE)
                        ?: return@runCatching emptyList()
                    GZIPInputStream(stream).bufferedReader().use { parseLines(it.readText()) }
                }.onFailure {
                    thisLogger().warn("Cannot read the bundled device list $BUNDLED_RESOURCE", it)
                }.getOrDefault(emptyList())
                bundledCache = parsed
                return parsed
            }
        }

        /**
         * Parses the CSV written by `ExpDevList`; exposed for tests.
         *
         * One line looks like:
         * `"ST", "STM32L100RC", "Cortex-M3", { {0x08000000, 0x00040000} }, {0x20000000, 0x00004000}`
         *
         * Only the first two quoted fields matter. The braces contain commas, so splitting on `,`
         * would corrupt the row — hence a real (if small) quoted-field scanner.
         */
        internal fun parseLines(raw: String): List<Device> =
            raw.lineSequence()
                .mapNotNull { line ->
                    val fields = firstQuotedFields(line, 2)
                    if (fields.size < 2) return@mapNotNull null
                    // The export begins with a header row naming the columns.
                    if (fields[0].equals("Manufacturer", true) && fields[1].equals("Device", true)) {
                        return@mapNotNull null
                    }
                    val manufacturer = fields[0].trim().ifBlank { UNSPECIFIED }
                    val name = fields[1].trim()
                    if (name.isEmpty()) null else Device(manufacturer, name)
                }
                .distinctBy { "${it.manufacturer}\u0000${it.name}" }
                .sortedWith(compareBy({ it.manufacturer == UNSPECIFIED }, { it.manufacturer.lowercase() }, { it.name }))
                .toList()

        /**
         * Reads up to [count] leading `"..."` fields from [line].
         *
         * Stops at the first character that is not a quoted field, which keeps the brace sections
         * out of the result without having to understand them.
         */
        private fun firstQuotedFields(line: String, count: Int): List<String> {
            val fields = ArrayList<String>(count)
            var index = 0
            while (index < line.length && fields.size < count) {
                // Skip separators and padding.
                while (index < line.length && (line[index] == ',' || line[index].isWhitespace())) index++
                if (index >= line.length || line[index] != '"') break
                index++
                val value = StringBuilder()
                var closed = false
                while (index < line.length) {
                    val c = line[index]
                    if (c == '"') {
                        index++
                        closed = true
                        break
                    }
                    value.append(c)
                    index++
                }
                if (!closed) break
                fields.add(value.toString())
            }
            return fields
        }

        fun getInstance(project: Project): JLinkDeviceDatabase = project.service()
    }
}
