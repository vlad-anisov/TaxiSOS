package com.example.sostaxi

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class VideoSegmentsTileService : TileService() {
    
    companion object {
        private const val TILE_STATE_PREFS = "video_segments_tile_state"
        private const val IS_ACTIVE_KEY = "is_active"
        
        fun getTileState(context: Context): Boolean {
            val prefs = context.getSharedPreferences(TILE_STATE_PREFS, Context.MODE_PRIVATE)
            return prefs.getBoolean(IS_ACTIVE_KEY, false)
        }
        
        fun setTileState(context: Context, isActive: Boolean) {
            val prefs = context.getSharedPreferences(TILE_STATE_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(IS_ACTIVE_KEY, isActive).apply()
        }
    }
    
    override fun onStartListening() {
        super.onStartListening()
        // Применим текущую локаль для контекста сервиса перед обновлением тайла
        LanguageManager.applyLanguage(this)
        updateTile()
    }
    
    override fun onClick() {
        super.onClick()
        
        val isActive = getTileState(this)
        
        if (!isActive) {
            // Запускаем запись видео сегментов
            startVideoSegmentsRecording()
        } else {
            // Останавливаем запись и закрываем приложение
            stopVideoSegmentsRecording()
        }
    }
    
    private fun startVideoSegmentsRecording() {
        // Устанавливаем режим VIDEO_SEGMENTS
        Log.d("VideoSegmentsTileService", "Запускаем приложение в режиме VIDEO_SEGMENTS")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("quick_tile_mode", "VIDEO_SEGMENTS")
            putExtra("auto_start", true)
            
            // Добавляем дополнительные данные для Android 14+
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra("from_tile_service", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
        }

        // Создаем ActivityOptions для СОЗДАТЕЛЯ (creator) PendingIntent на Android 14+
        val creatorOptionsBundle = if (Build.VERSION.SDK_INT >= 34) {
            try {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    setLaunchDisplayId(0)
                }.toBundle()
            } catch (e: Exception) {
                Log.w("VideoSegmentsTileService", "setPendingIntentCreatorBackgroundActivityStartMode недоступен: ${e.message}")
                try {
                    ActivityOptions.makeBasic().apply {
                        setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }.toBundle()
                } catch (e2: Exception) {
                    Log.w("VideoSegmentsTileService", "Базовые ActivityOptions для creator недоступны: ${e2.message}")
                    ActivityOptions.makeBasic().toBundle()
                }
            }
        } else {
            null
        }

        // Создаем PendingIntent, передавая creator options для Android 14+
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(), // Уникальный request code
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptionsBundle
        )

        // Пытаемся использовать привилегированный способ запуска из TileService
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(pendingIntent)
                Log.d("VideoSegmentsTileService", "startActivityAndCollapse (PI) выполнен успешно")
                setTileState(this, true)
                updateTile()
                return
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                startActivityAndCollapse(intent)
                Log.d("VideoSegmentsTileService", "startActivityAndCollapse (Intent) выполнен успешно")
                setTileState(this, true)
                updateTile()
                return
            }
        } catch (e: Exception) {
            Log.w("VideoSegmentsTileService", "startActivityAndCollapse не сработал: ${e.message}")
        }
        
        // Создаем ActivityOptions для ОТПРАВИТЕЛЯ (sender), а не создателя (creator)
        val senderActivityOptions = if (Build.VERSION.SDK_INT >= 34) { // Android 14+
            try {
                ActivityOptions.makeBasic().apply {
                    // ПРАВИЛЬНЫЙ метод для отправителя PendingIntent
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    // Дополнительные опции для Android 14+
                    setLaunchDisplayId(0) // Primary display
                }
            } catch (e: Exception) {
                Log.w("VideoSegmentsTileService", "setPendingIntentBackgroundActivityStartMode недоступен: ${e.message}")
                try {
                    ActivityOptions.makeBasic().apply {
                        setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }
                } catch (e2: Exception) {
                    Log.w("VideoSegmentsTileService", "Базовые ActivityOptions недоступны: ${e2.message}")
                    ActivityOptions.makeBasic()
                }
            }
        } else {
            null
        }
        
        try {
            // Используем правильный метод для отправителя
            if (Build.VERSION.SDK_INT >= 34) {
                // Отправляем PendingIntent с ActivityOptions для sender
                pendingIntent.send(
                    this, // context
                    0, // code
                    null, // intent
                    null, // onFinished
                    null, // handler
                    null, // requiredPermission
                    senderActivityOptions?.toBundle() // options для ОТПРАВИТЕЛЯ
                )
                Log.d("VideoSegmentsTileService", "PendingIntent отправлен с правильными sender ActivityOptions")
            } else {
                pendingIntent.send()
            }
            Log.d("VideoSegmentsTileService", "PendingIntent успешно отправлен")
        } catch (e: Exception) {
            Log.e("VideoSegmentsTileService", "Ошибка запуска активности через PendingIntent: ${e.message}")
            // Fallback для старых версий Android или в случае неудачи
            if (Build.VERSION.SDK_INT < 34) {
                try {
                    startActivity(intent)
                    Log.d("VideoSegmentsTileService", "Использован fallback startActivity")
                } catch (e2: Exception) {
                    Log.e("VideoSegmentsTileService", "Fallback также не сработал: ${e2.message}")
                }
            }
        }
        
        // Обновляем состояние плитки
        setTileState(this, true)
        updateTile()
    }
    
    private fun stopVideoSegmentsRecording() {
        // Останавливаем приложение
        val intent = Intent(this, MainActivity::class.java).apply {
            // ВАЖНО: не очищаем задачу, чтобы интент пришел в существующий экземпляр
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("quick_tile_action", "stop_and_close")
            
            // Добавляем дополнительные данные для Android 14+
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra("from_tile_service", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
        }

        // Сбрасываем состояние плитки сразу
        setTileState(this, false)
        updateTile()
        
        // Создаем ActivityOptions для СОЗДАТЕЛЯ (creator) PendingIntent на Android 14+
        val creatorOptionsBundle = if (Build.VERSION.SDK_INT >= 34) {
            try {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    setLaunchDisplayId(0)
                }.toBundle()
            } catch (e: Exception) {
                Log.w("VideoSegmentsTileService", "setPendingIntentCreatorBackgroundActivityStartMode недоступен: ${e.message}")
                try {
                    ActivityOptions.makeBasic().apply {
                        setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }.toBundle()
                } catch (e2: Exception) {
                    Log.w("VideoSegmentsTileService", "Базовые ActivityOptions для creator недоступны: ${e2.message}")
                    ActivityOptions.makeBasic().toBundle()
                }
            }
        } else {
            null
        }

        // Создаем PendingIntent, передавая creator options для Android 14+
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(), // Уникальный request code
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptionsBundle
        )

        // Для Android 14+ попробуем запустить через startActivityAndCollapse(PendingIntent)
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startActivityAndCollapse(pendingIntent)
                Log.d("VideoSegmentsTileService", "startActivityAndCollapse (stop, PI) выполнен успешно")
                return
            } catch (e: Exception) {
                Log.w("VideoSegmentsTileService", "startActivityAndCollapse (stop, PI) не сработал: ${e.message}")
            }
        }
        
        // Создаем ActivityOptions для ОТПРАВИТЕЛЯ (sender), а не создателя (creator)
        val senderActivityOptions = if (Build.VERSION.SDK_INT >= 34) { // Android 14+
            try {
                ActivityOptions.makeBasic().apply {
                    // ПРАВИЛЬНЫЙ метод для отправителя PendingIntent
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    // Дополнительные опции для Android 14+
                    setLaunchDisplayId(0) // Primary display
                }
            } catch (e: Exception) {
                Log.w("VideoSegmentsTileService", "setPendingIntentBackgroundActivityStartMode недоступен: ${e.message}")
                try {
                    ActivityOptions.makeBasic().apply {
                        setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }
                } catch (e2: Exception) {
                    Log.w("VideoSegmentsTileService", "Базовые ActivityOptions недоступны: ${e2.message}")
                    ActivityOptions.makeBasic()
                }
            }
        } else {
            null
        }
        
        try {
            // Используем правильный метод для отправителя
            if (Build.VERSION.SDK_INT >= 34) {
                // Отправляем PendingIntent с ActivityOptions для sender
                pendingIntent.send(
                    this, // context
                    0, // code
                    null, // intent
                    null, // onFinished
                    null, // handler
                    null, // requiredPermission
                    senderActivityOptions?.toBundle() // options для ОТПРАВИТЕЛЯ
                )
                Log.d("VideoSegmentsTileService", "PendingIntent для остановки отправлен с правильными sender ActivityOptions")
            } else {
                pendingIntent.send()
            }
            Log.d("VideoSegmentsTileService", "PendingIntent для остановки успешно отправлен")
        } catch (e: Exception) {
            Log.e("VideoSegmentsTileService", "Ошибка остановки активности через PendingIntent: ${e.message}")
            // Fallback для старых версий Android или в случае неудачи
            if (Build.VERSION.SDK_INT < 34) {
                try {
                    startActivity(intent)
                    Log.d("VideoSegmentsTileService", "Использован fallback startActivity для остановки")
                } catch (e2: Exception) {
                    Log.e("VideoSegmentsTileService", "Fallback для остановки также не сработал: ${e2.message}")
                }
            }
        }
    }
    
    private fun updateTile() {
        val isActive = getTileState(this)
        
        qsTile?.let { tile ->
            tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            
            tile.label = if (isActive) {
                getString(R.string.tile_stop_recording)
            } else {
                getString(R.string.tile_video_segments)
            }
            
            tile.icon = if (isActive) {
                Icon.createWithResource(this, android.R.drawable.ic_media_pause)
            } else {
                Icon.createWithResource(this, android.R.drawable.ic_media_play)
            }
            
            tile.updateTile()
        }
    }
} 