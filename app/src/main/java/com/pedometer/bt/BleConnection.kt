package com.pedometer.bt

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission")
class BleConnection(
    private val context: Context,
    private val onData: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    companion object {
        private const val TAG = "BleConnection"

        val SERVICE_UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")

        // BLE V1 characteristics
        val CHAR_CMD_READ = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb")
        val CHAR_CMD_WRITE = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb")

        // BLE V2 characteristics
        val CHAR_V2_RX = UUID.fromString("0000005e-0000-1000-8000-00805f9b34fb")
        val CHAR_V2_TX = UUID.fromString("0000005f-0000-1000-8000-00805f9b34fb")

        // CCC descriptor for notifications
        val CCC_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var useV2 = false
    @Volatile var isConnected = false
        private set

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected, discovering services...")
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "GATT disconnected, status=$status")
                    isConnected = false
                    onDisconnected()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu, status=$status")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Xiaomi service not found!")
                return
            }

            Log.i(TAG, "Found Xiaomi service, characteristics:")
            for (c in service.characteristics) {
                Log.i(TAG, "  char: ${c.uuid}")
            }

            // Try V2 first, fallback to V1
            val v2Rx = service.getCharacteristic(CHAR_V2_RX)
            val v2Tx = service.getCharacteristic(CHAR_V2_TX)

            if (v2Rx != null && v2Tx != null) {
                Log.i(TAG, "Using BLE V2 (chars 005e/005f)")
                useV2 = true
                writeChar = v2Tx
                enableNotifications(gatt, v2Rx)
            } else {
                val cmdRead = service.getCharacteristic(CHAR_CMD_READ)
                val cmdWrite = service.getCharacteristic(CHAR_CMD_WRITE)
                if (cmdRead != null && cmdWrite != null) {
                    Log.i(TAG, "Using BLE V1 (chars 0051/0052)")
                    useV2 = false
                    writeChar = cmdWrite
                    enableNotifications(gatt, cmdRead)
                } else {
                    Log.e(TAG, "No known characteristics found!")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "Received ${value.size} bytes on ${characteristic.uuid}")
            onData(value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            Log.d(TAG, "Received ${value.size} bytes on ${characteristic.uuid} (legacy)")
            onData(value)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Notifications enabled, connection ready")
                isConnected = true
            } else {
                Log.e(TAG, "Failed to enable notifications: $status")
            }
        }
    }

    fun connect(device: BluetoothDevice): Boolean {
        Log.i(TAG, "Connecting to ${device.address} via BLE GATT...")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        return gatt != null
    }

    fun write(data: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return

        c.value = data
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (!g.writeCharacteristic(c)) {
            Log.e(TAG, "Write failed!")
        }
    }

    fun disconnect() {
        isConnected = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeChar = null
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        } else {
            Log.w(TAG, "CCC descriptor not found, notifications may not work")
            isConnected = true
        }
    }
}
