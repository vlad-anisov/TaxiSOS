package com.example.sostaxi

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * BLE менеджер: подключается к выбранному пользователем BLE-устройству (брелок-кнопка),
 * подписывается на уведомления характеристик и вызывает callback при нажатии.
 *
 * Подход «режим обучения»: если в настройках включен learn-mode, то первая характеристика,
 * давшая уведомление, сохраняется как целевая (UUID) и используется далее для фильтрации.
 */
class BleButtonManager(
    private val context: Context,
    private val onButtonPress: () -> Unit
) {
    private val tag = "BleButtonManager"

    private val prefs by lazy {
        context.getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var reconnectAttempts = 0

    fun initialize(): Boolean {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        val supported = bluetoothAdapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        Log.d(tag, "BLE supported=$supported")
        return supported
    }

    fun start() {
        val deviceAddress = prefs.getString(KEY_DEVICE_ADDRESS, null)
        if (deviceAddress.isNullOrEmpty()) {
            Log.w(tag, "No BLE device selected; skipping start")
            return
        }
        // Сбрасываем флаг активности нотификаций до успешной подписки
        prefs.edit().putBoolean(KEY_NOTIFY_ACTIVE, false).apply()
        if (bluetoothAdapter == null && !initialize()) return
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            Log.e(tag, "Device not found by address $deviceAddress")
            return
        }
        connect(device)
    }

    fun stop() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Throwable) {}
        bluetoothGatt = null
        reconnectAttempts = 0
        prefs.edit().putBoolean(KEY_NOTIFY_ACTIVE, false).apply()
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        try {
            Log.d(tag, "Connecting to ${device.address} ...")
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            Log.e(tag, "Missing Bluetooth permissions: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Gatt connect error: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(tag, "onConnectionStateChange status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectAttempts = 0
                
                // КРИТИЧЕСКИ ВАЖНО: Обновляем флаг подключения для VolumeButtonAccessibilityService
                prefs.edit().putBoolean("ble_device_connected", true).apply()
                Log.d(tag, "✅ BLE device connected, flag updated to TRUE")
                
                try {
                    handler.post { gatt.discoverServices() }
                } catch (_: Throwable) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bluetoothGatt = null
                
                // КРИТИЧЕСКИ ВАЖНО: Сбрасываем флаг подключения
                prefs.edit()
                    .putBoolean(KEY_NOTIFY_ACTIVE, false)
                    .putBoolean("ble_device_connected", false)
                    .apply()
                Log.d(tag, "❌ BLE device disconnected, flag updated to FALSE")
                
                scheduleReconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(tag, "Services discovered, status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val targetChar = prefs.getString(KEY_BUTTON_CHAR_UUID, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val learnMode = prefs.getBoolean(KEY_LEARN_MODE, false)

            // Подписываемся: если заранее известна характеристика — только на неё; иначе на все нотифицируемые
            for (service in gatt.services) {
                for (ch in service.characteristics) {
                    val props = ch.properties
                    val notifiable = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                            (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    if (!notifiable) continue

                    val shouldEnable = when {
                        targetChar != null -> ch.uuid == targetChar
                        else -> true // learn or broad listen
                    }
                    if (shouldEnable) enableNotifications(gatt, ch)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            Log.d(tag, "Notification from ${characteristic.uuid}")
            val targetChar = prefs.getString(KEY_BUTTON_CHAR_UUID, null)
            val learnMode = prefs.getBoolean(KEY_LEARN_MODE, false)

            if (learnMode) {
                // Сохраняем UUID первой пришедшей характеристики
                prefs.edit().putString(KEY_BUTTON_CHAR_UUID, characteristic.uuid.toString()).putBoolean(KEY_LEARN_MODE, false).apply()
                Log.d(tag, "Learned button characteristic: ${characteristic.uuid}")
                onButtonPressSafe()
                return
            }

            if (targetChar == null || characteristic.uuid.toString().equals(targetChar, ignoreCase = true)) {
                onButtonPressSafe()
            } else {
                Log.v(tag, "Notification ignored (not target characteristic)")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(ch, true)
            val ccc = ch.getDescriptor(UUID.fromString(CCC_DESCRIPTOR_UUID))
            if (ccc != null) {
                ccc.value = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(ccc)
                Log.d(tag, "Notifications enabled for ${ch.uuid}")
                // Отмечаем, что подписка активирована хотя бы для одной характеристики
                prefs.edit().putBoolean(KEY_NOTIFY_ACTIVE, true).apply()
            } else {
                Log.w(tag, "CCC descriptor missing for ${ch.uuid}")
            }
        } catch (e: SecurityException) {
            Log.e(tag, "Missing Bluetooth permissions: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Enable notify error: ${e.message}")
        }
    }

    private fun onButtonPressSafe() {
        try {
            onButtonPress.invoke()
        } catch (e: Exception) {
            Log.e(tag, "onButtonPress error: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        val deviceAddress = prefs.getString(KEY_DEVICE_ADDRESS, null)
        val adapter = bluetoothAdapter ?: return
        if (deviceAddress.isNullOrEmpty()) return
        if (reconnectAttempts > 5) {
            Log.w(tag, "Reconnect attempts exceeded")
            return
        }
        reconnectAttempts++
        handler.postDelayed({
            try {
                val device = adapter.getRemoteDevice(deviceAddress)
                connect(device)
            } catch (_: Throwable) {}
        }, 2000L * reconnectAttempts)
    }

    companion object {
        private const val KEY_DEVICE_ADDRESS = "ble_device_address" // ИСПРАВЛЕН КЛЮЧ!
        private const val KEY_BUTTON_CHAR_UUID = "ble_characteristic_uuid"
        private const val KEY_LEARN_MODE = "ble_learn_mode"
        private const val KEY_NOTIFY_ACTIVE = "ble_notify_active"
        private const val CCC_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}


