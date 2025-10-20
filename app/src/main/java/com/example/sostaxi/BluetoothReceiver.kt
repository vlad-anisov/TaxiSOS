package com.example.sostaxi

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Ресивер для событий подключения/отключения Bluetooth-устройств.
 * Автозапускает/останавливает сервис кнопки для выбранного пользователем адреса.
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device: BluetoothDevice? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        } catch (_: SecurityException) { null }

        val address = device?.address ?: return
        val prefs = context.getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
        val selectedAddr = prefs.getString("ble_device_address", null)
        
        Log.d("BluetoothReceiver", "Bluetooth event: action=$action, device=$address, selected=$selectedAddr")
        
        if (selectedAddr.isNullOrEmpty() || !selectedAddr.equals(address, ignoreCase = true)) {
            Log.v("BluetoothReceiver", "Not the selected device, ignoring")
            return
        }

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.d("BluetoothReceiver", "Selected device connected: $address → start service")
                context.getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("ble_device_connected", true).apply()
                BluetoothButtonService.start(context)
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.d("BluetoothReceiver", "Selected device disconnected: $address → stop service")
                context.getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("ble_device_connected", false).apply()
                BluetoothButtonService.stop(context)
            }
        }
    }
}


