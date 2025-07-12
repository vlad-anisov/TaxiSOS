package com.example.sostaxi

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.security.cert.X509Certificate
import javax.net.ssl.*

/**
 * Локальный RTMP прокси сервер для обхода SSL проблем
 * Принимает незащищенные RTMP соединения и перенаправляет их на RTMPS сервер
 */
object RTMPProxyServer {
    
    private const val TAG = "RTMPProxyServer"
    private const val PROXY_PORT = 1935
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val proxyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Запускает локальный RTMP прокси сервер
     */
    fun startProxy(): String {
        return try {
            Log.d(TAG, "Запуск RTMP прокси сервера на порту $PROXY_PORT...")
            
            serverSocket = ServerSocket(PROXY_PORT)
            isRunning = true
            
            // Запускаем сервер в корутине
            proxyScope.launch {
                acceptConnections()
            }
            
            val localUrl = "rtmp://127.0.0.1:$PROXY_PORT"
            Log.d(TAG, "RTMP прокси сервер запущен: $localUrl")
            localUrl
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска RTMP прокси: ${e.message}", e)
            // Если не удалось запустить на 1935, попробуем другой порт
            startProxyOnAlternativePort()
        }
    }
    
    private fun startProxyOnAlternativePort(): String {
        val alternativePorts = listOf(1936, 1937, 1938, 1939, 8080, 8081)
        
        for (port in alternativePorts) {
            try {
                Log.d(TAG, "Пробую запустить прокси на порту $port...")
                serverSocket = ServerSocket(port)
                isRunning = true
                
                proxyScope.launch {
                    acceptConnections()
                }
                
                val localUrl = "rtmp://127.0.0.1:$port"
                Log.d(TAG, "RTMP прокси сервер запущен на альтернативном порту: $localUrl")
                return localUrl
                
            } catch (e: Exception) {
                Log.d(TAG, "Порт $port занят, пробую следующий...")
                continue
            }
        }
        
        Log.e(TAG, "Не удалось запустить прокси ни на одном порту")
        return "rtmp://127.0.0.1:1935" // Возвращаем дефолтный URL
    }
    
    private suspend fun acceptConnections() {
        try {
            while (isRunning && serverSocket?.isClosed == false) {
                try {
                    val clientSocket = serverSocket?.accept()
                    if (clientSocket != null) {
                        Log.d(TAG, "Новое RTMP соединение от ${clientSocket.remoteSocketAddress}")
                        
                        // Обрабатываем каждое соединение в отдельной корутине
                        proxyScope.launch {
                            handleClientConnection(clientSocket)
                        }
                    }
                } catch (e: SocketException) {
                    if (isRunning) {
                        Log.e(TAG, "Ошибка принятия соединения: ${e.message}")
                    }
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Неожиданная ошибка в accept: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Критическая ошибка в acceptConnections: ${e.message}")
        }
    }
    
    private suspend fun handleClientConnection(clientSocket: Socket) {
        var serverSocket: Socket? = null
        
        try {
            Log.d(TAG, "Обработка клиентского соединения...")
            
            // Создаем SSL соединение с RTMPS сервером
            val rtmpsUrl = "dc4-1.rtmp.t.me"
            val rtmpsPort = 443
            
            Log.d(TAG, "Подключаемся к RTMPS серверу: $rtmpsUrl:$rtmpsPort")
            
            // Создаем SSL контекст с отключенной проверкой
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            
            val sslSocketFactory = sslContext.socketFactory
            serverSocket = sslSocketFactory.createSocket(rtmpsUrl, rtmpsPort) as SSLSocket
            
            // SSL сокеты не имеют hostnameVerifier, это только для HttpsURLConnection
            Log.d(TAG, "SSL сокет создан без проверки hostname")
            
            Log.d(TAG, "SSL соединение с RTMPS сервером установлено")
            
            // Создаем потоки для проксирования данных
            val clientInput = clientSocket.getInputStream()
            val clientOutput = clientSocket.getOutputStream()
            val serverInput = serverSocket.getInputStream()
            val serverOutput = serverSocket.getOutputStream()
            
            // Запускаем проксирование в обе стороны
            val clientToServerJob = proxyScope.launch {
                proxyData(clientInput, serverOutput, "Client->Server")
            }
            
            val serverToClientJob = proxyScope.launch {
                proxyData(serverInput, clientOutput, "Server->Client")
            }
            
            // Ждем завершения любого из потоков
            try {
                clientToServerJob.join()
                serverToClientJob.join()
            } catch (e: Exception) {
                Log.d(TAG, "Соединение завершено: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки соединения: ${e.message}", e)
        } finally {
            try {
                clientSocket.close()
                serverSocket?.close()
                Log.d(TAG, "Соединения закрыты")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка закрытия соединений: ${e.message}")
            }
        }
    }
    
    private suspend fun proxyData(input: InputStream, output: OutputStream, direction: String) {
        try {
            val buffer = ByteArray(8192)
            var bytesTransferred = 0L
            
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                
                output.write(buffer, 0, bytesRead)
                output.flush()
                
                bytesTransferred += bytesRead
                
                if (bytesTransferred % 10240 == 0L) { // Логируем каждые 10KB
                    Log.v(TAG, "$direction: передано $bytesTransferred байт")
                }
            }
            
            Log.d(TAG, "$direction: передача завершена, всего $bytesTransferred байт")
            
        } catch (e: Exception) {
            Log.d(TAG, "$direction: ошибка передачи данных: ${e.message}")
        }
    }
    
    /**
     * Останавливает прокси сервер
     */
    fun stopProxy() {
        try {
            Log.d(TAG, "Остановка RTMP прокси сервера...")
            isRunning = false
            serverSocket?.close()
            proxyScope.cancel()
            Log.d(TAG, "RTMP прокси сервер остановлен")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки прокси: ${e.message}")
        }
    }
    
    /**
     * Проверяет, запущен ли прокси сервер
     */
    fun isProxyRunning(): Boolean = isRunning
} 