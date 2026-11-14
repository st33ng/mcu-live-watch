package dev.st33ng.mculivewatch.device

import dev.st33ng.mculivewatch.util.ProcessRunner
import java.io.File

/**
 * Runs the SEGGER J-Link Commander CLI.
 *
 * The plugin reads memory through `pylink`, which wraps the very same `libjlinkarm.dylib` that
 * J-Link Commander uses. Offering the official CLI as a diagnostic answers the practical question —
 * "does SEGGER's own software agree that this device and probe work?" — without guessing.
 *
 * Note: J-Link Commander refuses to validate a device *name* unless a probe is attached, so this is
 * a connectivity check. Name validity comes from [JLinkDeviceDatabase], which reads the DLL's own
 * device list.
 */
object JLinkCommander {

    data class Result(val exitCode: Int, val output: String, val timedOut: Boolean = false) {
        val connected: Boolean
            get() = output.contains("Connected to target", ignoreCase = true)

        val failed: Boolean
            get() = timedOut ||
                output.contains("FAILED", ignoreCase = true) ||
                output.contains("Cannot connect", ignoreCase = true)
    }

    /** Common install locations, plus every SEGGER version directory found. */
    fun findExecutable(): File? {
        val versioned = (File("/Applications/SEGGER").listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("JLink") }
            .sortedByDescending { it.name }
            .map { File(it, "JLinkExe") }

        val fixed = listOf(
            "/Applications/SEGGER/JLink/JLinkExe",
            "/usr/local/bin/JLinkExe",
            "/opt/SEGGER/JLink/JLinkExe",
        ).map(::File)

        return (fixed + versioned).firstOrNull { it.canExecute() }
    }

    /** Connects once and reports SEGGER's raw output. Blocking: call off the EDT. */
    fun testConnection(device: String, speedKhz: Int, interfaceName: String = "SWD"): Result? {
        val executable = findExecutable() ?: return null

        val commandFile = File.createTempFile("mcu-live-jlink", ".jlink").apply {
            writeText("connect\nexit\n")
            deleteOnExit()
        }

        return try {
            val result = ProcessRunner.run(
                command = listOf(
                    executable.absolutePath,
                    "-NoGui", "1",
                    "-device", device,
                    "-if", interfaceName,
                    "-speed", speedKhz.toString(),
                    "-AutoConnect", "1",
                    "-CommandFile", commandFile.absolutePath,
                ),
                timeoutMs = TIMEOUT_MS,
            )
            Result(
                exitCode = result.exitCode,
                output = result.stdout + result.stderr,
                timedOut = result.timedOut,
            )
        } finally {
            commandFile.delete()
        }
    }

    private const val TIMEOUT_MS = 25_000L
}
