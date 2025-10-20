package com.example.sostaxi

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent

/**
 * Менеджер для управления Bluetooth-кнопкой через MediaSession
 */
class BluetoothButtonManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothButtonManager"
        private const val MEDIA_SESSION_TAG = "SOSTaxiMediaSession"
    }
    
    private var mediaSession: MediaSession? = null
    
    /**
     * Инициализация MediaSession для обработки медиа-кнопок
     */
    fun initialize() {
        try {
            Log.d(TAG, "Инициализация MediaSession...")
            
            // Создаем MediaSession
            mediaSession = MediaSession(context, MEDIA_SESSION_TAG).apply {
                // Устанавливаем callback для обработки медиа-событий
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        Log.d(TAG, "onMediaButtonEvent вызван")
                        
                        if (mediaButtonIntent.action == Intent.ACTION_MEDIA_BUTTON) {
                            val keyEvent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                            }
                            
                            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                                Log.d(TAG, "Получено нажатие медиа-кнопки: ${keyEvent.keyCode}")
                                handleMediaButton(keyEvent)
                                return true
                            }
                        }
                        
                        return super.onMediaButtonEvent(mediaButtonIntent)
                    }
                    
                    override fun onPlay() {
                        Log.d(TAG, "onPlay вызван")
                        handleButtonPress()
                    }
                    
                    override fun onPause() {
                        Log.d(TAG, "onPause вызван")
                        handleButtonPress()
                    }
                    
                    override fun onStop() {
                        Log.d(TAG, "onStop вызван")
                        handleButtonPress()
                    }
                })
                
                // Устанавливаем флаги для обработки медиа-кнопок
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or 
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                
                // Устанавливаем состояние воспроизведения
                setPlaybackState(
                    PlaybackState.Builder()
                        .setState(PlaybackState.STATE_STOPPED, 0, 1.0f)
                        .setActions(
                            PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_STOP
                        )
                        .build()
                )
                
                // Регистрируем MediaButtonReceiver
                val mediaButtonReceiver = ComponentName(context, MediaButtonReceiver::class.java)
                val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                    component = mediaButtonReceiver
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    mediaButtonIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setMediaButtonReceiver(pendingIntent)
                
                // Активируем сессию
                isActive = true
            }
            
            Log.d(TAG, "MediaSession инициализирована успешно")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации MediaSession: ${e.message}", e)
        }
    }
    
    /**
     * Обработка нажатия медиа-кнопки
     */
    private fun handleMediaButton(keyEvent: KeyEvent) {
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                handleButtonPress()
            }
        }
    }
    
    /**
     * Обработка нажатия кнопки
     */
    private fun handleButtonPress() {
        // Проверяем, включена ли обработка Bluetooth-кнопки в настройках
        val prefs = context.getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("bluetooth_button_enabled", false)
        
        if (!isEnabled) {
            Log.d(TAG, "Обработка Bluetooth-кнопки отключена")
            return
        }
        
        val selectedAction = prefs.getString("bluetooth_button_action", "VIDEO_SEGMENTS") ?: "VIDEO_SEGMENTS"
        
        Log.d(TAG, "Обработка нажатия, режим: $selectedAction, активно: ${MainActivity.isActive}")
        
        // Обновляем состояние воспроизведения
        updatePlaybackState()
        
        if (MainActivity.isActive) {
            // Если приложение активно, останавливаем
            stopApp()
        } else {
            // Если приложение неактивно, запускаем
            startApp(selectedAction)
        }
    }
    
    /**
     * Запуск приложения с выбранным режимом
     */
    private fun startApp(mode: String) {
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
            Log.d(TAG, "Приложение запущено")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска приложения: ${e.message}")
        }
    }
    
    /**
     * Остановка приложения
     */
    private fun stopApp() {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("quick_tile_action", "stop_and_close")
            putExtra("from_bluetooth_button", true)
        }
        
        try {
            context.startActivity(intent)
            Log.d(TAG, "Команда остановки отправлена")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки приложения: ${e.message}")
        }
    }
    
    /**
     * Обновление состояния воспроизведения
     */
    private fun updatePlaybackState() {
        val state = if (MainActivity.isActive) {
            PlaybackState.STATE_PLAYING
        } else {
            PlaybackState.STATE_STOPPED
        }
        
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setState(state, 0, 1.0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_STOP
                )
                .build()
        )
    }
    
    /**
     * Освобождение ресурсов
     */
    fun release() {
        try {
            Log.d(TAG, "Освобождение MediaSession...")
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            Log.d(TAG, "MediaSession освобождена")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка освобождения MediaSession: ${e.message}")
        }
    }
    
    /**
     * Активация/деактивация сессии
     */
    fun setActive(active: Boolean) {
        try {
            mediaSession?.isActive = active
            updatePlaybackState()
            Log.d(TAG, "MediaSession ${if (active) "активирована" else "деактивирована"}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка изменения состояния MediaSession: ${e.message}")
        }
    }
}
