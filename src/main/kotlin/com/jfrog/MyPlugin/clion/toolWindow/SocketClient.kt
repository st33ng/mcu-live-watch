package com.jfrog.MyPlugin.clion.toolWindow

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

object SocketClient {
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var writer: PrintWriter? = null
    @Volatile
    private var reader: BufferedReader? = null
    @Volatile
    private var paused = false // Новый флаг паузы

    private val connectionLock = Any()

    val isConnected: Boolean
        get() = socket?.isConnected ?: false

    fun connect(port: Int): Boolean = synchronized(connectionLock) {
        if (socket?.isConnected == true) return true

        return try {
            // Создаем новый сокет
            val newSocket = Socket().apply {
                soTimeout = 5000
                connect(InetSocketAddress("localhost", port), 5000)
            }

            // Инициализируем поля
            socket = newSocket
            writer = PrintWriter(newSocket.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))

            true
        } catch (e: Exception) {
            println("In fun connect fail")
            disconnect()
            false
        }
    }

    fun isPaused(): Boolean = paused

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun readLine(): String? = synchronized(connectionLock) {
        if (paused || socket?.isConnected == false) return null
        return try {
            reader?.readLine()
        } catch (e: Exception) {
            null
        }
    }

    fun send(message: String): Unit = synchronized(connectionLock) {
        println("IN SEND $isConnected")
        if (socket?.isConnected == false) return

        try {
            writer?.println(message)
            writer?.flush()
        } catch (e: Exception) {
            println("send fail")
            disconnect()
        }
    }

    fun disconnect() = synchronized(connectionLock) {
        try {
            reader?.close()
            writer?.close()
            socket?.close()
        } finally {
            reader = null
            writer = null
            socket = null
        }
    }

    fun createSocketMessage(command: String, varName: String, varSize: Int, varAddress: Int, varType: String, updFrqMs: Int = 1000): String {
        val hexAddress = varAddress.toString(16)
        return "${command};${varName};${varSize};0x$hexAddress;${updFrqMs};${varType}"

    }

    fun createSocketMessage(command: String): String {
        return command
    }
}