package dev.st33ng.mculivewatch.bridge

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Materialises the Python scripts bundled into the plugin jar and locates a usable interpreter.
 *
 * The scripts live in `src/main/resources/python/` and are copied to a per-user directory on first
 * use. This removes the last reason to depend on paths outside the project: previously the plugin
 * pointed at `~/PycharmProjects/...` and at a specific pipx venv, neither of which is portable.
 *
 * The interpreter is probed rather than assumed: the bridge needs `pylink`, the ELF parser needs
 * `pyelftools`, and they are frequently installed in different environments. Candidates are tried
 * in order and the first one that can import everything wins.
 */
@Service(Service.Level.PROJECT)
class BundledPythonService(private val project: Project) {

    /** Result of an interpreter probe. */
    data class Interpreter(val path: String, val hasPylink: Boolean, val hasElftools: Boolean) {
        val canRunBridge: Boolean get() = hasPylink
        val canRunParser: Boolean get() = hasElftools
        val isFullyUsable: Boolean get() = hasPylink && hasElftools
    }

    @Volatile
    private var extractedDir: Path? = null

    @Volatile
    private var cachedProbes: List<Interpreter>? = null

    /** Path of the extracted bridge script. */
    fun bridgeScript(): String = extract("jlink_bridge.py").toString()

    /** Path of the extracted ELF parser script. */
    fun parserScript(): String = extract("elf_symbols.py").toString()

    private fun extract(resourceName: String): Path {
        val dir = extractedDir ?: synchronized(this) {
            extractedDir ?: prepareDirectory().also { extractedDir = it }
        }

        val target = dir.resolve(resourceName)
        val stream = javaClass.getResourceAsStream("/python/$resourceName")
            ?: throw IllegalStateException("Bundled script /python/$resourceName is missing from the plugin jar")

        stream.use { input ->
            // Always overwrite: the jar content is authoritative and the copy is cheap.
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        target.toFile().setExecutable(true, false)
        return target
    }

    private fun prepareDirectory(): Path {
        val base = File(System.getProperty("java.io.tmpdir"), "mcu-live-watch")
        val dir = File(base, "python")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create ${dir.absolutePath}")
        }
        return dir.toPath()
    }

    /**
     * Every Python that could plausibly run the scripts, best candidate first.
     *
     * `pylink` and `pyelftools` are commonly split across environments (a system python for
     * pyelftools, a venv for pylink-square), so both capabilities are reported separately.
     */
    fun candidates(): List<Interpreter> {
        cachedProbes?.let { return it }

        // Probe in parallel: a single unreachable interpreter used to cost its full timeout in
        // sequence, which is how the settings dialog ended up stuck on "detecting...".
        val paths = candidatePaths()
        val futures = paths.map { path ->
            java.util.concurrent.CompletableFuture.supplyAsync({ probe(path) }, PROBE_POOL)
        }

        // One deadline for the whole batch, not one per candidate: waiting PROBE_TIMEOUT_MS for each
        // of a dozen interpreters turned a slow candidate into two minutes of "detecting...".
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_PROBE_BUDGET_MS)
        val probed = futures.mapNotNull { future ->
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) null
            else runCatching { future.get(remaining, TimeUnit.MILLISECONDS) }.getOrNull()
        }

        // Best first: both packages, then pylink only, then elftools only.
        val sorted = probed.distinctBy { it.path }.sortedByDescending { it.isFullyUsable }
        cachedProbes = sorted
        return sorted
    }

    /** Interpreter that can run the bridge, or null when none has pylink. */
    fun interpreterForBridge(): Interpreter? = candidates().firstOrNull { it.canRunBridge }

    /** Interpreter that can run the ELF parser, or null when none has pyelftools. */
    fun interpreterForParser(): Interpreter? = candidates().firstOrNull { it.canRunParser }

    fun invalidate() {
        cachedProbes = null
    }

    private fun candidatePaths(): List<String> = buildList {
        // A configured interpreter always wins.
        val configured = dev.st33ng.mculivewatch.settings.LiveExpressionSettings
            .getInstance(project).state.pythonExecutable
        if (configured.isNotBlank()) add(configured)

        // Project-local virtual environment.
        project.basePath?.let { base ->
            listOf(".venv/bin/python3", "venv/bin/python3", ".venv/Scripts/python.exe", "venv/Scripts/python.exe")
                .forEach { add(File(base, it).absolutePath) }
        }

        // Common interpreter locations on this machine.
        addAll(listOf("/usr/bin/python3", "/usr/local/bin/python3", "/opt/homebrew/bin/python3"))
        if (SystemInfo.isWindows) {
            addAll(listOf("python.exe", "python3.exe", "py.exe"))
        } else {
            addAll(listOf("python3", "python"))
        }
        addAll(listOf("/opt/homebrew/bin/python3.13", "/opt/homebrew/bin/python3.12", "/opt/homebrew/bin/python3.11"))

        // Environments the previous version hardcoded, kept as a convenience if they still exist.
        File(System.getProperty("user.home")).let { home ->
            addAll(
                listOf(
                    "Work/PythonProjects/pythonProject/.venv/bin/python3",
                    ".local/pipx/venvs/pyelftools/bin/python3",
                ).map { File(home, it).absolutePath }
            )
        }
    }.distinct()

    private fun probe(path: String): Interpreter? {
        val file = File(path)
        if (!file.isFile) return null

        val script = """
            import sys
            p = 0
            e = 0
            try:
                import pylink
                p = 1
            except Exception:
                pass
            try:
                import elftools
                e = 1
            except Exception:
                pass
            sys.stdout.write(f"{p}{e}")
        """.trimIndent()

        // Runs through ProcessRunner: the timeout must apply even if the interpreter never exits.
        val result = dev.st33ng.mculivewatch.util.ProcessRunner.run(
            command = listOf(path, "-c", script),
            timeoutMs = PROBE_TIMEOUT_MS,
        )
        if (result.timedOut || !result.ok) return null

        val output = result.stdout.trim()
        // The probe prints exactly two digits: pylink present, elftools present.
        if (output.length < 2 || !output.takeLast(2).all { it.isDigit() }) return null
        val flags = output.takeLast(2)
        return Interpreter(path, hasPylink = flags[0] == '1', hasElftools = flags[1] == '1')
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 8_000L

        /** Upper bound for probing every candidate together, so the caller never waits unbounded. */
        private const val TOTAL_PROBE_BUDGET_MS = 15_000L

        /** Parallel probes so one slow interpreter cannot delay the rest. */
        private val PROBE_POOL: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newFixedThreadPool(6) { r ->
                Thread(r, "mcu-python-probe").apply { isDaemon = true }
            }

        fun getInstance(project: Project): BundledPythonService = project.service()
    }
}
