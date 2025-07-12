package com.example.sostaxi

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.*

object LanguageManager {
    private const val PREF_SELECTED_LANGUAGE = "selected_language"
    
    fun setLocale(context: Context, languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        
        val config = Configuration()
        config.setLocale(locale)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
        
        // Сохраняем выбранный язык
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SELECTED_LANGUAGE, languageCode).apply()
    }
    
    fun getSelectedLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        return prefs.getString(PREF_SELECTED_LANGUAGE, getSystemLanguage()) ?: getSystemLanguage()
    }
    
    private fun getSystemLanguage(): String {
        val systemLanguage = Locale.getDefault().language
        return when (systemLanguage) {
            "ru" -> "ru"
            "en" -> "en"
            "pl" -> "pl"
            else -> "ru" // По умолчанию русский
        }
    }
    
    fun applyLanguage(context: Context) {
        val selectedLanguage = getSelectedLanguage(context)
        setLocale(context, selectedLanguage)
    }
    
    fun getLanguageIndex(context: Context): Int {
        val selectedLanguage = getSelectedLanguage(context)
        return when (selectedLanguage) {
            "ru" -> 0
            "en" -> 1
            "pl" -> 2
            else -> 0
        }
    }
    
    fun getLanguageCodeByIndex(index: Int): String {
        return when (index) {
            0 -> "ru"
            1 -> "en"
            2 -> "pl"
            else -> "ru"
        }
    }
} 