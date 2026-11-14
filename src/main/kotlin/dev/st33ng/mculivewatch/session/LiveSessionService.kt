package dev.st33ng.mculivewatch.session

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import dev.st33ng.mculivewatch.state.DebugSessionStateService
import dev.st33ng.mculivewatch.storage.ElfTable
import dev.st33ng.mculivewatch.storage.LiveExpressionsService
import dev.st33ng.mculivewatch.bridge.BridgeProcessService
import dev.st33ng.mculivewatch.plot.PlotManager
import dev.st33ng.mculivewatch.plot.PlotUpdaterService
import dev.st33ng.mculivewatch.plot.SampleStore
import dev.st33ng.mculivewatch.settings.LiveExpressionSettings
import dev.st33ng.mculivewatch.ui.SocketClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * Owns the live session: the bridge socket, the reader thread, registration and sample routing.
 *
 * Why this is a project service and not part of a panel: the session used to be owned by
 * [dev.st33ng.mculivewatch.ui.LiveExpressionPanel], which tore it down in `removeNotify()`.
 * IntelliJ calls `removeNotify()` when a tool-window tab is merely *deselected* — the component is
 * removed from the tab container, not destroyed. Result: switching to the Graph tab silently killed
 * the session, and the user had to press Start again. A session is application state, not view
 * state, so its lifetime is now tied to the project.
 */
@Service(Service.Level.PROJECT)
class LiveSessionService(private val project: Project) : Disposable {

    fun interface StatusListener {
        fun statusChanged(text: String)
    }

    private val table get() = LiveExpressionsService.getInstance(project).table
    private val settings get() = LiveExpressionSettings.getInstance(project)

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private val samplesSeen = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<StatusListener>()

    @Volatile
    private var statusText: String = "Idle"

    val isRunning: Boolean get() = running.get()

    val status: String get() = statusText

    fun addStatusListener(listener: StatusListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeStatusListener(listener: StatusListener) {
        listeners.remove(listener)
    }

    /**
     * Starts the session, or resumes it when it is already running.
     *
     * Returns a user-facing reason when the session cannot even be attempted (so the caller can
     * show a dialog), or null when it was started/queued. Failures that happen later, on the reader
     * thread, surface through the status line and the log.
     */
    fun start(): String? {
        if (!isDebugActive()) return "Debug session not active"

        if (running.get()) {
            SocketClient.resume()
            SocketClient.send("start")
            setStatus("Running (already started)")
            return null
        }

        running.set(true)
        samplesSeen.set(false)
        setStatus("Starting...")

        readerThread = thread(isDaemon = true, name = "mcu-live-expression-reader") {
            try {
                // Make sure the bridge is alive before dialing the socket: if the process died
                // with the previous session, connecting would fail with a misleading error.
                val bridgeError = BridgeProcessService.getInstance(project).start()
                if (bridgeError != null) throw IllegalStateException(bridgeError)

                if (!SocketClient.connect(settings.bridgePort)) {
                    throw IllegalStateException(
                        "Cannot connect to the J-Link bridge on port ${settings.bridgePort}. " +
                            "Check the bridge script in Settings."
                    )
                }

                // A previous Stop left the client paused; without this reset the reader would
                // poll forever and no sample would ever reach the table.
                SocketClient.resume()

                // Each Start begins a fresh capture: old points would otherwise sit on the same
                // time axis as the new ones and make the chart unreadable.
                beginNewCapture()

                // The bridge may be a fresh process that knows no variables, while the table still
                // marks them "On server" from the previous session. Clearing the flags makes every
                // variable be sent again; `put` is idempotent, so an old bridge is unharmed.
                table.resetValues()
                registerVariables()
                SocketClient.send("start")
                readLoop()
                setStatus("Stopped (debug session ended)")
            } catch (e: Exception) {
                thisLogger().warn("Live expressions session failed", e)
                setStatus("Failed: ${e.message ?: "unknown error"}")
            } finally {
                running.set(false)
                SocketClient.pause()
                if (statusText == "Starting...") setStatus("Stopped")
            }
        }
        return null
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            setStatus("Not running")
            return
        }
        SocketClient.pause()
        SocketClient.send("stop")
        readerThread?.interrupt()
        readerThread = null
        table.discardPendingValues()
        // "On server" describes the bridge's state, not ours: after Stop the bridge has no active
        // stream, so leaving the flags set made a stopped session look like a running one.
        table.markAllUnregistered()
        // The captured data is deliberately kept: a stopped chart should stay readable.
        setStatus("Stopped")
    }

    private fun beginNewCapture() {
        SampleStore.clear()
        // Chart data is mutated only on the EDT, so clear it there rather than from this thread.
        SwingUtilities.invokeAndWait {
            PlotManager.clearData()
        }
        PlotUpdaterService.getInstance().resetCursors()
    }

    private fun isDebugActive(): Boolean =
        ApplicationManager.getApplication()
            .getService(DebugSessionStateService::class.java)
            .isDebugActive

    /**
     * Registers one variable if a session is already streaming.
     *
     * Without this, a variable added to the table mid-session was never sent to the bridge: its
     * Value stayed empty and its chart line never appeared until the user stopped and started
     * again. Failures are reported through the status line.
     */
    fun registerVariableIfRunning(variableName: String) {
        if (!running.get() || !SocketClient.isConnected) return

        val symbol = ElfTable.symbol(variableName)
        if (symbol == null) {
            setStatus("Cannot register $variableName: not in the ELF table")
            return
        }

        val frequency = table.registeredVariables()
            .firstOrNull { it.first == variableName }
            ?.second ?: 1000

        val message = SocketClient.createSocketMessage(
            "put",
            variableName,
            symbol.size.toInt(),
            symbol.address,
            symbol.type,
            frequency,
        )
        if (SocketClient.send(message)) {
            table.markRegistered(variableName, true)
            thisLogger().info("Registered variables: $variableName (mid-session)")
        } else {
            setStatus("Cannot register $variableName: bridge write failed")
        }
    }

    private fun registerVariables() {
        val missing = mutableListOf<String>()
        val registered = mutableListOf<String>()

        for ((name, frequency, isRegistered) in table.registeredVariables()) {
            if (isRegistered) continue

            val symbol = ElfTable.symbol(name)
            if (symbol == null) {
                missing.add("$name (not in ELF table)")
                continue
            }

            val message = SocketClient.createSocketMessage(
                "put",
                name,
                symbol.size.toInt(),
                symbol.address,
                symbol.type,
                frequency,
            )
            if (SocketClient.send(message)) {
                table.markRegistered(name, true)
                registered.add(name)
            } else {
                missing.add("$name (bridge write failed)")
            }
        }

        if (registered.isNotEmpty()) {
            thisLogger().info("Registered variables: ${registered.joinToString()}")
        }
        if (missing.isNotEmpty()) {
            thisLogger().warn("Variables not registered: ${missing.joinToString()}")
        }
    }

    private fun readLoop() {
        while (running.get()) {
            if (!isDebugActive()) {
                thisLogger().info("Debug session ended, stopping live expressions")
                return
            }

            val line = SocketClient.pollLine(READ_POLL_TIMEOUT_MS) ?: continue
            processLine(line)
        }
    }

    private fun processLine(line: String) {
        // Errors used to be dropped on the floor here, so "Target is not connected" from the
        // bridge was invisible and the panel simply showed a frozen Value column.
        if (line.startsWith("ERROR:", ignoreCase = true)) {
            val reason = line.substringAfter(':').trim()
            thisLogger().warn("Bridge error: $reason")
            setStatus("Bridge error: $reason")
            return
        }

        val separator = line.indexOf(";Data:")
        if (separator <= 0) {
            // Control replies such as "OK: ..." are not samples.
            return
        }
        val name = line.substring(0, separator).trim()
        val value = line.substring(separator + ";Data:".length).trim()
        if (name.isEmpty() || value.isEmpty()) return

        val firstSample = !samplesSeen.getAndSet(true)
        table.updateValue(name, value)
        if (firstSample) {
            // Log the first sample: "registration succeeded" and "data actually flows" look the
            // same from the outside, and the difference is exactly what was missing in the bug
            // reports ("the graph is empty" with no error anywhere).
            thisLogger().info("First sample received: $name = $value")
            setStatus("Running - receiving samples")
        }
    }

    private fun setStatus(text: String) {
        statusText = text
        val notify = { listeners.forEach { it.statusChanged(text) } }
        if (SwingUtilities.isEventDispatchThread()) notify() else SwingUtilities.invokeLater(notify)
    }

    override fun dispose() {
        running.set(false)
        SocketClient.disconnect()
        listeners.clear()
    }

    companion object {
        private const val READ_POLL_TIMEOUT_MS = 100L

        fun getInstance(project: Project): LiveSessionService = project.service()
    }
}
