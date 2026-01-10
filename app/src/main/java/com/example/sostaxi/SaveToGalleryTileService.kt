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
class SaveToGalleryTileService : TileService() {
    
    companion object {
        private const val TAG = "SaveToGalleryTile"
        private const val TILE_STATE_PREFS = "save_to_gallery_tile_state"
        private const val IS_ACTIVE_KEY = "is_active"
        const val ACTION_TILE_TOGGLE = "com.example.sostaxi.TILE_TOGGLE"
        
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
        LanguageManager.applyLanguage(this)
        updateTile()
    }
    
    override fun onClick() {
        super.onClick()
        
        val isActive = getTileState(this)
        
        if (!isActive) {
            startWithTile()
        } else {
            stopTile()
        }
    }
    
    private fun startWithTile() {
        Log.d(TAG, "Включаем сохранение в галерею")
        setTileState(this, true)
        updateTile()
        
        // Если приложение уже активно - отправляем broadcast (не выводим из PiP)
        if (MainActivity.isActive) {
            Log.d(TAG, "Приложение уже активно, отправляем broadcast")
            val broadcastIntent = Intent(ACTION_TILE_TOGGLE).apply {
                setPackage(packageName)
                putExtra("tile_action", "toggle_save_to_gallery")
                putExtra("tile_value", true)
            }
            sendBroadcast(broadcastIntent)
            return
        }
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("tile_action", "toggle_save_to_gallery")
            putExtra("tile_value", true)
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra("from_tile_service", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
        }
        
        launchActivity(intent)
    }
    
    private fun stopTile() {
        Log.d(TAG, "Отключаем сохранение в галерею")
        setTileState(this, false)
        updateTile()
        
        // Если приложение уже активно - отправляем broadcast (не выводим из PiP)
        if (MainActivity.isActive) {
            Log.d(TAG, "Приложение уже активно, отправляем broadcast")
            val broadcastIntent = Intent(ACTION_TILE_TOGGLE).apply {
                setPackage(packageName)
                putExtra("tile_action", "toggle_save_to_gallery")
                putExtra("tile_value", false)
            }
            sendBroadcast(broadcastIntent)
            return
        }
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("tile_action", "toggle_save_to_gallery")
            putExtra("tile_value", false)
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra("from_tile_service", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
        }
        
        launchActivity(intent)
    }
    
    private fun launchActivity(intent: Intent) {
        val creatorOptionsBundle = if (Build.VERSION.SDK_INT >= 34) {
            try {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    setLaunchDisplayId(0)
                }.toBundle()
            } catch (e: Exception) {
                Log.w(TAG, "ActivityOptions error: ${e.message}")
                ActivityOptions.makeBasic().toBundle()
            }
        } else {
            null
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptionsBundle
        )

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(pendingIntent)
                return
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                startActivityAndCollapse(intent)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "startActivityAndCollapse failed: ${e.message}")
        }
        
        val senderActivityOptions = if (Build.VERSION.SDK_INT >= 34) {
            try {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    setLaunchDisplayId(0)
                }
            } catch (e: Exception) {
                ActivityOptions.makeBasic()
            }
        } else {
            null
        }
        
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                pendingIntent.send(
                    this, 0, null, null, null, null,
                    senderActivityOptions?.toBundle()
                )
            } else {
                pendingIntent.send()
            }
        } catch (e: Exception) {
            Log.e(TAG, "PendingIntent send failed: ${e.message}")
            if (Build.VERSION.SDK_INT < 34) {
                try {
                    startActivity(intent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback failed: ${e2.message}")
                }
            }
        }
    }
    
    private fun updateTile() {
        val isActive = getTileState(this)
        
        qsTile?.let { tile ->
            tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            
            tile.label = if (isActive) {
                getString(R.string.tile_save_to_gallery_active)
            } else {
                getString(R.string.tile_save_to_gallery)
            }
            
            tile.icon = if (isActive) {
                Icon.createWithResource(this, android.R.drawable.ic_menu_gallery)
            } else {
                Icon.createWithResource(this, android.R.drawable.ic_menu_gallery)
            }
            
            tile.updateTile()
        }
    }
}
