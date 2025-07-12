package com.example.sostaxi

import android.app.Application
import android.util.Log

/**
 * Кастомный Application класс для ранней инициализации SSL
 */
class SOSTaxiApplication : Application() {
    
    companion object {
        private const val TAG = "SOSTaxiApplication"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "Инициализация приложения...")
        Log.d(TAG, "Приложение инициализировано")
    }
    

} 