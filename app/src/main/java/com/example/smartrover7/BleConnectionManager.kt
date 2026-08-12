package com.example.smartrover7

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) {

    private val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val DEVICE_NAME = "SMARTROVER"

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var targetDevice: BluetoothDevice? = null

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var keepAliveJob: Job? = null
    private var isConnecting = false

    fun connect() {
        if (_connected.value || gatt != null || isConnecting) return
        if (adapter == null || !adapter!!.isEnabled) {
            _status.value = "Bluetooth OFF"
            return
        }
        if (!hasPerms()) {
            _status.value = "Missing permissions"
            return
        }

        stopScan()
        isConnecting = true
        _status.value = "Scanning for SMARTROVER…"

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val deviceName = result.device.name ?: return
                if (!deviceName.equals(DEVICE_NAME, true)) return

                Log.d("BLE", "✓ SMARTROVER found! Connecting…")
                targetDevice = result.device
                stopScan()

                scope.launch {
                    delay(120)
                    gatt?.close()
                    gatt = null
                    result.device.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _status.value = "Scan failed: $errorCode"
                isConnecting = false
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner = adapter!!.bluetoothLeScanner
        scanner?.startScan(null, settings, scanCallback)

        scanJob = scope.launch(Dispatchers.IO) {
            delay(10_000)
            if (!_connected.value) {
                withContext(Dispatchers.Main) {
                    stopScan()
                    _status.value = "SMARTROVER not found"
                    isConnecting = false
                }
            }
        }
    }

    fun disconnect() {
        stopKeepAlive()
        stopScan()
        isConnecting = false
        _connected.value = false
        _status.value = "Disconnected"
        gatt?.disconnect()
        scope.launch {
            delay(200)
            gatt?.close()
            gatt = null
            rxChar = null
            txChar = null
        }
    }

    fun send(cmd: String) {
        val characteristic = rxChar ?: return
        if (!_connected.value) return

        scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeCharacteristic(
                    characteristic,
                    cmd.toByteArray(),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                characteristic.value = cmd.toByteArray()
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt?.writeCharacteristic(characteristic)
            }
        }
    }

    private fun stopScan() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) { }
        scanCallback = null
        scanJob?.cancel()
        scanJob = null
    }

    private fun hasPerms(): Boolean {
        val perms = buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && _connected.value) {
                delay(2000)
                send("PING_ACK")
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val callback = this  // <-- safe reference for retries

            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    this@BleConnectionManager.gatt = gatt
                    _status.value = "Discovering services…"
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.requestMtu(185)
                    scope.launch {
                        delay(200)
                        gatt.discoverServices()
                    }
                }

                status == 133 -> {
                    Log.e("BLE", "GATT 133, retrying…")
                    scope.launch {
                        delay(1000)
                        gatt.close()
                        this@BleConnectionManager.gatt = null
                        targetDevice?.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    }
                }

                else -> {
                    Log.d("BLE", "Disconnected (state=$newState status=$status)")
                    scope.launch {
                        disconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "MTU set to $mtu")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _status.value = "Discovery failed"
                isConnecting = false
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                _status.value = "Service not found"
                isConnecting = false
                return
            }

            rxChar = service.getCharacteristic(RX_UUID)
            txChar = service.getCharacteristic(TX_UUID)
            if (rxChar == null || txChar == null) {
                _status.value = "Characteristics not found"
                isConnecting = false
                return
            }

            txChar?.let { enableNotifications(gatt, it) }
            _connected.value = true
            isConnecting = false
            startKeepAlive()
            Log.d("BLE", "✓✓✓ FULLY CONNECTED ✓✓✓")
        }

        private fun enableNotifications(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            gatt.setCharacteristicNotification(characteristic, true)   // correct order
            val cccUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val descriptor = characteristic.getDescriptor(cccUuid) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_UUID) {
                val message = value.toString(Charsets.UTF_8)
                if (message != "PING" && message != "PING_ACK") {
                    _status.value = message
                }
            }
        }

        @Deprecated("Use new onCharacteristicChanged")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                characteristic.uuid == TX_UUID) {
                val message = characteristic.value.toString(Charsets.UTF_8)
                if (message != "PING" && message != "PING_ACK") {
                    _status.value = message
                }
            }
        }
    }
}
