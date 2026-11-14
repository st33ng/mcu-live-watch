package dev.st33ng.mculivewatch.ui

import com.intellij.openapi.diagnostic.thisLogger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Line-oriented client for the J-Link bridge process.
 *
 * Fixed here: the old implementation guarded both `readLine()` and `send()` with one lock. Since
 * `readLine()` blocks for up to the socket timeout, a `stop` command could not be delivered for
 * several seconds while the reader was waiting — the UI appeared frozen.
 *
 * Reads and writes now use independent locks (input and output streams are independent on a TCP
 * socket), and a dedicated reader thread pushes complete lines into a bounded queue so that no
 * caller ever blocks on the socket.
 */
object SocketClient {

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 500

    private val readLock = Any()
    private val writeLock = Any()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: PrintWriter? = null

    @Volatile
    private var reader: BufferedReader? = null

    @Volatile
    private var readerThread: Thread? = null

    private val reading = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    /** Completed lines from the bridge. Bounded so a stalled consumer cannot exhaust memory. */
    private val incoming = LinkedBlockingQueue<String>(10_000)

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } ?: false

    fun connect(port: Int, host: String = "127.0.0.1"): Boolean {
        synchronized(readLock) {
            if (isConnected) return true

            return try {
                val newSocket = Socket().apply {
                    soTimeout = READ_TIMEOUT_MS
                    tcpNoDelay = true
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                }

                synchronized(writeLock) {
                    writer = PrintWriter(newSocket.getOutputStream(), true)
                }
                reader = BufferedReader(InputStreamReader(newSocket.getInputStream(), Charsets.UTF_8))
                socket = newSocket

                incoming.clear()
                startReaderThread(newSocket)
                true
            } catch (e: Exception) {
                thisLogger().warn("Cannot connect to J-Link bridge on $host:$port", e)
                disconnect()
                false
            }
        }
    }

    private fun startReaderThread(activeSocket: Socket) {
        if (reading.getAndSet(true)) return

        readerThread = Thread({
            try {
                val buffered = reader ?: return@Thread
                while (reading.get() && !activeSocket.isClosed) {
                    val line = try {
                        buffered.readLine()
                    } catch (timeout: java.net.SocketTimeoutException) {
                        // Expected: the socket timeout lets the loop notice a stop request.
                        continue
                    } catch (e: Exception) {
                        if (reading.get()) thisLogger().warn("Bridge read failed", e)
                        break
                    }

                    if (line == null) break
                    if (line.isNotBlank()) {
                        // Drop the oldest line rather than block the reader when nobody consumes.
                        if (!incoming.offer(line)) {
                            incoming.poll()
                            incoming.offer(line)
                        }
                    }
                }
            } finally {
                reading.set(false)
            }
        }, "mcu-live-socket-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun isPaused(): Boolean = paused.get()

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
        incoming.clear()
    }

    /**
     * Returns the next line without blocking for longer than [timeoutMs].
     * Never holds a lock while waiting, so [send] is always able to proceed.
     */
    fun pollLine(timeoutMs: Long = 100): String? {
        if (paused.get()) return null
        return try {
            incoming.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    fun send(message: String): Boolean {
        if (!isConnected) return false
        return synchronized(writeLock) {
            try {
                val out = writer ?: return false
                out.println(message)
                out.flush()
                if (out.checkError()) {
                    thisLogger().warn("Bridge write failed for: $message")
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                thisLogger().warn("Bridge write failed for: $message", e)
                disconnect()
                false
            }
        }
    }

    fun disconnect() {
        reading.set(false)
        synchronized(writeLock) {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            writer = null
        }
        synchronized(readLock) {
            try {
                reader?.close()
            } catch (_: Exception) {
            }
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            reader = null
            socket = null
        }
        readerThread?.interrupt()
        readerThread = null
        incoming.clear()
        paused.set(false)
    }

    fun createSocketMessage(
        command: String,
        varName: String,
        varSize: Int,
        varAddress: Long,
        varType: String,
        updateRateHz: Int = 1000,
    ): String {
        val hexAddress = "0x" + varAddress.toString(16)
        return "$command;$varName;$varSize;$hexAddress;$updateRateHz;$varType"
    }

    fun createSocketMessage(command: String, varName: String, updateRateHz: Int): String =
        "$command;$varName;$updateRateHz"

    fun createSocketMessage(command: String): String = command
}
