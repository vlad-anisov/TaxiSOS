package com.example.sostaxi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

/**
 * BroadcastReceiver для обработки нажатий на медиа-кнопки (включая Bluetooth-кнопки)
 */
class MediaButtonReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "MediaButtonReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Получен интент: ${intent.action}")
        
        if (Intent.ACTION_MEDIA_BUTTON == intent.action) {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            
            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "Нажата медиа-кнопка: keyCode=${keyEvent.keyCode}")
                
                // Проверяем, включена ли обработка Bluetooth-кнопки в настройках
                val prefs = context.getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                val isEnabled = prefs.getBoolean("bluetooth_button_enabled", false)
                
                if (!isEnabled) {
                    Log.d(TAG, "Обработка Bluetooth-кнопки отключена в настройках")
                    return
                }
                
                // Обрабатываем различные типы нажатий
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                        Log.d(TAG, "Обработка нажатия кнопки воспроизведения/паузы")
                        handleButtonPress(context)
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        Log.d(TAG, "Обработка нажатия кнопки Next")
                        handleButtonPress(context)
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        Log.d(TAG, "Обработка нажатия кнопки Previous")
                        handleButtonPress(context)
                    }
                    else -> {
                        Log.d(TAG, "Неизвестная медиа-кнопка: ${keyEvent.keyCode}")
                    }
                }
            }
        }
    }
    
    private fun handleButtonPress(context: Context) {
        // Получаем настройки
        val prefs = context.getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
        val selectedAction = prefs.getString("bluetooth_button_action", "VIDEO_SEGMENTS") ?: "VIDEO_SEGMENTS"
        
        Log.d(TAG, "Выбранное действие: $selectedAction")
        
        // Проверяем, активно ли приложение
        if (MainActivity.isActive) {
            Log.d(TAG, "Приложение уже активно, останавливаем")
            // Если приложение активно, останавливаем его
            stopApp(context)
        } else {
            Log.d(TAG, "Приложение неактивно, запускаем с режимом: $selectedAction")
            // Если приложение неактивно, запускаем его с выбранным режимом
            startApp(context, selectedAction)
        }
    }
    
    private fun startApp(context: Context, mode: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("quick_tile_mode", mode)
            putExtra("auto_start", true)
            putExtra("from_bluetooth_button", true)
        }
        
        try {
            context.startActivity(intent)
            Log.d(TAG, "Активность запущена успешно")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска активности: ${e.message}")
        }
    }
    
    private fun stopApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("quick_tile_action", "stop_and_close")
            putExtra("from_bluetooth_button", true)
        }
        
        try {
            context.startActivity(intent)
            Log.d(TAG, "Команда остановки отправлена успешно")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки команды остановки: ${e.message}")
        }
    }
}

