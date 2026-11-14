package dev.st33ng.mculivewatch.session

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.st33ng.mculivewatch.state.DebugSessionStateService
import dev.st33ng.mculivewatch.storage.LiveExpressionsService
import dev.st33ng.mculivewatch.bridge.BridgeProcessService
import dev.st33ng.mculivewatch.plot.SampleStore
import dev.st33ng.mculivewatch.settings.LiveExpressionSettings
import dev.st33ng.mculivewatch.ui.LiveExpressionPanel
import dev.st33ng.mculivewatch.ui.SocketClient
import java.io.File
import java.net.ServerSocket

/**
 * End-to-end regression for the user's actual complaint, without a J-Link and without the IDE GUI.
 *
 * The session is driven through its real code path — [LiveSessionService] spawns the bridge
 * process, dials [SocketClient], reads lines, feeds the table and [SampleStore] — against a fake
 * bridge written in Python that streams `ticks;Data:<n>` lines. The only stub is the hardware.
 *
 * The assertions encode what the user asked for:
 *  1. samples reach the table (values are not empty),
 *  2. **detaching the view — which the platform does on every tab switch — does not stop them**
 *     (this is the 3.0.6 fix; before it the session lived in the panel and died in `removeNotify`),
 *  3. Stop really stops.
 */
class LiveSessionEndToEndTest : BasePlatformTestCase() {

    private lateinit var settings: LiveExpressionSettings
    private var port = 0

    override fun setUp() {
        super.setUp()
        // Otherwise the fixture's tearDown reports "Code insight settings damaged" on every test.
        setDefaultCodeInsightSettings(CodeInsightSettings.getInstance())

        settings = LiveExpressionSettings.getInstance(project)
        port = ServerSocket(0).use { it.localPort }

        val script = File(java.nio.file.Files.createTempDirectory("fake-bridge").toFile(), "fake_bridge.py")
        script.writeText(FAKE_BRIDGE)
        settings.loadState(
            LiveExpressionSettings.State(
                pythonExecutable = PYTHON,
                bridgeScriptPath = script.absolutePath,
                bridgePort = port,
                deviceName = "FAKE",
            )
        )
    }

    override fun tearDown() {
        try {
            LiveSessionService.getInstance(project).stop()
            BridgeProcessService.getInstance(project).stop()
            SocketClient.disconnect()
            debugState().isDebugActive = false
        } finally {
            super.tearDown()
        }
    }

    private fun debugState(): DebugSessionStateService =
        ApplicationManager.getApplication().getService(DebugSessionStateService::class.java)

    fun `test samples keep flowing while the view is detached`() {
        val session = LiveSessionService.getInstance(project)
        val table = LiveExpressionsService.getInstance(project).table
        val panel = LiveExpressionPanel(project)

        table.addVariable("ticks", false, 1000)
        debugState().isDebugActive = true

        assertNull("start() must not report a user-facing error", session.start())
        assertTrue(
            "samples must arrive",
            pumpUntil(15_000) { SampleStore.variables().contains("ticks") && totalTicks() > 0 },
        )

        // The platform removes the selected content from the tab container on every tab switch,
        // which is exactly this call. It must not touch the capture.
        panel.addNotify()
        panel.removeNotify()

        val before = totalTicks()
        assertTrue("session must still be running after the view is detached", session.isRunning)
        assertTrue(
            "samples must keep arriving after the view is detached (was: capture died here)",
            pumpUntil(5_000) { totalTicks() > before },
        )

        // And the value column must actually show a number, not an empty cell.
        assertTrue(
            "the table must display the sample",
            pumpUntil(2_000) {
                (0 until table.tableModel.rowCount).any { row ->
                    table.tableModel.getValueAt(row, 0) == "ticks" &&
                        table.tableModel.getValueAt(row, 1).toString().isNotBlank()
                }
            },
        )

        // The chart is the user's actual complaint: samples must reach the XY series, not just the
        // table and the ring buffer. A variable typed into the "+" row used to get no series at
        // all, so the Graph tab stayed empty while the Value column updated.
        assertTrue(
            "the chart series must receive points",
            pumpUntil(5_000) { chartPoints("ticks") > 0 },
        )

        session.stop()
        assertFalse(session.isRunning)
        val afterStop = totalTicks()
        Thread.sleep(300)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals("no samples may arrive after Stop", afterStop, totalTicks())
    }

    private fun totalTicks(): Long = SampleStore.bufferFor("ticks").totalWritten

    private fun chartPoints(variable: String): Int {
        var count = 0
        dev.st33ng.mculivewatch.plot.PlotManager.forEachSeries {
            if (it.key.toString() == variable) count = it.itemCount
        }
        return count
    }

    /**
     * Pumps the EDT while waiting.
     *
     * Platform tests run on the EDT, and the reader thread blocks in `invokeAndWait` during
     * `beginNewCapture()` / `resetValues()`. Sleeping without dispatching would deadlock the very
     * code under test.
     */
    private fun pumpUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) return true
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
        return condition()
    }

    companion object {
        private const val PYTHON = "/usr/bin/python3"

        /** Minimal stand-in for `jlink_bridge.py`: announce readiness, accept, stream samples. */
        private val FAKE_BRIDGE = """
            import socket, sys, threading, time

            port = int(sys.argv[sys.argv.index("--port") + 1])
            server = socket.socket()
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(("127.0.0.1", port))
            server.listen(1)
            print(f"J-Link bridge on 127.0.0.1:{port}, device FAKE, waiting for connection...", flush=True)

            conn, addr = server.accept()
            print(f"Connected by {addr}", flush=True)

            def drain():
                for _ in conn.makefile("r"):
                    pass
            threading.Thread(target=drain, daemon=True).start()

            n = 0
            while True:
                n += 1
                conn.sendall(f"ticks;Data:{n}\n".encode())
                time.sleep(0.01)
        """.trimIndent()
    }
}
