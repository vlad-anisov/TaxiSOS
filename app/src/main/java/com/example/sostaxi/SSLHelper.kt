package com.example.sostaxi

import android.annotation.SuppressLint
import android.util.Log
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Вспомогательный класс для работы с SSL соединениями
 * Решает проблемы с RTMP SSL сертификатами для серверов Telegram
 */
object SSLHelper {
    
    private const val TAG = "SSLHelper"
    
    /**
     * Создает доверяющий все сертификаты SSL контекст
     * ВНИМАНИЕ: Используется только для RTMP серверов Telegram
     */
    @SuppressLint("TrustAllX509TrustManager")
    fun createTrustAllSSLContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(CustomTrustManager())
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext
    }
    
    /**
     * Создает hostname verifier который принимает все хосты
     * ВНИМАНИЕ: Используется только для RTMP серверов Telegram
     */
    fun createTrustAllHostnameVerifier(): HostnameVerifier {
        return HostnameVerifier { hostname, _ -> 
            Log.d(TAG, "HostnameVerifier: Доверяем хосту $hostname")
            true 
        }
    }
    
    /**
     * Настраивает глобальные SSL параметры для RTMP соединений
     * Вызывается один раз при инициализации приложения
     */
    fun setupGlobalSSLForRTMP() {
        try {
            Log.d(TAG, "Настройка глобального SSL для RTMP...")
            
            val sslContext = createTrustAllSSLContext()
            
            // Устанавливаем глобальный SSL контекст
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(createTrustAllHostnameVerifier())
            
            // Устанавливаем глобальный SSL контекст для всех соединений
            SSLContext.setDefault(sslContext)
            
            // Системные свойства для SSL
            System.setProperty("javax.net.ssl.trustStore", "")
            System.setProperty("javax.net.ssl.trustStorePassword", "")
            System.setProperty("javax.net.ssl.keyStore", "")
            System.setProperty("javax.net.ssl.keyStorePassword", "")
            
            // Дополнительные свойства для отключения проверки сертификатов
            System.setProperty("com.sun.net.ssl.checkRevocation", "false")
            System.setProperty("sun.security.ssl.allowUnsafeRenegotiation", "true")
            System.setProperty("sun.security.ssl.allowLegacyHelloMessages", "true")
            
            // Свойства для OkHttp (используется многими библиотеками)
            System.setProperty("okhttp.unsafeTrustManager", "true")
            
            // Для Ktor (используется в некоторых RTMP библиотеках)
            System.setProperty("io.ktor.network.tls.certificates.checkHostname", "false")
            System.setProperty("io.ktor.network.tls.trustAll", "true")
            
            Log.d(TAG, "Глобальный SSL настроен успешно")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка настройки SSL: ${e.message}", e)
        }
    }
    
    /**
     * Проверяет, является ли URL RTMP сервером Telegram
     */
    fun isTelegramRTMPServer(url: String): Boolean {
        return url.contains("rtmp.t.me") || 
               url.contains("dc1-1.rtmp.t.me") ||
               url.contains("dc2-1.rtmp.t.me") ||
               url.contains("dc3-1.rtmp.t.me") ||
               url.contains("dc4-1.rtmp.t.me") ||
               url.contains("dc5-1.rtmp.t.me")
    }
} 