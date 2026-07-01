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
    @Volatile private var connected = false // guard against double-connect
    private var device: BluetoothDevice? = null
    private var serverThread: Thread? = null
    private var reconnectThread: Thread? = null
    var autoReconnect = false

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        this.device = device
        connected = false

        val adapter = BluetoothAdapter.getDefaultAdapter()

        // Start server listener in background
        var serverSocket: BluetoothServerSocket? = null
        serverThread = Thread({
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("Pedometer", SPP_UUID)
                Log.i(TAG, "RFCOMM server listening")
                val accepted = serverSocket?.accept(30000)
                if (accepted != null) {
                    synchronized(this) {
                        if (!connected) {
                            connected = true
                            socket = accepted
                            inputStream = accepted.inputStream
                            outputStream = accepted.outputStream
                            running = true
                            Thread({ readLoop() }, "spp-read").start()
                            Log.i(TAG, "Watch connected as SERVER")
                        } else {
                            // Client already connected, close server socket
                            try { accepted.close() } catch (_: Exception) {}
                            Log.d(TAG, "Server accept ignored — already connected as client")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Server accept ended: ${e.message}")
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }, "spp-server")
        serverThread?.start()

        // Try client connection
        if (tryClientConnect(device)) return true

        // Client failed — try reflection channel 2
        try {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            val s = m.invoke(device, 2) as BluetoothSocket
            s.connect()
            synchronized(this) {
                if (!connected) {
                    connected = true
                    socket = s
                    inputStream = s.inputStream
                    outputStream = s.outputStream
                    running = true
                    Thread({ readLoop() }, "spp-read").start()
                    Log.i(TAG, "Connected via channel 2")
                    closeServerThread()
                    return true
                } else {
                    try { s.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Channel 2 failed: ${e.message}")
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun tryClientConnect(device: BluetoothDevice): Boolean {
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            synchronized(this) {
                if (!connected) {
                    connected = true
                    socket = s
                    inputStream = s.inputStream
                    outputStream = s.outputStream
                    running = true
                    Thread({ readLoop() }, "spp-read").start()
                    Log.i(TAG, "Connected as CLIENT to ${device.address}")
                    closeServerThread()
                    return true
                } else {
                    try { s.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client connection failed: ${e.message}")
        }
        return false
    }

    private fun closeServerThread() {
        serverThread?.interrupt()
        serverThread = null
    }

    @SuppressLint("MissingPermission")
    private fun attemptReconnect() {
        val dev = device ?: return
        if (!autoReconnect) return

        for (attempt in 1..MAX_RETRIES) {
            if (!autoReconnect) return
            val delay = BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4))
            Log.i(TAG, "Reconnect attempt $attempt/$MAX_RETRIES in ${delay}ms")
            try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
            if (!autoReconnect) return

            try {
                val s = dev.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                synchronized(this) {
                    connected = true
                    socket = s
                    inputStream = s.inputStream
                    outputStream = s.outputStream
                    running = true
                }
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
        connected = false
        autoReconnect = false
        closeServerThread()
        reconnectThread?.interrupt()
        reconnectThread = null
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
                onData(received)
            }
        } catch (e: IOException) {
            if (running) Log.e(TAG, "Read error", e)
        } finally {
            val wasRunning = running
            running = false
            connected = false
            try { socket?.close() } catch (_: IOException) {}
            if (wasRunning && autoReconnect) {
                reconnectThread = Thread({ attemptReconnect() }, "spp-reconnect")
                reconnectThread?.start()
            } else if (wasRunning) {
                onDisconnected()
            }
        }
    }
}
