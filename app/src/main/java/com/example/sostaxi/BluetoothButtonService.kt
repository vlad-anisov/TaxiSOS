package com.example.sostaxi

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service для обработки нажатий Bluetooth-кнопки в фоне
 */
class BluetoothButtonService : Service() {
    
    companion object {
        private const val TAG = "BluetoothButtonService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "bluetooth_button_channel"
        
        /**
         * Запуск сервиса
         */
        fun start(context: Context) {
            val intent = Intent(context, BluetoothButtonService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Остановка сервиса
         */
        fun stop(context: Context) {
            val intent = Intent(context, BluetoothButtonService::class.java)
            context.stopService(intent)
        }
    }
    
      private var bluetoothButtonManager: BluetoothButtonManager? = null
      private var bleButtonManager: BleButtonManager? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        // Создаем notification channel для Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Button Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Сервис для обработки нажатий Bluetooth-кнопки"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        
        // Запускаем сервис как foreground
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Инициализируем BluetoothButtonManager (для стандартных медиа-кнопок)
        bluetoothButtonManager = BluetoothButtonManager(this)
        bluetoothButtonManager?.initialize()
        bluetoothButtonManager?.setActive(true)
        
          // Инициализируем BLE кнопку, если выбрано устройство в настройках
          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
              val hasConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
              if (!hasConnect) {
                  Log.e(TAG, "BLUETOOTH_CONNECT permission missing; BLE button is disabled until granted")
              }
          }
          val prefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
          val bleAddr = prefs.getString("ble_device_address", null) // ИСПРАВЛЕН КЛЮЧ!
          val bleOnly = prefs.getBoolean("ble_only_mode", false)
          if (!bleAddr.isNullOrEmpty()) {
              bleButtonManager = BleButtonManager(this) {
                  handleBleButtonPress()
              }
              if (bleButtonManager?.initialize() == true) {
                  bleButtonManager?.start()
                  Log.d(TAG, "BLE button listening started")
              } else {
                  Log.w(TAG, "BLE not supported or disabled")
              }
          } else {
              Log.d(TAG, "No BLE button configured")
          }

        // VolumeKeyListener ОТКЛЮЧЕН - теперь используется AccessibilityService
        // который отслеживает открытие окна громкости (TYPE_WINDOW_STATE_CHANGED)
        // и может отличить Bluetooth-кнопку от физических кнопок устройства
        
        Log.d(TAG, "Service initialized (MediaSession активен, VolumeKeyListener отключен)")
        Log.d(TAG, "ℹ Обработка Bluetooth-кнопки теперь в AccessibilityService")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        
        // Освобождаем ресурсы
        bluetoothButtonManager?.release()
        bluetoothButtonManager = null

          try {
              bleButtonManager?.stop()
          } catch (_: Exception) {}
          bleButtonManager = null
        
        super.onDestroy()
    }

      private fun handleBleButtonPress() {
          Log.d(TAG, "BLE button press → forwarding to MainActivity")
          val prefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
          val selectedAction = prefs.getString("bluetooth_button_action", "VIDEO_SEGMENTS") ?: "VIDEO_SEGMENTS"

          val intent = Intent(this, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                      Intent.FLAG_ACTIVITY_CLEAR_TOP or
                      Intent.FLAG_ACTIVITY_SINGLE_TOP
              if (MainActivity.isActive) {
                  putExtra("quick_tile_action", "stop_and_close")
              } else {
                  putExtra("quick_tile_mode", selectedAction)
                  putExtra("auto_start", true)
              }
              putExtra("from_ble_button", true)
          }
          startActivity(intent)
      }
    
    /**
     * Создание уведомления для foreground service
     */
    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SOS Taxi")
            .setContentText("Bluetooth-кнопка активна")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}

