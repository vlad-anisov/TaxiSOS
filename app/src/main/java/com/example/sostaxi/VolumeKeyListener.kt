package com.example.sostaxi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Слушатель изменений громкости для перехвата нажатий Bluetooth-кнопок,
 * которые эмулируют кнопки громкости.
 * 
 * Использует паттерн "двойного быстрого нажатия" для избежания ложных срабатываний
 * при использовании физических кнопок громкости устройства.
 */
class VolumeKeyListener(private val context: Context, private val onVolumeIncrease: () -> Unit) {

    private val TAG = "VolumeKeyListener"
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var baseVolume: Int = -1
    private var isListening = false
    
    // Паттерн "двойного нажатия"
    private var lastPressTime: Long = 0
    private var pressCount: Int = 0
    private val DOUBLE_PRESS_INTERVAL = 500L // 500мс между нажатиями
    private val resetHandler = Handler(Looper.getMainLooper())
    private var resetRunnable: Runnable? = null

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                
                // Инициализация базовой громкости
                if (baseVolume == -1) {
                    baseVolume = currentVolume
                    Log.d(TAG, "Базовая громкость установлена: $baseVolume")
                    return
                }
                
                Log.v(TAG, "Громкость: $baseVolume -> $currentVolume")

                // Проверяем, было ли это увеличение громкости
                if (currentVolume > baseVolume) {
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastPress = currentTime - lastPressTime
                    
                    Log.d(TAG, "↑ Увеличение громкости обнаружено (интервал: ${timeSinceLastPress}мс)")
                    
                    // Отменяем предыдущий таймер сброса
                    resetRunnable?.let { resetHandler.removeCallbacks(it) }
                    
                    // Если это быстрое повторное нажатие
                    if (timeSinceLastPress < DOUBLE_PRESS_INTERVAL && pressCount > 0) {
                        pressCount++
                        Log.d(TAG, "⚡ Нажатие #$pressCount (интервал: ${timeSinceLastPress}мс)")
                        
                        // Второе нажатие - срабатываем!
                        if (pressCount >= 2) {
                            val prefs = context?.getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                            val isEnabled = prefs?.getBoolean("bluetooth_button_enabled", false) ?: false

                            if (isEnabled) {
                                Log.d(TAG, "✓ ДВОЙНОЕ НАЖАТИЕ ОБНАРУЖЕНО! Вызываем действие")
                                onVolumeIncrease.invoke()
                            } else {
                                Log.d(TAG, "✗ Функция отключена")
                            }
                            
                            // Сбрасываем счетчик
                            pressCount = 0
                            lastPressTime = 0
                        }
                    } else {
                        // Первое нажатие или слишком долгий интервал
                        pressCount = 1
                        lastPressTime = currentTime
                        Log.d(TAG, "⚡ Первое нажатие, ожидаем второго в течение ${DOUBLE_PRESS_INTERVAL}мс")
                        
                        // Устанавливаем таймер сброса
                        resetRunnable = Runnable {
                            if (pressCount > 0) {
                                Log.d(TAG, "⏱ Таймаут двойного нажатия, сброс счетчика")
                                pressCount = 0
                                lastPressTime = 0
                            }
                        }
                        resetHandler.postDelayed(resetRunnable!!, DOUBLE_PRESS_INTERVAL)
                    }
                    
                    // Возвращаем громкость на базовый уровень
                    if (currentVolume != baseVolume) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, baseVolume, 0)
                        Log.v(TAG, "Громкость восстановлена: $currentVolume -> $baseVolume")
                    }
                } else if (currentVolume < baseVolume) {
                    // Уменьшение громкости - обновляем базовый уровень
                    Log.v(TAG, "↓ Уменьшение громкости: $baseVolume -> $currentVolume")
                    baseVolume = currentVolume
                }
            }
        }
    }

    fun startListening() {
        if (!isListening) {
            Log.d(TAG, "Начинаем прослушивание изменений громкости (режим: двойное нажатие)")
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            context.registerReceiver(volumeChangeReceiver, filter)
            baseVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            pressCount = 0
            lastPressTime = 0
            isListening = true
            Log.d(TAG, "✓ Receiver зарегистрирован, базовая громкость: $baseVolume")
            Log.d(TAG, "ℹ Для активации: быстро нажмите кнопку 2 раза (интервал < ${DOUBLE_PRESS_INTERVAL}мс)")
        }
    }

    fun stopListening() {
        if (isListening) {
            Log.d(TAG, "Останавливаем прослушивание изменений громкости")
            context.unregisterReceiver(volumeChangeReceiver)
            resetRunnable?.let { resetHandler.removeCallbacks(it) }
            isListening = false
            baseVolume = -1
            pressCount = 0
            lastPressTime = 0
            Log.d(TAG, "✓ Receiver отменен")
        }
    }
}
