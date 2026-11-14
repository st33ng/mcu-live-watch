package dev.st33ng.mculivewatch.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import dev.st33ng.mculivewatch.settings.LiveExpressionSettings
import java.io.File
import java.util.ArrayDeque

/**
 * Owns the J-Link bridge child process for a project.
 *
 * The old `PythonDataServer` started `python3 <script>` from `createToolWindowContent` and threw
 * the returned [Process] away: every reopened tool window spawned another process, nothing ever
 * terminated it, and the second instance failed on the busy port while the orphan kept the J-Link
 * probe attached.
 *
 * This service starts the bridge once per project, refuses to start a second copy, terminates the
 * process when the project closes, and — most importantly — only reports success once the child
 * has announced that it is **listening**. The bridge imports `pylink` and binds its socket after
 * that, which took longer than a fixed sleep, so the session used to dial a port that was not open
 * yet and fail with "Cannot connect to the J-Link bridge".
 */
@Service(Service.Level.PROJECT)
class BridgeProcessService(private val project: Project) : Disposable {

    @Volatile
    private var process: Process? = null

    /** Set by the output drain thread once the bridge prints its "waiting for connection" line. */
    @Volatile
    private var ready = false

    private val outputLock = Any()
    private val output = ArrayDeque<String>()

    val isRunning: Boolean
        get() = process?.isAlive == true

    /**
     * Starts the bridge if it is not running yet, and waits until it is accepting connections.
     * Returns null on success, or a human-readable reason on failure.
     */
    fun start(): String? {
        // A child that is alive but never announced readiness cannot be dialled: it is either
        // still importing pylink forever or it is wedged. Replacement is cheaper than waiting.
        if (isRunning) {
            if (ready) return null
            stop()
        }

        val settings = LiveExpressionSettings.getInstance(project)

        // Both resolve from the plugin's own resources unless the user overrode them.
        val python = settings.pythonForBridge()
            ?: return "No Python interpreter with pylink found. Install pylink-square " +
                "(pip install pylink-square) or set the interpreter in Settings."
        if (!File(python).canExecute()) {
            return "Python interpreter is not executable: $python"
        }

        val script = try {
            settings.bridgeScript()
        } catch (e: Exception) {
            return "Cannot extract the bundled bridge script: ${e.message}"
        }
        if (!File(script).isFile) return "Bridge script not found: $script"

        // Refuse to start without a device: guessing one means reading registers from the wrong MCU.
        val device = settings.deviceName
        if (device.isEmpty()) {
            return "No J-Link device selected. Open Settings and press Select... to choose one."
        }

        synchronized(outputLock) { output.clear() }
        ready = false

        return try {
            val command = buildList {
                add(python)
                add(script)
                add("--port")
                add(settings.bridgePort.toString())
                add("--device")
                add(device)
                if (settings.jlinkSpeedKhz > 0) {
                    add("--speed")
                    add(settings.jlinkSpeedKhz.toString())
                }
            }

            val builder = ProcessBuilder(command).apply {
                directory(File(script).parentFile)
                environment()["PYTHONUNBUFFERED"] = "1"
                redirectErrorStream(true)
            }
            val started = builder.start()
            process = started

            // Drain the child's output on a separate thread. Doing this inline would block start()
            // for the whole lifetime of the bridge, because the bridge runs until stopped.
            drainOutputAsync(started)

            awaitListening(started, settings.bridgePort)
        } catch (e: Exception) {
            thisLogger().warn("Cannot start J-Link bridge", e)
            process = null
            "Cannot start bridge: ${e.message}${outputTail()}"
        }
    }

    /**
     * Waits for the bridge to print its readiness line.
     *
     * Deliberately does **not** probe the port with a socket connect: the bridge accepts exactly
     * one connection and never returns to `accept()`, so a probe would take the connection slot and
     * the real client would wait in the backlog forever.
     */
    private fun awaitListening(started: Process, port: Int): String? {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ready) {
                thisLogger().info("J-Link bridge is listening on port $port")
                return null
            }
            if (!started.isAlive) {
                val code = started.exitValue()
                process = null
                return "Bridge exited with code $code before it began listening on port $port." +
                    outputTail()
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        stop()
        return "The J-Link bridge did not start listening on port $port within " +
            "${START_TIMEOUT_MS / 1000} s.${outputTail()}"
    }

    private fun drainOutputAsync(started: Process) {
        Thread({
            try {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        record(line)
                        thisLogger().debug("[bridge] $line")
                    }
                }
            } catch (_: Exception) {
                // Process destroyed while reading; nothing to report.
            }
        }, "mcu-bridge-output").apply { isDaemon = true }.start()
    }

    private fun record(line: String) {
        synchronized(outputLock) {
            output.addLast(line)
            while (output.size > MAX_OUTPUT_LINES) output.removeFirst()
        }
        if (READY_MARKERS.any { line.contains(it, ignoreCase = true) }) {
            ready = true
        }
    }

    /** Last lines of the child's output, so a failure says *why* instead of just *that*. */
    private fun outputTail(): String {
        val lines = synchronized(outputLock) { output.toList() }
        if (lines.isEmpty()) return ""
        return "\nBridge output:\n" + lines.joinToString("\n") { "  $it" }
    }

    fun stop() {
        ready = false
        val current = process ?: return
        process = null
        try {
            current.destroy()
            if (!current.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                current.destroyForcibly()
            }
        } catch (e: Exception) {
            thisLogger().warn("Cannot stop J-Link bridge", e)
        }
    }

    override fun dispose() = stop()

    companion object {
        /** How long the bridge may take to import pylink, bind and announce itself. */
        private const val START_TIMEOUT_MS = 20_000L

        private const val POLL_INTERVAL_MS = 50L

        private const val MAX_OUTPUT_LINES = 40

        /**
         * Text printed by the bundled bridge right after `listen()`. Extra markers keep a
         * user-supplied script working if it words the message differently.
         */
        private val READY_MARKERS = listOf("waiting for connection", "listening on")

        fun getInstance(project: Project): BridgeProcessService = project.service()
    }
}
