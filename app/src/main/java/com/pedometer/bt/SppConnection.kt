package com.pedometer.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class SppConnection(
    private val onData: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    companion object {
        private const val TAG = "SppConnection"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val MAX_RETRIES = 5
        private const val BASE_DELAY_MS = 1000L
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    @Volatile private var running = false
    private var device: BluetoothDevice? = null
    var autoReconnect = false

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        this.device = device

        // Strategy: connect as client AND listen as server simultaneously
        // Mi Fitness acts as server — watch connects TO it
        val adapter = BluetoothAdapter.getDefaultAdapter()

        // Start server listener in background
        var serverSocket: BluetoothServerSocket? = null
        val serverThread = Thread({
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("Pedometer", SPP_UUID)
                Log.i(TAG, "RFCOMM server listening on UUID $SPP_UUID")
                val accepted = serverSocket?.accept(30000) // 30s timeout
                if (accepted != null && !running) {
                    Log.i(TAG, "Watch connected to us as SERVER! remote=${accepted.remoteDevice.address}")
                    socket = accepted
                    inputStream = accepted.inputStream
                    outputStream = accepted.outputStream
                    running = true
                    Thread({ readLoop() }, "spp-read").start()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Server accept ended: ${e.message}")
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }, "spp-server")
        serverThread.start()

        // Mi Fitness reconnect pattern: scn=2 first, close, then scn=1
        try { serverSocket?.close() } catch (_: Exception) {}

        try { serverSocket?.close() } catch (_: Exception) {}

        // Simple client connect
        for (attempt in 1..3) {
            try {
                val s = if (attempt <= 1) {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                } else {
                    Log.i(TAG, "Using reflection createRfcommSocket on attempt $attempt")
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                    m.invoke(device, 2) as BluetoothSocket
                }
                s.connect()
                socket = s
                inputStream = s.inputStream
                outputStream = s.outputStream
                running = true
                Thread({ readLoop() }, "spp-read").start()
                Log.i(TAG, "Connected to ${device.address} on attempt $attempt")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Connection attempt $attempt failed", e)
                if (attempt < 3) Thread.sleep(2000)
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun attemptReconnect() {
        val dev = device ?: return
        if (!autoReconnect) return

        for (attempt in 1..MAX_RETRIES) {
            val delay = BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4))
            Log.i(TAG, "Reconnect attempt $attempt/$MAX_RETRIES in ${delay}ms")
            try { Thread.sleep(delay) } catch (_: InterruptedException) { return }

            try {
                val s = dev.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                inputStream = s.inputStream
                outputStream = s.outputStream
                running = true
                Log.i(TAG, "Reconnected on attempt $attempt")
                Thread({ readLoop() }, "spp-read").start()
                return
            } catch (e: IOException) {
                Log.w(TAG, "Reconnect attempt $attempt failed", e)
            }
        }
        Log.e(TAG, "All reconnect attempts failed")
        onDisconnected()
    }

    fun write(data: ByteArray) {
        try {
            outputStream?.write(data)
            outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Write failed", e)
            disconnect()
        }
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        inputStream = null
        outputStream = null
    }

    val isConnected: Boolean get() = socket?.isConnected == true && running

    private fun readLoop() {
        val buf = ByteArray(4096)
        try {
            while (running) {
                val n = inputStream?.read(buf) ?: -1
                if (n < 0) break
                val received = buf.copyOf(n)
                Log.d(TAG, "RAW RX ${received.size} bytes: ${received.take(32).joinToString(":") { "%02x".format(it) }}${if (received.size > 32) "..." else ""}")
                onData(received)
            }
        } catch (e: IOException) {
            if (running) Log.e(TAG, "Read error", e)
        } finally {
            val wasRunning = running
            running = false
            try { socket?.close() } catch (_: IOException) {}
            if (wasRunning && autoReconnect) {
                Thread({ attemptReconnect() }, "spp-reconnect").start()
            } else {
                onDisconnected()
            }
        }
    }
}
