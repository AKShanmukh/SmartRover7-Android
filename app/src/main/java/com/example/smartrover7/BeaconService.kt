package com.example.smartrover7

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID

class BeaconService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val CHANNEL = "SmartRoverBeacon"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "BeaconService"

        val BEACON_UUID: UUID = UUID.fromString("12345678-1234-5678-1234-567812345678")
    }

    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            isAdvertising = true
            Log.d(TAG, "BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            isAdvertising = false
            Log.e(TAG, "BLE Advertising failed: $errorCode")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothLeAdvertiser = bluetoothManager?.adapter?.bluetoothLeAdvertiser
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                startAdvertising()
            }
            ACTION_STOP -> {
                stopAdvertising()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("🤖 Smart Rover Active")
            .setContentText("Your rover is following you")
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // THIS IS THE FIX: The extra comma has been removed.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startAdvertising() {
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "Advertiser is null. Check if Bluetooth is supported/on.")
            return
        }

        if (isAdvertising) {
            Log.w(TAG, "Already advertising")
            return
        }

        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false) // Set to false to avoid packet size issues
                .addServiceUuid(ParcelUuid(BEACON_UUID))
                .build()

            bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d(TAG, "Started BLE advertising")

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception when starting advertising: ${e.message}")
        }
    }

    private fun stopAdvertising() {
        try {
            if (isAdvertising) {
                bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                Log.d(TAG, "Stopped BLE advertising")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception when stopping advertising: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL,
                "Smart Rover Beacon",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAdvertising()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
