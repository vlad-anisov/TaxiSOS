package com.example.sostaxi

import android.annotation.SuppressLint
import android.util.Log
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Кастомный TrustManager который доверяет всем сертификатам
 * Поддерживает hostname-aware проверку для Android
 */
@SuppressLint("TrustAllX509TrustManager")
class CustomTrustManager : X509ExtendedTrustManager() {
    
    companion object {
        private const val TAG = "CustomTrustManager"
    }
    
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        Log.d(TAG, "checkClientTrusted: Доверяем клиентскому сертификату")
    }
    
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        Log.d(TAG, "checkServerTrusted: Доверяем серверному сертификату")
    }
    
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
        Log.d(TAG, "checkClientTrusted (socket): Доверяем клиентскому сертификату")
    }
    
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {
        Log.d(TAG, "checkServerTrusted (socket): Доверяем серверному сертификату")
    }
    
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
        Log.d(TAG, "checkClientTrusted (engine): Доверяем клиентскому сертификату")
    }
    
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {
        Log.d(TAG, "checkServerTrusted (engine): Доверяем серверному сертификату для ${engine.peerHost}")
    }
    
    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf()
    }
} 