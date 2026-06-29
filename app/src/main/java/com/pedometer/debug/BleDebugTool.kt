package com.pedometer.debug

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleDebugTool(private val context: Context) {
    companion object {
        private const val TAG = "BleDebugTool"

        // Known Xiaomi service
        val SERVICE_FE95 = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
        // CCC descriptor
        val CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    data class GattEntry(
        val serviceUuid: String,
        val charUuid: String,
        val properties: Int,
        val handle: String,
    ) {
        val propsStr: String get() {
            val p = mutableListOf<String>()
            if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) p.add("R")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) p.add("W")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) p.add("WNR")
            if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) p.add("N")
            if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) p.add("I")
            return p.joinToString(",")
        }
    }

    private var gatt: BluetoothGatt? = null
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _services = MutableStateFlow<List<GattEntry>>(emptyList())
    val services: StateFlow<List<GattEntry>> = _services

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _log.value = _log.value + msg
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected! Requesting MTU 512...")
                    gatt.requestMtu(512)
                    _connected.value = true
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected (status=$status)")
                    _connected.value = false
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU=$mtu, discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery FAILED: $status")
                return
            }

            val entries = mutableListOf<GattEntry>()
            for (service in gatt.services) {
                log("Service: ${service.uuid}")
                for (char in service.characteristics) {
                    val entry = GattEntry(
                        serviceUuid = service.uuid.toString().take(8),
                        charUuid = char.uuid.toString().take(8),
                        properties = char.properties,
                        handle = char.instanceId.toString(),
                    )
                    entries.add(entry)
                    log("  Char: ${char.uuid} [${entry.propsStr}]")
                }
            }
            _services.value = entries
            log("Found ${gatt.services.size} services, ${entries.size} characteristics")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("READ ${characteristic.uuid.toString().take(8)}: ${value.toHex()}")
            } else {
                log("READ FAILED ${characteristic.uuid.toString().take(8)}: status=$status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("WRITE OK ${characteristic.uuid.toString().take(8)}")
            } else {
                log("WRITE FAILED ${characteristic.uuid.toString().take(8)}: status=$status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            log("NOTIFY ${characteristic.uuid.toString().take(8)}: ${value.toHex()}")
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            log("NOTIFY ${characteristic.uuid.toString().take(8)}: ${value.toHex()}")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            log("Notifications ${if (status == BluetoothGatt.GATT_SUCCESS) "enabled" else "FAILED"}")
        }
    }

    fun connect(mac: String) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(mac)
        log("Connecting to $mac via BLE...")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_AUTO)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connected.value = false
        log("Disconnected")
    }

    fun readChar(serviceUuid: String, charUuid: String) {
        val g = gatt ?: return log("Not connected")
        val service = g.services.find { it.uuid.toString().startsWith(serviceUuid) }
            ?: return log("Service $serviceUuid not found")
        val char = service.characteristics.find { it.uuid.toString().startsWith(charUuid) }
            ?: return log("Char $charUuid not found")
        g.readCharacteristic(char)
        log("Reading $charUuid...")
    }

    fun writeChar(serviceUuid: String, charUuid: String, data: ByteArray) {
        val g = gatt ?: return log("Not connected")
        val service = g.services.find { it.uuid.toString().startsWith(serviceUuid) }
            ?: return log("Service $serviceUuid not found")
        val char = service.characteristics.find { it.uuid.toString().startsWith(charUuid) }
            ?: return log("Char $charUuid not found")
        char.value = data
        char.writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        g.writeCharacteristic(char)
        log("Writing ${data.toHex()} to $charUuid...")
    }

    fun enableNotify(serviceUuid: String, charUuid: String) {
        val g = gatt ?: return log("Not connected")
        val service = g.services.find { it.uuid.toString().startsWith(serviceUuid) }
            ?: return log("Service $serviceUuid not found")
        val char = service.characteristics.find { it.uuid.toString().startsWith(charUuid) }
            ?: return log("Char $charUuid not found")
        g.setCharacteristicNotification(char, true)
        val desc = char.getDescriptor(CCC)
        if (desc != null) {
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(desc)
        }
        log("Enabling notifications on $charUuid...")
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
