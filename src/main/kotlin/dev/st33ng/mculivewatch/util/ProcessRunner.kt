package dev.st33ng.mculivewatch.util

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a child process with a working timeout.
 *
 * Why this exists: reading `process.inputStream` before calling `waitFor(timeout)` blocks until the
 * child closes the stream, so the timeout never applies. A child that waits for input, or that
 * produces more output than a pipe buffer while we are busy elsewhere, hangs the caller forever —
 * which is exactly what made the device detection spin on "detecting..." indefinitely.
 *
 * Here stdout and stderr are drained on their own threads while `waitFor` enforces the deadline, and
 * a timed-out child is killed.
 */
object ProcessRunner {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    ) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    fun run(
        command: List<String>,
        timeoutMs: Long,
        workingDir: File? = null,
        environment: Map<String, String> = emptyMap(),
    ): Result {
        val builder = ProcessBuilder(command).apply {
            redirectErrorStream(false)
            if (workingDir != null) directory(workingDir)
            environment().putAll(environment)
            environment()["PYTHONUNBUFFERED"] = "1"
        }

        val process = try {
            builder.start()
        } catch (e: Exception) {
            thisLogger().debug("Cannot start ${command.firstOrNull()}", e)
            return Result(-1, "", e.message ?: "cannot start process", timedOut = false)
        }

        val stdout = Drainer(process.inputStream, "stdout")
        val stderr = Drainer(process.errorStream, "stderr")
        stdout.start()
        stderr.start()

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!finished) {
            process.destroyForcibly()
            // Give the drainers a moment to collect whatever was already written.
            stdout.join(500)
            stderr.join(500)
            return Result(-1, stdout.text(), stderr.text(), timedOut = true)
        }

        stdout.join(1_000)
        stderr.join(1_000)
        return Result(process.exitValue(), stdout.text(), stderr.text(), timedOut = false)
    }

    private class Drainer(val stream: java.io.InputStream, val name: String) {
        private val builder = StringBuilder()
        private val thread = Thread({
            try {
                stream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(builder) { builder.append(line).append('\n') }
                    }
                }
            } catch (_: Exception) {
                // Stream closed by process termination.
            }
        }, "mcu-proc-$name").apply { isDaemon = true }

        fun start() = thread.start()

        fun join(timeoutMs: Long) = thread.join(timeoutMs)

        fun text(): String = synchronized(builder) { builder.toString() }
    }
}
