package com.example.sostaxi

import android.content.Context
import android.content.*
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log
import android.widget.*
import android.widget.AdapterView
import kotlinx.coroutines.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import com.google.android.gms.location.Priority
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import com.pedro.library.view.OpenGlView
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.encoder.input.gl.render.filters.CropFilterRender
import com.pedro.common.ConnectChecker
import com.pedro.encoder.utils.gl.AspectRatioMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume
import android.view.View
import android.content.res.Configuration
import android.os.Bundle
import android.app.ProgressDialog
import android.view.SurfaceHolder
import org.json.JSONObject
import java.io.FileOutputStream


class MainActivity : AppCompatActivity(), ConnectChecker {

    private val root by lazy { findViewById<View>(android.R.id.content) }
    private lateinit var startStopButton: Button
    private lateinit var settingsButton: Button
    private lateinit var modeSwitch: Spinner
    private lateinit var openGlView: OpenGlView
    // Добавляем переменную для отслеживания готовности Surface
    private var isSurfaceReady = false
    private var pendingAutoStart = false
    private var pendingWorkMode: WorkMode? = null
    // Добавляем флаг для контроля закрытия приложения
    private var isClosingFromTile = false
    // Камеры - отдельные экземпляры для стриминга и записи
    private var streamingCamera: RtmpCamera2? = null
    private var recordingCamera: RtmpCamera2? = null
    // Добавляем флаги для отслеживания инициализации камер
    private var isStreamingCameraPrepared = false
    private var isRecordingCameraPrepared = false
  // Текущий записываемый сегмент
  private var currentSegmentFile: java.io.File? = null
  private var isSegmentRecordingActive: Boolean = false
  private var isFinalizingLastSegment: Boolean = false
  // Раздельные счётчики сегментов для каждого направления
  private var sentSegmentCountGroup: Int = 0      // Счётчик для группы
  private var sentSegmentCountContacts: Int = 0   // Счётчик для контактов
  
  // Хранение ID сообщений для группы (Bot API): segmentNumber -> messageId
  private var botGroupMessageIds = mutableMapOf<Int, Int>()
  // Хранение ID сообщений для контактов (TDLib): contactId -> (segmentNumber -> (chatId, messageId))
  private var contactMessageIds = mutableMapOf<Long, MutableMap<Int, Pair<Long, Long>>>()
  
  // Флаги активности плиток (независимые)
  @Volatile private var isTileSendToGroupActive = false
  @Volatile private var isTileSendToContactsActive = false
  @Volatile private var isTileSaveToGalleryActive = false
  
    // Добавляем класс для хранения данных канала
    data class ChannelInfo(val name: String, val url: String, val key: String)
    // Список каналов
    private val channelsList = mutableListOf<ChannelInfo>()
    
    // Новые поля для Telegram авторизации и данных пользователя
    private var telegramUserId: Long? = null
    private var telegramUserName: String? = null
    private var telegramUserPhone: String? = null
    private var telegramContacts = mutableListOf<TelegramContact>()
    private var selectedContacts = mutableListOf<TelegramContact>()
    
    // Класс для хранения контактов из Telegram
    data class TelegramContact(
        val id: Long,
        val name: String,
        val phone: String,
        var isSelected: Boolean = false
    )
    
    // Информация о пользователе
    private var userName: String = ""
    private var userCar: String = ""
    private var userCarColor: String = ""
    
    // Диалог настроек (для возможности его обновления)
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    
    // UI-элементы диалога для обновления их статуса
    private var accessibilityInfoText: TextView? = null
    private var enableAccessibilityButton: Button? = null
    
    enum class WorkMode {
        VIDEO_SEGMENTS,     // Отправка видео сегментами
        RTMP_STREAMING      // Настоящая RTMP-трансляция
    }
    private var currentWorkMode = WorkMode.RTMP_STREAMING
    private var rtmpUrl: String = ""
    private var rtmpStreamKey: String = ""
    private var fullRtmpUrl: String = ""
    // URL Google Таблицы в CSV формате
    private val SPREADSHEET_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vR2CksjoEO6pzSaz0FY6fhBSAIjXn9gCCGsMCVG7sPsRAh54FvuLmxn_2eQh6QCOBK9PsNGn6-06QZU/pub?output=csv"
    
    companion object {
        private const val TAG = "MainActivity"
        @Volatile var isActive: Boolean = false
        // Отдельный scope для критичных отправок при остановке/закрытии (не привязан к lifecycle Activity)
        private val backgroundSendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val TELEGRAM_CHAT_ID = "-1003132262418"
        
        /**
         * Токен бота не хранится одной строкой, чтобы его нельзя было просто вытащить
         * из APK через обычную декомпиляцию (JADX покажет только части).
         *
         * Это НЕ абсолютная защита (динамический анализ всё равно может извлечь),
         * но “просто декомпилом” токен не лежит в исходниках цельной строкой.
         */
        fun getTelegramBotToken(): String {
            // base64(token) разбит на 4 части и слегка “перемешан”
            // base64("8551732522:AAG2P0aac9GeB9A6osHXIEsTBlzvtFmwzmc") без '=='
            val p1 = "ODU1MTczMjUyMjpB"
            val p2 = "QUcyUDBhYWM5R2VC"
            val p3 = "OUE2b3NIWElFc1RC"
            val p4 = "bHp2dEZtd3ptYw"
            val b64 = p1 + p2 + p3 + p4
            return try {
                String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            } catch (_: Exception) {
                ""
            }
        }
        
        // Добавляем константы для запроса разрешений
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val TELEGRAM_AUTH_PERMISSION_REQUEST_CODE = 1002

      // Настройки длительности видеосегментов
      private const val PREFS_TAXI = "taxi_sos_prefs"
      private const val KEY_SEGMENT_DURATION_SECONDS = "segment_duration_seconds"
      private const val KEY_LAST_SENT_FILE_NAME = "last_sent_file_name"
      private const val KEY_SAVE_SEGMENTS_TO_GALLERY = "save_segments_to_gallery"
      private const val KEY_SEND_TO_GROUP = "send_to_group"
      private const val KEY_RESOLVED_BOT_CHAT_ID = "resolved_bot_chat_id"
      private const val KEY_STORAGE_LIMIT_GB = "storage_limit_gb"
      private const val MIN_SEGMENT_DURATION_SECONDS = 10
      private const val MAX_SEGMENT_DURATION_SECONDS = 300
      private const val MIN_STORAGE_LIMIT_GB = 1
      private const val MAX_STORAGE_LIMIT_GB = 50
      private const val DEFAULT_STORAGE_LIMIT_GB = 5
      
      // Action для broadcast от плиток
      const val ACTION_TILE_TOGGLE = "com.example.sostaxi.TILE_TOGGLE"
    }
    
    // BroadcastReceiver для обработки плиток когда приложение уже в PiP режиме
    private val tileToggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TILE_TOGGLE) {
                val action = intent.getStringExtra("tile_action") ?: return
                val value = intent.getBooleanExtra("tile_value", false)
                Log.d("MainActivity", "Получен broadcast от плитки: action=$action, value=$value")
                handleTileToggle(action, value)
            }
        }
    }
    
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var liveLocationMessageId: Int? = null
    // Live-location для контактов через TDLib: contactUserId -> (chatId, messageId)
    private val contactLiveLocationState = mutableMapOf<Long, Pair<Long, Long>>()
    private var autoStopJob: Job? = null
    @Volatile private var resolvedBotChatIdCache: String? = null

    private fun agentDebugLog(
        runId: String,
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?>
    ) {
        try {
            val obj = JSONObject()
            obj.put("sessionId", "debug-session")
            obj.put("runId", runId)
            obj.put("hypothesisId", hypothesisId)
            obj.put("location", location)
            obj.put("message", message)
            obj.put("timestamp", System.currentTimeMillis())
            obj.put("data", JSONObject(data))
            FileOutputStream("/Users/vlad/AndroidStudioProjects/SOSTaxi/.cursor/debug.log", true).use { fos ->
                fos.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
        }
    }

    private fun candidateBotChatIds(): List<String> {
        val base = TELEGRAM_CHAT_ID.trim()
        val candidates = mutableListOf<String>()
        if (base.isNotEmpty()) candidates.add(base)
        // Часто для супергрупп/каналов нужен -100 + id
        if (base.startsWith("-") && !base.startsWith("-100")) {
            val digits = base.removePrefix("-")
            if (digits.all { it.isDigit() }) {
                candidates.add("-100$digits")
            }
        }
        return candidates.distinct()
    }

    private suspend fun getResolvedBotChatId(token: String): String {
        val validCandidates = candidateBotChatIds()
        
        // Проверяем кэш в памяти
        resolvedBotChatIdCache?.let { cached ->
            if (cached in validCandidates) return cached
            // Кэш устарел (константа изменилась) - сбрасываем
            resolvedBotChatIdCache = null
        }
        
        val prefs = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
        prefs.getString(KEY_RESOLVED_BOT_CHAT_ID, null)?.let { cached ->
            if (cached in validCandidates) {
                resolvedBotChatIdCache = cached
                return cached
            }
            // Кэш в SharedPreferences устарел - очищаем
            prefs.edit().remove(KEY_RESOLVED_BOT_CHAT_ID).apply()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        // #region agent log
        agentDebugLog(
            runId = "run1",
            hypothesisId = "A",
            location = "MainActivity.kt:getResolvedBotChatId",
            message = "Resolving bot chat_id candidates",
            data = mapOf("candidates" to candidateBotChatIds(), "cache" to (resolvedBotChatIdCache != null))
        )
        // #endregion

        for (candidate in candidateBotChatIds()) {
            try {
                val url = "https://api.telegram.org/bot${token}/getChat?chat_id=$candidate"
                val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
                val body = resp.body?.string().orEmpty()
                resp.close()
                val json = JSONObject(body)
                if (json.optBoolean("ok", false)) {
                    prefs.edit().putString(KEY_RESOLVED_BOT_CHAT_ID, candidate).apply()
                    resolvedBotChatIdCache = candidate
                    Log.d("MainActivity", "Resolved bot chat_id = $candidate")
                    // #region agent log
                    agentDebugLog(
                        runId = "run1",
                        hypothesisId = "A",
                        location = "MainActivity.kt:getResolvedBotChatId",
                        message = "getChat ok=true (resolved)",
                        data = mapOf("candidate" to candidate)
                    )
                    // #endregion
                    return candidate
                } else {
                    Log.w("MainActivity", "getChat failed for $candidate: ${json.optString("description")}")
                    // #region agent log
                    agentDebugLog(
                        runId = "run1",
                        hypothesisId = "A",
                        location = "MainActivity.kt:getResolvedBotChatId",
                        message = "getChat ok=false",
                        data = mapOf(
                            "candidate" to candidate,
                            "description" to json.optString("description"),
                            "error_code" to json.optInt("error_code", -1)
                        )
                    )
                    // #endregion
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "getChat exception for $candidate: ${e.message}")
                // #region agent log
                agentDebugLog(
                    runId = "run1",
                    hypothesisId = "A",
                    location = "MainActivity.kt:getResolvedBotChatId",
                    message = "getChat exception",
                    data = mapOf("candidate" to candidate, "error" to (e.message ?: ""))
                )
                // #endregion
            }
        }

        // Фоллбек: что есть
        val fallback = TELEGRAM_CHAT_ID
        prefs.edit().putString(KEY_RESOLVED_BOT_CHAT_ID, fallback).apply()
        resolvedBotChatIdCache = fallback
        return fallback
    }

    // Добавим свойство для телеграм-авторизации в класс MainActivity
    private val telegramAuthHelper: TelegramAuthHelper by lazy {
        TelegramAuthHelper.getInstance(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Применяем язык перед созданием активности
        LanguageManager.applyLanguage(this)
        super.onCreate(savedInstanceState)

        Log.d(TAG, "Инициализация MainActivity...")
        
        // Проверяем флаг закрытия - если приложение закрывается, не инициализируем
        if (isClosingFromTile) {
            Log.d(TAG, "Приложение закрывается, прерываем инициализацию")
            finish()
            return
        }

        loadRtmpSettings()
        loadUserSettings()
        
        // Инициализируем помощник авторизации Telegram
        // Используем singleton, поэтому повторная инициализация безопасна
        if (!isClosingFromTile) {
            initializeTelegramAuth()
        }
        
        val layout = FrameLayout(this)
        val previewParams = FrameLayout.LayoutParams(1, 1)
        previewParams.gravity = android.view.Gravity.TOP or android.view.Gravity.START

        openGlView = OpenGlView(this)
        openGlView.setAspectRatioMode(AspectRatioMode.Adjust)
        openGlView.visibility = View.VISIBLE
        
        // Добавляем callback для отслеживания готовности Surface
        openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d("MainActivity", "Surface создан")
                isSurfaceReady = true
                // Если есть отложенный автостарт, запускаем его с небольшой задержкой
                if (pendingAutoStart) {
                    pendingAutoStart = false
                    pendingWorkMode?.let { mode ->
                        currentWorkMode = mode
                        // Добавляем небольшую задержку для полной инициализации
                        openGlView.postDelayed({
                            if (!isActive) {
                                start()
                            }
                        }, 500)
                    }
                    pendingWorkMode = null
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d("MainActivity", "Surface изменен: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d("MainActivity", "Surface уничтожен")
                isSurfaceReady = false
                
                // Останавливаем предпросмотр камеры при уничтожении Surface
                try {
                    streamingCamera?.stopPreview()
                    recordingCamera?.stopPreview()
                    Log.d("MainActivity", "Предпросмотр камеры остановлен")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Ошибка остановки предпросмотра: ${e.message}")
                }
            }
        })
        
        // Инициализируем две отдельные камеры
        streamingCamera = RtmpCamera2(openGlView, this)
        recordingCamera = RtmpCamera2(openGlView, this)
        streamingCamera?.switchCamera()
        recordingCamera?.switchCamera()
        layout.addView(openGlView, previewParams)

        startStopButton = Button(this)
        startStopButton.text = "Старт"
        startStopButton.isAllCaps = false
        startStopButton.visibility = View.GONE
        val btnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        btnParams.gravity = android.view.Gravity.CENTER or android.view.Gravity.BOTTOM
        btnParams.bottomMargin = 200
        layout.addView(startStopButton, btnParams)

        // Добавляем кнопку "Настройки"
        settingsButton = Button(this)
        settingsButton.text = getString(R.string.settings)
        settingsButton.isAllCaps = false
        val settingsBtnParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        settingsBtnParams.gravity = android.view.Gravity.CENTER or android.view.Gravity.BOTTOM
        settingsBtnParams.bottomMargin = 50 // Позиционируем под кнопкой Старт/Стоп
        layout.addView(settingsButton, settingsBtnParams)

        setContentView(layout)

        // Назначаем обработчик для кнопки настроек
        settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        // Обработка интентов от Quick Settings Tiles
        handleQuickTileIntent()

        // Если после смены языка нужно авто-открыть настройки
        try {
            val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("open_settings_after_recreate", false)) {
                prefs.edit().putBoolean("open_settings_after_recreate", false).apply()
                root.post { showSettingsDialog() }
            }
        } catch (_: Exception) {}

        // Автозапуск BluetoothButtonService (всегда включено под капотом)
        try {
            val sharedPrefs = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
            if (!sharedPrefs.getBoolean("bluetooth_button_enabled", false)) {
                // Проставим дефолты один раз
                sharedPrefs.edit()
                    .putBoolean("bluetooth_button_enabled", true)
                    .putBoolean("ble_only_mode", true)
                    .apply()
            }
            val bluetoothButtonEnabled = true
            if (bluetoothButtonEnabled) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val needScan = checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    val needConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (needScan || needConnect) {
                        requestPermissions(arrayOf(
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT
                        ), 1002)
                    }
                }
                Log.d(TAG, "Bluetooth-кнопка включена, запускаем сервис")
                BluetoothButtonService.start(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска BluetoothButtonService: ${e.message}")
        }

        startStopButton.setOnClickListener {
            if (!isActive) {
                start()
            } else {
                stop()
            }
        }
        
        // Регистрируем BroadcastReceiver для плиток (чтобы не выходить из PiP)
        val filter = IntentFilter(ACTION_TILE_TOGGLE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tileToggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(tileToggleReceiver, filter)
        }
    }

    private fun formatSegmentDuration(totalSeconds: Int): String {
        val seconds = totalSeconds.coerceIn(MIN_SEGMENT_DURATION_SECONDS, MAX_SEGMENT_DURATION_SECONDS)
        val minutes = seconds / 60
        val remainSeconds = seconds % 60
        return when {
            minutes == 0 -> resources.getQuantityString(R.plurals.seconds_plurals, remainSeconds, remainSeconds)
            remainSeconds == 0 -> resources.getQuantityString(R.plurals.minutes_plurals, minutes, minutes)
            else -> getString(
                R.string.minutes_seconds_format,
                resources.getQuantityString(R.plurals.minutes_plurals, minutes, minutes),
                resources.getQuantityString(R.plurals.seconds_plurals, remainSeconds, remainSeconds)
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickTileIntent()
    }
    
    override fun onResume() {
        super.onResume()
        // Проверяем, был ли открыт диалог настроек перед уходом в системные настройки
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val shouldUpdateSettings = prefs.getBoolean("settings_dialog_open", false)
        
        Log.d("MainActivity", "onResume: shouldUpdateSettings=$shouldUpdateSettings, dialog?.isShowing=${dialog?.isShowing}")
        
        if (shouldUpdateSettings) {
            // Сбрасываем флаг
            prefs.edit().putBoolean("settings_dialog_open", false).apply()
            
            Log.d("MainActivity", "Диалог был закрыт системой, переоткрываем его")
            
            // КРИТИЧЕСКИ ВАЖНО: явно закрываем старый диалог (если он вдруг остался)
            dialog?.dismiss()
            dialog = null
            accessibilityInfoText = null
            enableAccessibilityButton = null
            
            // Диалог был закрыт системой при переходе в настройки, переоткрываем его
            window.decorView.post {
                showSettingsDialog()
            }
        }
    }
    
    /**
     * Обновляет статус Accessibility Service в уже открытом диалоге настроек
     */
    private fun updateAccessibilityStatus() {
        Log.d("MainActivity", "updateAccessibilityStatus вызван: dialog?.isShowing=${dialog?.isShowing}, accessibilityInfoText=$accessibilityInfoText, enableAccessibilityButton=$enableAccessibilityButton")
        
        // Проверяем, что диалог открыт и UI-элементы доступны
        if (dialog?.isShowing == true && accessibilityInfoText != null && enableAccessibilityButton != null) {
            val isEnabled = isAccessibilityServiceEnabled()
            
            Log.d("MainActivity", "Обновляем UI: isEnabled=$isEnabled")
            
            if (isEnabled) {
                enableAccessibilityButton?.text = getString(R.string.accessibility_enabled_button)
                accessibilityInfoText?.text = getString(R.string.accessibility_enabled_info)
                accessibilityInfoText?.setTextColor(0xFF00AA00.toInt()) // Зелёный
            } else {
                enableAccessibilityButton?.text = getString(R.string.accessibility_required_button)
                accessibilityInfoText?.text = getString(R.string.accessibility_disabled_info)
                accessibilityInfoText?.setTextColor(0xFFFF0000.toInt()) // Красный
            }
            
            Log.d("MainActivity", "✅ Статус Accessibility Service обновлён: enabled=$isEnabled")
        } else {
            Log.w("MainActivity", "⚠️ Не удалось обновить статус: диалог не открыт или UI-элементы недоступны")
        }
    }

    private fun showSettingsDialog() {
        // КРИТИЧЕСКИ ВАЖНО: Закрываем старый диалог перед созданием нового (если он ещё показывается)
        dialog?.let {
            if (it.isShowing) {
                Log.d("MainActivity", "⚠️ Старый диалог всё ещё показывается, закрываем его")
                it.dismiss()
            }
        }
        
        // Устанавливаем FLAG_SECURE чтобы система НЕ сохраняла snapshot при переходе в настройки
        // Это предотвращает появление "замороженного" фона после возврата
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        Log.d("MainActivity", "🔒 FLAG_SECURE установлен (snapshot отключен)")
        
        // Создаем ScrollView для прокрутки
        val scrollView = ScrollView(this)
        val dialogLayout = LinearLayout(this)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(50, 30, 50, 30)
        scrollView.addView(dialogLayout)

        // Объявляем переменную dialog заранее
        lateinit var localDialog: androidx.appcompat.app.AlertDialog

        // Блок выбора языка — переносим в начало настроек
        val languageLabel = TextView(this)
        languageLabel.text = getString(R.string.language_setting)
        languageLabel.textSize = 16f
        languageLabel.setPadding(0, 0, 0, 10)
        dialogLayout.addView(languageLabel)

        val languageSpinner = Spinner(this)
        val languageAdapter = ArrayAdapter.createFromResource(
            this,
            R.array.languages,
            android.R.layout.simple_spinner_item
        )
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = languageAdapter
        languageSpinner.setSelection(LanguageManager.getLanguageIndex(this))
        dialogLayout.addView(languageSpinner)

        // Автоприменение языка без нажатия "Готово"
        var languageListenerInitialized = false
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!languageListenerInitialized) {
                    languageListenerInitialized = true
                    return
                }
                val selectedLanguageCode = LanguageManager.getLanguageCodeByIndex(position)
                val currentLanguage = LanguageManager.getSelectedLanguage(this@MainActivity)
                if (selectedLanguageCode == currentLanguage) return

                // Применяем язык сразу
                LanguageManager.setLocale(this@MainActivity, selectedLanguageCode)

                // Обновляем заголовки плиток
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    try {
                        android.service.quicksettings.TileService.requestListeningState(
                            this@MainActivity,
                            android.content.ComponentName(this@MainActivity, StreamingTileService::class.java)
                        )
                        android.service.quicksettings.TileService.requestListeningState(
                            this@MainActivity,
                            android.content.ComponentName(this@MainActivity, VideoSegmentsTileService::class.java)
                        )
                    } catch (_: Exception) {}
                }

                // Закрываем текущий диалог и перезапускаем активити с авто-открытием настроек
                try { dialog?.dismiss() } catch (_: Exception) {}
                val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("open_settings_after_recreate", true).apply()
                recreate()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Разделитель после выбора языка
        val languageDividerTop = View(this)
        languageDividerTop.setBackgroundColor(0x20000000)
        val languageDividerParamsTop = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        )
        languageDividerParamsTop.setMargins(0, 30, 0, 30)
        dialogLayout.addView(languageDividerTop, languageDividerParamsTop)

        // Режим работы скрыт
        /*
        // Добавляем заголовок "Режим работы"
        val modeLabel = TextView(this)
        modeLabel.text = getString(R.string.work_mode)
        modeLabel.textSize = 16f
        modeLabel.setPadding(0, 0, 0, 10)
        dialogLayout.addView(modeLabel)

        // Создаем новый Spinner для выбора режима
        val modeSpinner = Spinner(this)
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.work_modes,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeSpinner.adapter = adapter
        
        // Устанавливаем текущий выбранный режим
        modeSpinner.setSelection(if (currentWorkMode == WorkMode.VIDEO_SEGMENTS) 0 else 1)
        dialogLayout.addView(modeSpinner)

        // Добавляем разделитель
        val divider = View(this)
        divider.setBackgroundColor(0x20000000)
        val dividerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        )
        dividerParams.setMargins(0, 30, 0, 30)
        dialogLayout.addView(divider, dividerParams)
        */

        // Отправка в группу (фиксированная группа, без выбора)
        val taxiPrefs = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
        val sendToGroupCheckBox = CheckBox(this)
        sendToGroupCheckBox.text = getString(R.string.send_to_group)
        sendToGroupCheckBox.textSize = 14f
        sendToGroupCheckBox.setPadding(0, 0, 0, 0)
        sendToGroupCheckBox.isChecked = taxiPrefs.getBoolean(KEY_SEND_TO_GROUP, true)
        // Сохраняем сразу при переключении, чтобы не зависеть от кнопки "Готово"
        sendToGroupCheckBox.setOnCheckedChangeListener { _, isChecked ->
            try { taxiPrefs.edit().putBoolean(KEY_SEND_TO_GROUP, isChecked).apply() } catch (_: Exception) {}
        }
        dialogLayout.addView(sendToGroupCheckBox)
        
        // Добавляем разделитель
        val divider2 = View(this)
        divider2.setBackgroundColor(0x20000000)
        val divider2Params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        )
        divider2Params.setMargins(0, 30, 0, 30)
        dialogLayout.addView(divider2, divider2Params)
        
        // Добавляем заголовок "Telegram авторизация"
        val telegramLabel = TextView(this)
        telegramLabel.text = getString(R.string.telegram_auth)
        telegramLabel.textSize = 16f
        telegramLabel.setPadding(0, 0, 0, 10)
        dialogLayout.addView(telegramLabel)
        
        // Проверяем авторизацию пользователя (проверяем и TelegramAuthHelper и SharedPreferences)
        val telegramAuthPrefs = getSharedPreferences("telegram_auth_prefs", Context.MODE_PRIVATE)
        val isAuthenticatedInPrefs = telegramAuthPrefs.getLong("user_id", 0) != 0L
        
        if (telegramAuthHelper.isAuthenticated() && isAuthenticatedInPrefs) {
            // Получаем данные пользователя из SharedPreferences
            val sharedPrefs = telegramAuthPrefs
            val userId = sharedPrefs.getLong("user_id", 0)
            val firstName = sharedPrefs.getString("first_name", "") ?: ""
            val lastName = sharedPrefs.getString("last_name", null)
            val username = sharedPrefs.getString("username", null)
            
            // Информация о текущем пользователе
            val userInfoText = TextView(this)
            val displayName = buildString {
                append(firstName)
                lastName?.let { append(" $it") }
                username?.let { append(" (@$it)") }
            }
            userInfoText.text = getString(R.string.authorized_as_format, displayName)
            userInfoText.textSize = 14f
            userInfoText.setPadding(0, 0, 0, 10)
            dialogLayout.addView(userInfoText)
            

            
            // Кнопка "Войти в другой аккаунт Telegram"
            val changeAccountButton = Button(this)
            changeAccountButton.text = getString(R.string.login_another_account)
            changeAccountButton.isAllCaps = false
            dialogLayout.addView(changeAccountButton)
            
            changeAccountButton.setOnClickListener {
                // Сначала выходим из текущего аккаунта, потом запускаем новую авторизацию
                logoutFromTelegram()
                dialog?.dismiss() // Закрываем текущий диалог
            }
            
            // Добавляем заголовок "Выбранные контакты"
            if (selectedContacts.isNotEmpty()) {
                val contactsLabel = TextView(this)
                contactsLabel.text = getString(R.string.selected_contacts_count_format, selectedContacts.size)
                contactsLabel.textSize = 16f
                contactsLabel.setPadding(0, 10, 0, 10)
                dialogLayout.addView(contactsLabel)
                
                // Контейнер для списка выбранных контактов
                val contactsContainer = LinearLayout(this)
                contactsContainer.orientation = LinearLayout.VERTICAL
                contactsContainer.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                
                // Добавляем первые 3 контакта (или меньше, если выбрано меньше)
                val displayCount = minOf(3, selectedContacts.size)
                for (i in 0 until displayCount) {
                    val contact = selectedContacts[i]
                    val contactText = TextView(this)
                    contactText.text = contact.name
                    contactText.textSize = 14f
                    contactText.setPadding(10, 5, 0, 5)
                    contactsContainer.addView(contactText)
                }
                
                // Если выбрано больше 3 контактов, показываем "И еще X"
                if (selectedContacts.size > 3) {
                    val moreText = TextView(this)
                    moreText.text = getString(R.string.and_more_format, selectedContacts.size - 3)
                    moreText.textSize = 14f
                    moreText.setPadding(10, 5, 0, 5)
                    contactsContainer.addView(moreText)
                }
                
                dialogLayout.addView(contactsContainer)
            }
            
            // Кнопка выбора контактов
            val selectContactsButton = Button(this)
            selectContactsButton.text = getString(R.string.contact_selection)
            selectContactsButton.isAllCaps = false
            dialogLayout.addView(selectContactsButton)
            
            selectContactsButton.setOnClickListener {
                showContactsSelectionDialog()
            }
        } else {
            // Кнопка "Войти через Telegram"
            val loginButton = Button(this)
            loginButton.text = getString(R.string.login_via_telegram)
            loginButton.isAllCaps = false
            dialogLayout.addView(loginButton)
            
            loginButton.setOnClickListener {
                // Запускаем полноценную авторизацию через Telegram OAuth
                startTelegramAuth()
            }
        }
        
        // Добавляем разделитель
        val divider3 = View(this)
        divider3.setBackgroundColor(0x20000000)
        val divider3Params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        )
        divider3Params.setMargins(0, 30, 0, 30)
        dialogLayout.addView(divider3, divider3Params)
        
        // Добавляем заголовок "Информация о пользователе"
        val userInfoLabel = TextView(this)
        userInfoLabel.text = getString(R.string.user_info)
        userInfoLabel.textSize = 16f
        userInfoLabel.setPadding(0, 0, 0, 10)
        dialogLayout.addView(userInfoLabel)
        
        // Загружаем сохраненные данные
        val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
        val telegramUserPrefs = getSharedPreferences("telegram_auth_prefs", Context.MODE_PRIVATE)
        
        val firstName = sharedPrefs.getString("first_name", "") ?: ""
        val registrationNumber = sharedPrefs.getString("registration_number", "") ?: ""
        val taxiNumber = sharedPrefs.getString("taxi_number", "") ?: ""
        val carBrand = sharedPrefs.getString("car_brand", "") ?: ""
        val carColor = sharedPrefs.getString("car_color", "") ?: ""
        
        // Поле для ввода имени
        val firstNameLabel = TextView(this)
        firstNameLabel.text = getString(R.string.first_name) + ":"
        firstNameLabel.textSize = 14f
        dialogLayout.addView(firstNameLabel)
        
        val firstNameInput = EditText(this)
        firstNameInput.setText(firstName)
        firstNameInput.hint = getString(R.string.enter_first_name_hint)
        dialogLayout.addView(firstNameInput)
        
        // Поле для ввода регистрационного номера
        val registrationLabel = TextView(this)
        registrationLabel.text = getString(R.string.registration_number) + ":"
        registrationLabel.textSize = 14f
        registrationLabel.setPadding(0, 10, 0, 0)
        dialogLayout.addView(registrationLabel)
        
        val registrationInput = EditText(this)
        registrationInput.setText(registrationNumber)
        registrationInput.hint = getString(R.string.enter_registration_hint)
        dialogLayout.addView(registrationInput)
        
        // Поле для ввода бортового номера такси
        val taxiNumberLabel = TextView(this)
        taxiNumberLabel.text = getString(R.string.taxi_board_number) + ":"
        taxiNumberLabel.textSize = 14f
        taxiNumberLabel.setPadding(0, 10, 0, 0)
        dialogLayout.addView(taxiNumberLabel)
        
        val taxiNumberInput = EditText(this)
        taxiNumberInput.setText(taxiNumber)
        taxiNumberInput.hint = getString(R.string.enter_taxi_board_hint)
        dialogLayout.addView(taxiNumberInput)
        
        // Поле для ввода марки автомобиля
        val carBrandLabel = TextView(this)
        carBrandLabel.text = getString(R.string.car_brand) + ":"
        carBrandLabel.textSize = 14f
        carBrandLabel.setPadding(0, 10, 0, 0)
        dialogLayout.addView(carBrandLabel)
        
        val carBrandInput = EditText(this)
        carBrandInput.setText(carBrand)
        carBrandInput.hint = getString(R.string.enter_car_brand_hint)
        dialogLayout.addView(carBrandInput)
        
        // Поле для ввода цвета автомобиля
        val carColorLabel = TextView(this)
        carColorLabel.text = getString(R.string.car_color) + ":"
        carColorLabel.textSize = 14f
        carColorLabel.setPadding(0, 10, 0, 0)
        dialogLayout.addView(carColorLabel)
        
        val carColorInput = EditText(this)
        carColorInput.setText(carColor)
        carColorInput.hint = getString(R.string.enter_car_color_hint)
        dialogLayout.addView(carColorInput)
        
        // Разделитель перед настройкой длительности сегмента
        val dividerDuration = View(this)
        dividerDuration.setBackgroundColor(0x20000000)
        val dividerDurationParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        )
        dividerDurationParams.setMargins(0, 30, 0, 30)
        dialogLayout.addView(dividerDuration, dividerDurationParams)

        // Настройка длительности сегмента (ползунок)
        val segmentDurationLabel = TextView(this)
        segmentDurationLabel.text = getString(R.string.video_segment_length)
        segmentDurationLabel.textSize = 16f
        segmentDurationLabel.setPadding(0, 0, 0, 10)
        dialogLayout.addView(segmentDurationLabel)

        // Значение длительности с форматированием (мин/сек)
        val segmentDurationValue = TextView(this)
        segmentDurationValue.textSize = 14f
        segmentDurationValue.setPadding(0, 0, 0, 10)
        dialogLayout.addView(segmentDurationValue)

        val savedDuration = sharedPrefs.getInt(
            KEY_SEGMENT_DURATION_SECONDS,
            MIN_SEGMENT_DURATION_SECONDS
        ).coerceIn(MIN_SEGMENT_DURATION_SECONDS, MAX_SEGMENT_DURATION_SECONDS)
        segmentDurationValue.text = formatSegmentDuration(savedDuration)

        val segmentDurationSeekBar = SeekBar(this)
        segmentDurationSeekBar.max = (MAX_SEGMENT_DURATION_SECONDS - MIN_SEGMENT_DURATION_SECONDS)
        segmentDurationSeekBar.progress = savedDuration - MIN_SEGMENT_DURATION_SECONDS
        dialogLayout.addView(segmentDurationSeekBar)

        segmentDurationSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress + MIN_SEGMENT_DURATION_SECONDS
                segmentDurationValue.text = formatSegmentDuration(seconds)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Чекбокс для сохранения сегментов в галерею
        val saveToGalleryCheckBox = CheckBox(this)
        saveToGalleryCheckBox.text = getString(R.string.save_segments_to_gallery)
        saveToGalleryCheckBox.textSize = 14f
        saveToGalleryCheckBox.setPadding(0, 20, 0, 0)
        saveToGalleryCheckBox.isChecked = sharedPrefs.getBoolean(KEY_SAVE_SEGMENTS_TO_GALLERY, false)
        dialogLayout.addView(saveToGalleryCheckBox)

        // Значение лимита
        val storageLimitValue = TextView(this)
        storageLimitValue.textSize = 14f
        storageLimitValue.setPadding(60, 10, 0, 0)
        val savedLimitGb = sharedPrefs.getInt(KEY_STORAGE_LIMIT_GB, DEFAULT_STORAGE_LIMIT_GB)
            .coerceIn(MIN_STORAGE_LIMIT_GB, MAX_STORAGE_LIMIT_GB)
        storageLimitValue.text = getString(R.string.storage_limit_value_format, savedLimitGb)
        // Ползунок/значение показываем только если включено сохранение в галерею
        storageLimitValue.visibility = if (saveToGalleryCheckBox.isChecked) View.VISIBLE else View.GONE
        dialogLayout.addView(storageLimitValue)

        // Ползунок для лимита памяти
        val storageLimitSeekBar = SeekBar(this)
        storageLimitSeekBar.max = MAX_STORAGE_LIMIT_GB - MIN_STORAGE_LIMIT_GB
        storageLimitSeekBar.progress = savedLimitGb - MIN_STORAGE_LIMIT_GB
        storageLimitSeekBar.setPadding(60, 0, 20, 0)
        storageLimitSeekBar.visibility = if (saveToGalleryCheckBox.isChecked) View.VISIBLE else View.GONE
        dialogLayout.addView(storageLimitSeekBar)

        storageLimitSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val gb = progress + MIN_STORAGE_LIMIT_GB
                storageLimitValue.text = getString(R.string.storage_limit_value_format, gb)
                // Сохраняем значение сразу, даже если галочка сохранения в галерею выключена позже
                try {
                    sharedPrefs.edit().putInt(KEY_STORAGE_LIMIT_GB, gb).apply()
                } catch (_: Exception) {}
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Функция для обновления видимости ползунка лимита (показываем только если включено сохранение в галерею)
        fun updateStorageLimitVisibility() {
            val show = saveToGalleryCheckBox.isChecked
            storageLimitValue.visibility = if (show) View.VISIBLE else View.GONE
            storageLimitSeekBar.visibility = if (show) View.VISIBLE else View.GONE
        }

        // Обработчик для чекбокса сохранения в галерею
        saveToGalleryCheckBox.setOnCheckedChangeListener { _, _ ->
            updateStorageLimitVisibility()
        }

        // Разделитель перед настройками Bluetooth-кнопки
        val dividerBluetooth = View(this)
        dividerBluetooth.setBackgroundColor(0x20000000)
        val dividerBluetoothParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        )
        dividerBluetoothParams.setMargins(0, 30, 0, 30)
        dialogLayout.addView(dividerBluetooth, dividerBluetoothParams)
        
        // Настройки Bluetooth-кнопки
        val bluetoothButtonLabel = TextView(this)
        bluetoothButtonLabel.text = getString(R.string.bluetooth_button_settings)
        bluetoothButtonLabel.textSize = 16f
        bluetoothButtonLabel.setPadding(0, 0, 0, 10)
        dialogLayout.addView(bluetoothButtonLabel)
        
        // Информационное сообщение (скрыто)
        /*
        val bluetoothButtonInfo = TextView(this)
        bluetoothButtonInfo.text = getString(R.string.bluetooth_button_info)
        bluetoothButtonInfo.textSize = 12f
        bluetoothButtonInfo.setPadding(0, 0, 0, 15)
        bluetoothButtonInfo.setTextColor(0xFF666666.toInt())
        dialogLayout.addView(bluetoothButtonInfo)
        */
        
        // Обработка Bluetooth-кнопки включена всегда (под капотом)

        // Статус и запрос Nearby devices (Android 12+) - скрыто
        /*
        val nearbyPermStatus = TextView(this)
        nearbyPermStatus.textSize = 12f
        nearbyPermStatus.setPadding(0, 8, 0, 4)
        dialogLayout.addView(nearbyPermStatus)

        fun updateNearbyPermStatus() {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val hasScan = checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasScan && hasConnect) {
                    nearbyPermStatus.text = "✓ Доступ к устройствам поблизости предоставлен"
                    nearbyPermStatus.setTextColor(0xFF00AA00.toInt())
                } else {
                    nearbyPermStatus.text = "⚠ Требуется разрешение: Устройства поблизости (Nearby devices)"
                    nearbyPermStatus.setTextColor(0xFFFF6600.toInt())
                }
            } else {
                nearbyPermStatus.text = ""
            }
        }
        updateNearbyPermStatus()
        */

        // Кнопка запроса разрешений Bluetooth - скрыто
        /*
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // Проверяем, было ли уже запрошено разрешение
            val bluetoothPermRequested = sharedPrefs.getBoolean("bluetooth_perm_requested", false)
            val hasScan = checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            // Показываем кнопку только если разрешения не предоставлены И не были запрошены ранее
            if (!hasScan || !hasConnect) {
                if (!bluetoothPermRequested) {
                    val requestNearbyButton = Button(this)
                    requestNearbyButton.text = "Разрешить доступ к устройствам поблизости"
                    requestNearbyButton.isAllCaps = false
                    requestNearbyButton.setOnClickListener {
                        // Сохраняем флаг, что разрешение было запрошено
                        sharedPrefs.edit().putBoolean("bluetooth_perm_requested", true).apply()
                        
                        requestPermissions(arrayOf(
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT
                        ), 1100)
                    }
                    dialogLayout.addView(requestNearbyButton)
                }
            }
        }
        */

        // Кнопка выбора BLE-устройства (опционально)
        val selectBleButton = Button(this)
        selectBleButton.text = getString(R.string.select_bluetooth_button)
        selectBleButton.isAllCaps = false
        
        // TextView для отображения выбранного устройства
        val selectedDeviceText = TextView(this)
        selectedDeviceText.textSize = 12f
        selectedDeviceText.setPadding(0, 8, 0, 15)
        selectedDeviceText.setTextColor(0xFF00AA00.toInt()) // Зелёный цвет
        
        // Загружаем сохраненное устройство
        val savedDeviceName = sharedPrefs.getString("ble_device_name", null)
        val savedDeviceAddr = sharedPrefs.getString("ble_device_address", null)
        if (savedDeviceName != null && savedDeviceAddr != null) {
            selectedDeviceText.text = getString(R.string.selected_device_prefix, savedDeviceName)
            selectedDeviceText.visibility = View.VISIBLE
        } else {
            selectedDeviceText.visibility = View.GONE
        }
        
        selectBleButton.setOnClickListener {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val needConnect = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (needConnect) {
                        requestPermissions(arrayOf(
                            android.Manifest.permission.BLUETOOTH_CONNECT
                        ), 1004)
                        return@setOnClickListener
                    }
                }
                showBleConnectedDevicesDialog { deviceName ->
                    // Обновляем TextView после выбора устройства
                    selectedDeviceText.text = getString(R.string.selected_device_prefix, deviceName)
                    selectedDeviceText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть список устройств: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        dialogLayout.addView(selectBleButton)
        dialogLayout.addView(selectedDeviceText)

        // Выбор действия Bluetooth-кнопки скрыт: всегда VIDEO_SEGMENTS
        
        // Информация о службе специальных возможностей
        accessibilityInfoText = TextView(this)
        accessibilityInfoText?.text = getString(R.string.bluetooth_button_accessibility_info)
        accessibilityInfoText?.textSize = 12f
        accessibilityInfoText?.setPadding(0, 20, 0, 15)
        accessibilityInfoText?.setTextColor(0xFFFF6600.toInt()) // Оранжевый цвет для важной информации
        dialogLayout.addView(accessibilityInfoText)
        
        // Кнопка для открытия настроек Accessibility
        enableAccessibilityButton = Button(this)
        enableAccessibilityButton?.text = getString(R.string.bluetooth_button_enable_accessibility)
        enableAccessibilityButton?.isAllCaps = false
        enableAccessibilityButton?.setOnClickListener {
            openAccessibilitySettings()
        }
        dialogLayout.addView(enableAccessibilityButton)
        
        // Проверяем, включена ли служба специальных возможностей
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        if (isAccessibilityEnabled) {
            enableAccessibilityButton?.text = getString(R.string.accessibility_enabled_button)
            accessibilityInfoText?.text = getString(R.string.accessibility_enabled_info)
            accessibilityInfoText?.setTextColor(0xFF00AA00.toInt()) // Зелёный цвет
        } else {
            enableAccessibilityButton?.text = getString(R.string.accessibility_required_button)
            accessibilityInfoText?.text = getString(R.string.accessibility_disabled_info)
            accessibilityInfoText?.setTextColor(0xFFFF0000.toInt()) // Красный цвет для важного предупреждения
        }
        
        // Создаем и отображаем диалог с кнопкой "Готово" по центру
        localDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.done)) { _, _ ->
                // Сохраняем выбранный режим (закомментировано, т.к. режим скрыт)
                /*
                currentWorkMode = when (modeSpinner.selectedItemPosition) {
                    0 -> WorkMode.VIDEO_SEGMENTS
                    1 -> WorkMode.RTMP_STREAMING
                    else -> WorkMode.RTMP_STREAMING
                }
                */
                
                // Сохраняем информацию о пользователе
                userName = firstNameInput.text.toString().trim()
                userCar = carBrandInput.text.toString().trim()
                userCarColor = carColorInput.text.toString().trim()
                
                // Сохраняем в SharedPreferences
                val sharedPrefs = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
                
                // Сохраняем настройки Bluetooth-кнопки
                val bluetoothButtonWasEnabled = sharedPrefs.getBoolean("bluetooth_button_enabled", true)
                val bluetoothButtonEnabled = true
                val bluetoothButtonAction = "VIDEO_SEGMENTS"
                
                sharedPrefs.edit()
                    .putString("first_name", firstNameInput.text.toString().trim())
                    .putString("registration_number", registrationInput.text.toString().trim())
                    .putString("taxi_number", taxiNumberInput.text.toString().trim())
                    .putString("car_brand", carBrandInput.text.toString().trim())
                    .putString("car_color", carColorInput.text.toString().trim())
                    // Сохраняем длительность сегмента (с валидацией)
                    .putInt(
                        KEY_SEGMENT_DURATION_SECONDS,
                        (segmentDurationSeekBar.progress + MIN_SEGMENT_DURATION_SECONDS)
                            .coerceIn(MIN_SEGMENT_DURATION_SECONDS, MAX_SEGMENT_DURATION_SECONDS)
                    )
                    // Сохраняем настройку сохранения сегментов в галерею
                    .putBoolean(KEY_SAVE_SEGMENTS_TO_GALLERY, saveToGalleryCheckBox.isChecked)
                    // Сохраняем настройку отправки в группу
                    .putBoolean(KEY_SEND_TO_GROUP, sendToGroupCheckBox.isChecked)
                    // Сохраняем настройки лимита памяти
                    .putInt(KEY_STORAGE_LIMIT_GB, storageLimitSeekBar.progress + MIN_STORAGE_LIMIT_GB)
                    // Сохраняем настройки Bluetooth-кнопки
                    .putBoolean("bluetooth_button_enabled", true)
                    .putString("bluetooth_button_action", bluetoothButtonAction)
                    .putBoolean("ble_only_mode", true)
                    .apply()
                
                // Управляем BluetoothButtonService в зависимости от настроек
                if (bluetoothButtonEnabled && !bluetoothButtonWasEnabled) {
                    // Включили - запускаем сервис
                    BluetoothButtonService.start(this@MainActivity)
                    Toast.makeText(this@MainActivity, getString(R.string.bluetooth_button_enabled_toast), Toast.LENGTH_SHORT).show()
                } else if (!bluetoothButtonEnabled && bluetoothButtonWasEnabled) {
                    // Выключили - останавливаем сервис
                    BluetoothButtonService.stop(this@MainActivity)
                    Toast.makeText(this@MainActivity, getString(R.string.bluetooth_button_disabled_toast), Toast.LENGTH_SHORT).show()
                }
                
                // Проверяем, изменился ли язык
                val selectedLanguageIndex = languageSpinner.selectedItemPosition
                val selectedLanguageCode = LanguageManager.getLanguageCodeByIndex(selectedLanguageIndex)
                val currentLanguage = LanguageManager.getSelectedLanguage(this@MainActivity)
                
                if (selectedLanguageCode != currentLanguage) {
                    // Сохраняем новый язык
                    LanguageManager.setLocale(this@MainActivity, selectedLanguageCode)

                    // Обновляем заголовки плиток (попросим систему переслушать тайлы)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        try {
                            android.service.quicksettings.TileService.requestListeningState(
                                this@MainActivity,
                                android.content.ComponentName(this@MainActivity, StreamingTileService::class.java)
                            )
                            android.service.quicksettings.TileService.requestListeningState(
                                this@MainActivity,
                                android.content.ComponentName(this@MainActivity, VideoSegmentsTileService::class.java)
                            )
                        } catch (_: Exception) {}
                    }

                    // Перезапускаем активность для применения языка
                    recreate()
                } else {
                    // Если язык не изменился, принудительно обновляем UI для очистки "замороженного" фона
                    // Это убирает snapshot, который остаётся после возврата из системных настроек
                    window.decorView.postDelayed({
                        // Форсируем перерисовку фона
                        window.decorView.invalidate()
                        root.invalidate()
                    }, 50) // Небольшая задержка для плавности
                }
            }
            .create()
        
        // Центрирование заголовка диалога
        localDialog.setOnShowListener {
            val titleView = localDialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
            titleView?.gravity = android.view.Gravity.CENTER
            
            // Центрирование кнопок
            val positiveButton = localDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val layoutParams = positiveButton.layoutParams as LinearLayout.LayoutParams
            layoutParams.gravity = android.view.Gravity.CENTER
            layoutParams.width = LinearLayout.LayoutParams.WRAP_CONTENT
            positiveButton.layoutParams = layoutParams
            
            // Применяем жёлтый цвет ко всем кнопкам
            applyYellowColorToDialogButtons(localDialog)
            
            // Перемещение кнопки в центр
            val buttonLayout = positiveButton.parent as LinearLayout
            buttonLayout.gravity = android.view.Gravity.CENTER
            
            // Обновляем статус Accessibility Service при показе диалога
            // Это важно, если пользователь только что вернулся из настроек
            val isEnabled = isAccessibilityServiceEnabled()
            if (isEnabled) {
                enableAccessibilityButton?.text = getString(R.string.accessibility_enabled_button)
                accessibilityInfoText?.text = getString(R.string.accessibility_enabled_info)
                accessibilityInfoText?.setTextColor(0xFF00AA00.toInt())
            } else {
                enableAccessibilityButton?.text = getString(R.string.accessibility_required_button)
                accessibilityInfoText?.text = getString(R.string.accessibility_disabled_info)
                accessibilityInfoText?.setTextColor(0xFFFF0000.toInt())
            }
        }
        
        // Обработчик закрытия диалога (для очистки "замороженного" фона)
        localDialog.setOnDismissListener {
            // Сбрасываем флаг settings_dialog_open
            val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("settings_dialog_open", false).apply()
            
            // Снимаем FLAG_SECURE чтобы разрешить скриншоты снова
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            Log.d("MainActivity", "🔓 FLAG_SECURE снят (snapshot разрешён)")
            
            // Принудительно обновляем UI для очистки snapshot
            window.decorView.postDelayed({
                window.decorView.invalidate()
                root.invalidate()
            }, 50)
        }
        
        // Нет выбора канала: группа фиксированная
        
        // Сохраняем созданный диалог в class member для возможности обновления статуса
        this.dialog = localDialog
        
        // Показываем диалог
        localDialog.show()
    }

    private fun start() {
        if (isActive) return
        
        // Проверяем разрешения перед началом
        if (!checkPermissions()) {
            Log.d("MainActivity", "Разрешения не получены, запрашиваем")
            requestPermissions()
            return
        }

        // Доступ по Tribute: если пользователь НЕ состоит в группе/канале, то приложение НЕ запускает никакой функционал
        // (ни запись, ни сегменты, ни сохранение, ни отправку куда-либо).
        val token = getTelegramBotToken()
        // #region agent log
        agentDebugLog(
            runId = "run1",
            hypothesisId = "B",
            location = "MainActivity.kt:start",
            message = "Start pressed: begin access check",
            data = mapOf(
                "tokenBlank" to token.isBlank(),
                "workMode" to currentWorkMode.name,
                "sendToGroupPref" to getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE).getBoolean(KEY_SEND_TO_GROUP, true)
            )
        )
        // #endregion
        if (token.isBlank()) {
            Toast.makeText(this, "Ошибка токена бота (проверка подписки недоступна)", Toast.LENGTH_LONG).show()
            return
        }

        // Чтобы проверить подписку, нужен user_id (Tribute даёт доступ через членство в чате)
        val userId = telegramAuthHelper.getCurrentUserId()
        // #region agent log
        agentDebugLog(
            runId = "run1",
            hypothesisId = "C",
            location = "MainActivity.kt:start",
            message = "Have userId for getChatMember?",
            data = mapOf("userIdZero" to (userId == 0L))
        )
        // #endregion
        if (userId == 0L) {
            Toast.makeText(this, getString(R.string.tribute_auth_required), Toast.LENGTH_LONG).show()
            try { startTelegramAuth() } catch (_: Exception) {}
            return
        }

        // Проверяем членство через Bot API getChatMember (для TELEGRAM_CHAT_ID это корректный chat_id)
        ioScope.launch {
            val hasAccess = try {
                val chatId = getResolvedBotChatId(token)
                val url = "https://api.telegram.org/bot${token}/getChatMember" +
                    "?chat_id=${chatId}&user_id=${userId}"
                val resp = OkHttpClient().newCall(Request.Builder().url(url).get().build()).execute()
                val body = resp.body?.string().orEmpty()
                resp.close()
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    Log.w("MainActivity", "getChatMember ok=false: ${json.optString("description")}")
                    // #region agent log
                    agentDebugLog(
                        runId = "run1",
                        hypothesisId = "D",
                        location = "MainActivity.kt:start",
                        message = "getChatMember ok=false",
                        data = mapOf(
                            "chatId" to chatId,
                            "description" to json.optString("description"),
                            "error_code" to json.optInt("error_code", -1)
                        )
                    )
                    // #endregion
                    false
                } else {
                    val status = json.optJSONObject("result")?.optString("status", "") ?: ""
                    // #region agent log
                    agentDebugLog(
                        runId = "run1",
                        hypothesisId = "D",
                        location = "MainActivity.kt:start",
                        message = "getChatMember ok=true",
                        data = mapOf("chatId" to chatId, "status" to status)
                    )
                    // #endregion
                    // left / kicked => нет доступа. остальное (member/restricted/admin/creator) => доступ есть
                    status != "left" && status != "kicked" && status.isNotBlank()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка проверки подписки (getChatMember): ${e.message}")
                // #region agent log
                agentDebugLog(
                    runId = "run1",
                    hypothesisId = "E",
                    location = "MainActivity.kt:start",
                    message = "getChatMember exception",
                    data = mapOf("error" to (e.message ?: ""))
                )
                // #endregion
                false
            }

            withContext(Dispatchers.Main) {
                // #region agent log
                agentDebugLog(
                    runId = "run1",
                    hypothesisId = "D",
                    location = "MainActivity.kt:start",
                    message = "Access decision",
                    data = mapOf("hasAccess" to hasAccess)
                )
                // #endregion
                if (!hasAccess) {
                    Toast.makeText(this@MainActivity, getString(R.string.tribute_subscription_required), Toast.LENGTH_LONG).show()
                } else {
                    startAfterAccessCheck()
                }
            }
        }
        return

        startAfterAccessCheck()
    }

    private fun startAfterAccessCheck() {
        Log.d("MainActivity", "Начинаем запуск с режимом $currentWorkMode")

        // Сбрасываем флаг финализации последнего сегмента при новом старте
        isFinalizingLastSegment = false

        Log.d("MainActivity", "Разрешения получены, продолжаем запуск")

        isActive = true

        // Запускаем в корутине для последовательной отправки сообщений
        ioScope.launch {
            // Отправляем инфо-сообщение и запускаем геолокацию только если активны соответствующие плитки
            // Если активна только галерея - ничего не отправляем
            val shouldSendToGroup = isTileSendToGroupActive
            val shouldSendToContacts = isTileSendToContactsActive && selectedContacts.isNotEmpty()
            
            if (shouldSendToGroup || shouldSendToContacts) {
                // Инфо-сообщение отправляется только туда, где активны плитки
                sendUserInfoMessage()
                // Геолокация тоже
                startLiveLocation()
            } else {
                Log.d("MainActivity", "Пропускаем инфо-сообщение и геолокацию - активна только галерея")
            }
        }

        // Дополнительно запускаем стриминг, если выбран режим RTMP
        Log.d("MainActivity", "Используем $currentWorkMode")
        when (currentWorkMode) {
            WorkMode.RTMP_STREAMING -> {
                Log.d("MainActivity", "Запускаем RTMP стриминг")
                startStream()
            }
            WorkMode.VIDEO_SEGMENTS -> {
                Log.d("MainActivity", "Запускаем запись видеосегментов")
                startRecord()
            }
        }

        startStopButton.text = "Стоп"
        enterPictureInPictureMode()

        // Обновляем состояние плиток при запуске (только для API 24+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            when (currentWorkMode) {
                WorkMode.RTMP_STREAMING -> {
                    StreamingTileService.setTileState(this, true)
                    VideoSegmentsTileService.setTileState(this, false)
                }
                WorkMode.VIDEO_SEGMENTS -> {
                    VideoSegmentsTileService.setTileState(this, true)
                    StreamingTileService.setTileState(this, false)
                }
            }
        }

        // Планируем авто-остановку через 1 час, если пользователь не остановит вручную
        try {
            autoStopJob?.cancel()
        } catch (_: Exception) {}
        autoStopJob = CoroutineScope(Dispatchers.Main).launch {
            delay(3_600_000) // 1 час = 60 минут * 60 секунд * 1000 мс
            if (isActive) {
                Log.d("MainActivity", "Авто-остановка сессии по таймеру 1 час (эмулируем повторное нажатие на плитку)")
                val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("quick_tile_action", "stop_and_close")
                }
                startActivity(intent)
            }
        }
    }

    private fun stop() {
        if (!isActive) return

        Log.d("MainActivity", "Начинаем остановку")
        isActive = false

        // Финализируем и отправляем последний сегмент видео перед остановкой
        if (currentWorkMode == WorkMode.VIDEO_SEGMENTS && !isFinalizingLastSegment) {
            Log.d("MainActivity", "Финализация последнего сегмента перед остановкой")
            try {
                // НЕ блокируем UI поток: TDLib-колбэки приходят через main looper,
                // а блокировка приводит к зависанию/ANR и “последний сегмент не дошёл контактам”.
                backgroundSendScope.launch {
                    try {
                        finalizeAndSendLastSegment()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "finalizeAndSendLastSegment ошибка: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка финализации последнего сегмента: ${e.message}")
            }
        }

        // Останавливаем геолокацию
        try {
            stopLiveLocation()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка остановки геолокации: ${e.message}")
        }

        // Останавливаем камеры
        try {
            streamingCamera?.stopStream()
            streamingCamera?.stopRecord()
            recordingCamera?.stopRecord()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка остановки камер: ${e.message}")
        }
        
        // Останавливаем RTMP прокси сервер
        try {
            RTMPProxyServer.stopProxy()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка остановки RTMP прокси: ${e.message}")
        }
        
        startStopButton.text = "Старт"
        
        // Сбрасываем состояние плиток при остановке (только для API 24+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                StreamingTileService.setTileState(this, false)
                VideoSegmentsTileService.setTileState(this, false)
                // Сбрасываем новые плитки
                SendToGroupTileService.setTileState(this, false)
                SendToContactsTileService.setTileState(this, false)
                SaveToGalleryTileService.setTileState(this, false)
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка сброса состояния плиток: ${e.message}")
            }
        }
        
        // Сбрасываем флаги активности плиток
        isTileSendToGroupActive = false
        isTileSendToContactsActive = false
        isTileSaveToGalleryActive = false
        
        Log.d("MainActivity", "Остановка завершена")
        
        // Сбрасываем флаги инициализации камер
        isStreamingCameraPrepared = false
        isRecordingCameraPrepared = false
    }

    private fun startStream() {
        if (fullRtmpUrl.isEmpty() || fullRtmpUrl == "/") {
            showRtmpSettingsDialog()
            return
        }
        
        // Проверяем готовность Surface перед началом стриминга
        if (!isSurfaceReady) {
            Log.e("MainActivity", "Surface не готов для стриминга")
            return
        }
        
        try {
            // ФИНАЛЬНОЕ РЕШЕНИЕ: Запускаем локальный RTMP прокси
            Log.d("MainActivity", "Запуск локального RTMP прокси для обхода SSL...")
            val proxyUrl = RTMPProxyServer.startProxy()
            
            // Добавляем stream key к прокси URL
            val proxyFullUrl = "$proxyUrl/s/$rtmpStreamKey"
            
            Log.d("MainActivity", "Используем прокси URL: $proxyFullUrl")
            Log.d("MainActivity", "Оригинальный RTMPS URL: $fullRtmpUrl")
            
            // Инициализируем камеру только если она еще не готова
            if (!isStreamingCameraPrepared) {
                Log.d("MainActivity", "Инициализация стриминговой камеры")
                streamingCamera?.prepareAudio(192 * 1024, 44_100, true)
                streamingCamera?.prepareVideo(1280, 720, 30, 2_000 * 1024, 90)
                streamingCamera?.getGlInterface()?.setFilter(CropFilterRender().apply {
                    setCropArea(0f, 33.33f, 99.99f, 33.33f)
                })
                isStreamingCameraPrepared = true
            } else {
                Log.d("MainActivity", "Стриминговая камера уже инициализирована")
            }
            
            // Используем локальный прокси вместо прямого RTMPS соединения
            Log.d("MainActivity", "Подключаемся через RTMP прокси: $proxyFullUrl")
            streamingCamera?.startStream(proxyFullUrl)
            
            Log.d("MainActivity", "RTMP соединение через прокси начато успешно")
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Критическая ошибка при инициализации RTMP прокси: ${e.message}")
        }
    }

    private fun startRecord() {
        ioScope.launch {
            try {
                var segmentCount = 0
                // Счётчики сегментов сбрасываются при активации соответствующей плитки в handleTileToggle
                // Инициализируем камеру один раз в начале сессии
                Log.d("MainActivity", "Глобальная инициализация камеры для записи сегментов")
                recordingCamera?.prepareAudio(192 * 1024, 44_100, true)
                recordingCamera?.prepareVideo(1280, 720, 30, 2_000 * 1024, 90)
                
                // Стабилизационная задержка после инициализации
                delay(1000)

                // Загружаем длительность сегмента из настроек (в секундах)
                val segmentDurationSec = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
                    .getInt(KEY_SEGMENT_DURATION_SECONDS, MIN_SEGMENT_DURATION_SECONDS)
                    .coerceIn(MIN_SEGMENT_DURATION_SECONDS, MAX_SEGMENT_DURATION_SECONDS)
                
                while (MainActivity.isActive) {
                    try {
                        segmentCount++
                        val ts = SimpleDateFormat("yyyyMMdd_HH-mm-ss", Locale.US).format(System.currentTimeMillis())
                      val file = File(getExternalFilesDir(null), "taxi_helper_${ts}_segment${segmentCount}.mp4")
                        
                        Log.d("MainActivity", "Начинаем запись сегмента $segmentCount: ${file.name}")
                        
                        // Проверяем, что камера готова к записи
                        if (recordingCamera == null) {
                            Log.e("MainActivity", "Камера записи не инициализирована")
                            delay(2000)
                            continue
                        }
                        
                        // Проверяем и при необходимости переподготавливаем энкодер
                        if (segmentCount == 1) {
                            Log.d("MainActivity", "Используем уже подготовленный энкодер для сегмента $segmentCount")
                        } else {
                            Log.d("MainActivity", "Переподготавливаем энкодер для сегмента $segmentCount")
                            // После первого сегмента энкодер требует повторной подготовки
                            recordingCamera?.prepareAudio(192 * 1024, 44_100, true)
                            recordingCamera?.prepareVideo(1280, 720, 30, 2_000 * 1024, 90)
                            // Короткая задержка для инициализации
                            delay(500)
                        }
                        
                        // Запускаем запись нового сегмента
                        try {
                          // Сохраняем текущий сегмент
                          currentSegmentFile = file
                          isSegmentRecordingActive = true
                          recordingCamera?.startRecord(file.absolutePath)
                        } catch (e: Exception) {
                            if (e.message?.contains("VideoEncoder not prepared yet") == true) {
                                Log.w("MainActivity", "Энкодер не готов для сегмента $segmentCount, переподготавливаем")
                                // Принудительно переподготавливаем энкодер
                                recordingCamera?.prepareAudio(192 * 1024, 44_100, true)
                                recordingCamera?.prepareVideo(1280, 720, 30, 2_000 * 1024, 90)
                                delay(500)
                              currentSegmentFile = file
                              isSegmentRecordingActive = true
                              recordingCamera?.startRecord(file.absolutePath)
                            } else {
                                throw e // Перебрасываем другие ошибки
                            }
                        }
                        
                        // Записываем выбранную длительность
                        delay(segmentDurationSec * 1000L)
                        
                        // Останавливаем только запись (камера остается активной)
                      recordingCamera?.stopRecord()
                      isSegmentRecordingActive = false
                        
                        Log.d("MainActivity", "Завершена запись сегмента $segmentCount")
                        
                        // Увеличенная задержка для полного завершения записи и освобождения энкодера
                        delay(1500)
                        
                        // Проверяем, что файл создался и имеет корректный размер
                        if (file.exists() && file.length() > 1000) {
                            // Проверяем, не началась ли финализация последнего сегмента
                            if (isFinalizingLastSegment) {
                                Log.d("MainActivity", "Пропускаем отправку сегмента $segmentCount - идет финализация последнего сегмента")
                            } else {
                                // Сканируем файл и отправляем
                                MediaScannerConnection.scanFile(this@MainActivity, arrayOf(file.absolutePath), null, null)
                                
                                // Отправляем/сохраняем видео (логика определяется активными плитками)
                                sendVideo(file)
                                Log.d("MainActivity", "Сегмент $segmentCount обработан")
                            }
                        } else {
                            Log.w("MainActivity", "Файл сегмента $segmentCount не создался или слишком мал: ${file.length()} байт")
                            // Удаляем некорректный файл
                            if (file.exists()) {
                                file.delete()
                            }
                        }
                      currentSegmentFile = null
                        
                        // Достаточная задержка между сегментами для восстановления энкодера
                        delay(1000)
                        
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Ошибка записи сегмента $segmentCount: ${e.message}")
                        // Ждем перед следующей попыткой
                        delay(2000)
                    }
                }
                
            } catch (e: Exception) {
                Log.e("MainActivity", "Критическая ошибка при инициализации записи: ${e.message}")
            } finally {
                // Полностью останавливаем камеру только при завершении
                try {
                    Log.d("MainActivity", "Окончательная остановка камеры записи")
                    recordingCamera?.stopRecord()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Ошибка остановки камеры записи: ${e.message}")
                }
            }
        }
    }

  private fun finalizeLastSegmentBlocking() {
      kotlinx.coroutines.runBlocking(Dispatchers.IO) {
          try {
              finalizeAndSendLastSegment()
          } catch (e: Exception) {
              Log.e("MainActivity", "finalizeAndSendLastSegment ошибка: ${e.message}")
          }
      }
  }

  private suspend fun finalizeAndSendLastSegment() {
      // Устанавливаем флаг финализации, чтобы избежать двойной отправки
      isFinalizingLastSegment = true
      
      Log.d("MainActivity", "finalizeAndSendLastSegment: начинаем финализацию, currentSegmentFile=${currentSegmentFile?.name}, isSegmentRecordingActive=$isSegmentRecordingActive")
      
      var lastFile = currentSegmentFile
      // Если ничего не записывается, нечего завершать
      if (lastFile == null && !isSegmentRecordingActive) {
          // Попробуем найти последний файл по шаблону на случай гонки
          lastFile = tryFindLatestSegmentFile()
          Log.d("MainActivity", "Резервный поиск файла сегмента: ${lastFile?.name}")
      }
      if (lastFile == null) {
          Log.d("MainActivity", "finalizeAndSendLastSegment: нет файла для финализации")
          isFinalizingLastSegment = false
          return
      }

      try {
          // Останавливаем запись, если еще идет
          if (isSegmentRecordingActive) {
              Log.d("MainActivity", "finalizeAndSendLastSegment: останавливаем активную запись")
              try {
                  recordingCamera?.stopRecord()
              } catch (e: Exception) {
                  Log.e("MainActivity", "Ошибка остановки записи: ${e.message}")
              }
              isSegmentRecordingActive = false
          }

          // Ждём стабилизации размера файла (когда moov атом записан)
          // Увеличиваем таймаут до 5 секунд и проверяем стабильность размера файла
          var finalizedFile: File? = lastFile
          val deadline = System.currentTimeMillis() + 5000
          var lastSize = 0L
          var stableSizeCount = 0
          
          Log.d("MainActivity", "finalizeAndSendLastSegment: ожидаем финализации файла ${lastFile.name}")
          
          while (System.currentTimeMillis() < deadline) {
              val candidate = tryFindLatestSegmentFile() ?: finalizedFile
              if (candidate != null && candidate.exists()) {
                  val currentSize = candidate.length()
                  Log.d("MainActivity", "finalizeAndSendLastSegment: проверка файла ${candidate.name}, размер=$currentSize")
                  
                  if (currentSize > 1000) { // Минимальный размер для валидного видео
                      // Проверяем, что размер файла стабилизировался (не меняется 3 проверки подряд)
                      if (currentSize == lastSize) {
                          stableSizeCount++
                          if (stableSizeCount >= 3) {
                              finalizedFile = candidate
                              Log.d("MainActivity", "finalizeAndSendLastSegment: файл стабилизирован, размер=$currentSize")
                              break
                          }
                      } else {
                          stableSizeCount = 0
                          lastSize = currentSize
                      }
                      finalizedFile = candidate
                  }
              }
              kotlinx.coroutines.delay(200)
          }
          
          // Дополнительная задержка для гарантии записи moov атома
          kotlinx.coroutines.delay(500)

          val fileToSend = finalizedFile
          if (fileToSend != null && fileToSend.exists() && fileToSend.length() > 1000) {
              // Проверяем, не был ли этот файл уже отправлен
              val prefs = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
              val lastSentFileName = prefs.getString(KEY_LAST_SENT_FILE_NAME, null)
              
              if (fileToSend.name == lastSentFileName) {
                  Log.d("MainActivity", "finalizeAndSendLastSegment: файл ${fileToSend.name} уже был отправлен, пропускаем")
                  return
              }
              
              // Сохраняем имя отправляемого файла
              prefs.edit().putString(KEY_LAST_SENT_FILE_NAME, fileToSend.name).apply()
              
              // Сканируем файл
              MediaScannerConnection.scanFile(this, arrayOf(fileToSend.absolutePath), null, null)

              // Отправляем/сохраняем синхронно (логика определяется активными плитками)
              Log.d("MainActivity", "Обработка финального сегмента: ${fileToSend.name}, размер=${fileToSend.length()} байт")
              sendVideoNow(fileToSend)
              Log.d("MainActivity", "Финальный сегмент обработан: ${fileToSend.name}")
          } else {
              Log.w("MainActivity", "finalizeAndSendLastSegment: файл не готов или слишком мал: ${fileToSend?.name}, size=${fileToSend?.length()}")
          }
      } finally {
          currentSegmentFile = null
          isFinalizingLastSegment = false
      }
  }

  private fun tryFindLatestSegmentFile(): File? {
      return try {
          val dir = getExternalFilesDir(null) ?: return null
          val files = dir.listFiles { f ->
              f.isFile && f.name.startsWith("taxi_helper_") && f.name.contains("_segment") && f.name.endsWith(".mp4")
          } ?: return null
          files.maxByOrNull { it.lastModified() }
      } catch (_: Exception) { null }
  }

  private suspend fun sendVideoNow(videoFile: File) {
      Log.d("MainActivity", "Обработка финального сегмента: ${videoFile.name}")
      Log.d("MainActivity", "Активные плитки: group=$isTileSendToGroupActive, contacts=$isTileSendToContactsActive, gallery=$isTileSaveToGalleryActive")
      
      // 1) Сохраняем в галерею (если плитка активна)
      val shouldSaveToGallery = isTileSaveToGalleryActive
      if (shouldSaveToGallery) {
          try {
              Log.d("MainActivity", "Сохраняем финальный сегмент в галерею")
              copyVideoToGallery(videoFile)
          } catch (e: Exception) {
              Log.e("MainActivity", "Ошибка сохранения в галерею: ${e.message}")
          }
      }
      
      // 2) Отправка в группу (если плитка активна)
      try {
          if (isTileSendToGroupActive && currentWorkMode == WorkMode.VIDEO_SEGMENTS) {
              // Увеличиваем счётчик для группы
              sentSegmentCountGroup++
              val groupSegmentNum = sentSegmentCountGroup
              
              withContext(Dispatchers.IO) {
                  val token = getTelegramBotToken()
                  if (token.isBlank()) return@withContext

                  val chatId = getResolvedBotChatId(token)
                  
                  // Если это сегмент 3+ для группы, удаляем предыдущий (промежуточный)
                  if (groupSegmentNum >= 3) {
                      val previousSegmentNumber = groupSegmentNum - 1
                      val previousMessageId = botGroupMessageIds[previousSegmentNumber]
                      if (previousMessageId != null) {
                          Log.d("MainActivity", "Удаляем промежуточный сегмент группы #$previousSegmentNumber (messageId=$previousMessageId)")
                          deleteMessageFromBotChat(token, chatId, previousMessageId)
                          botGroupMessageIds.remove(previousSegmentNumber)
                      }
                  }

                  val videoRequestBody = videoFile.asRequestBody("video/mp4".toMediaTypeOrNull())
                  val multipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                      .addFormDataPart("chat_id", chatId)
                      .addFormDataPart("video", videoFile.name, videoRequestBody)
                      .build()
                  val url = "https://api.telegram.org/bot${token}/sendVideo"
                  val request = Request.Builder()
                      .url(url)
                      .post(multipartBody)
                      .build()
                  val client = OkHttpClient.Builder()
                      .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                      .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                      .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                      .build()
                  
                  val response = client.newCall(request).execute()
                  // Парсим message_id из ответа
                  val responseBody = response.body?.string()
                  response.close()
                  if (responseBody != null) {
                      val messageIdRegex = """"message_id":(\d+)""".toRegex()
                      val matchResult = messageIdRegex.find(responseBody)
                      if (matchResult != null) {
                          val messageId = matchResult.groupValues[1].toInt()
                          botGroupMessageIds[groupSegmentNum] = messageId
                          Log.d("MainActivity", "Финальный сегмент группы #$groupSegmentNum отправлен, messageId=$messageId")
                      }
                  }
              }
          }
      } catch (e: Exception) {
          Log.e("MainActivity", "Ошибка отправки видео в канал: ${e.message}")
      }

      // 3) Отправка контактам (если плитка активна)
      try {
          if (isTileSendToContactsActive && selectedContacts.isNotEmpty() && telegramAuthHelper.isAuthenticated()) {
              // Увеличиваем счётчик для контактов
              sentSegmentCountContacts++
              val contactsSegmentNum = sentSegmentCountContacts
              
              Log.d("MainActivity", "Отправляем финальный сегмент контактов #$contactsSegmentNum (${selectedContacts.size} шт)")
              for (contact in selectedContacts) {
                  sendVideoToContactWithTracking(contact, videoFile, contactsSegmentNum)
              }
              // Небольшая задержка чтобы TDLib обработал файл
              kotlinx.coroutines.delay(500)
          }
      } catch (e: Exception) {
          Log.e("MainActivity", "Ошибка отправки видео контактам: ${e.message}")
      }

      // 4) Удаляем файл после обработки (если не нужно сохранять)
      try {
          if (!shouldSaveToGallery && videoFile.exists()) {
              videoFile.delete()
              Log.d("MainActivity", "Файл ${videoFile.name} удален после обработки")
          }
      } catch (_: Exception) {}
  }

    private fun sendVideo(videoFile: File) {
        ioScope.launch {
            Log.d("MainActivity", "Обработка видео сегмента: ${videoFile.name}")
            Log.d("MainActivity", "Активные плитки: group=$isTileSendToGroupActive, contacts=$isTileSendToContactsActive, gallery=$isTileSaveToGalleryActive")
            
            // 1) Сохраняем в галерею (если плитка активна)
            val shouldSaveToGallery = isTileSaveToGalleryActive
            if (shouldSaveToGallery) {
                try {
                    Log.d("MainActivity", "Сохраняем сегмент в галерею")
                    copyVideoToGallery(videoFile)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Ошибка сохранения в галерею: ${e.message}")
                }
            }

            // 2) Отправляем в группу (если плитка активна)
            try {
                if (isTileSendToGroupActive && currentWorkMode == WorkMode.VIDEO_SEGMENTS) {
                    // Увеличиваем счётчик для группы
                    sentSegmentCountGroup++
                    val groupSegmentNum = sentSegmentCountGroup
                    
                    val token = getTelegramBotToken()
                    if (token.isNotBlank()) {
                        val chatId = getResolvedBotChatId(token)
                        
                        // Если это сегмент 3+ для группы, удаляем предыдущий (промежуточный)
                        if (groupSegmentNum >= 3) {
                            val previousSegmentNumber = groupSegmentNum - 1
                            val previousMessageId = botGroupMessageIds[previousSegmentNumber]
                            if (previousMessageId != null) {
                                Log.d("MainActivity", "Удаляем промежуточный сегмент группы #$previousSegmentNumber (messageId=$previousMessageId)")
                                deleteMessageFromBotChat(token, chatId, previousMessageId)
                                botGroupMessageIds.remove(previousSegmentNumber)
                            }
                        }
                        
                        val videoRequestBody = videoFile.asRequestBody("video/mp4".toMediaTypeOrNull())
                        val multipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("chat_id", chatId)
                            .addFormDataPart("video", videoFile.name, videoRequestBody)
                            .build()
                        val url = "https://api.telegram.org/bot${token}/sendVideo"
                        val request = Request.Builder()
                            .url(url)
                            .post(multipartBody)
                            .build()

                        val client = OkHttpClient.Builder()
                            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        
                        val response = client.newCall(request).execute()
                        // Парсим message_id из ответа
                        val responseBody = response.body?.string()
                        response.close()
                        if (responseBody != null) {
                            val messageIdRegex = """"message_id":(\d+)""".toRegex()
                            val matchResult = messageIdRegex.find(responseBody)
                            if (matchResult != null) {
                                val messageId = matchResult.groupValues[1].toInt()
                                botGroupMessageIds[groupSegmentNum] = messageId
                                Log.d("MainActivity", "Сегмент группы #$groupSegmentNum отправлен, messageId=$messageId")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка отправки видео в канал: ${e.message}")
            }

            // 3) Отправляем контактам (если плитка активна и есть выбранные контакты)
            try {
                if (isTileSendToContactsActive && selectedContacts.isNotEmpty() && telegramAuthHelper.isAuthenticated()) {
                    // Увеличиваем счётчик для контактов
                    sentSegmentCountContacts++
                    val contactsSegmentNum = sentSegmentCountContacts
                    
                    Log.d("MainActivity", "Отправляем сегмент контактов #$contactsSegmentNum (${selectedContacts.size} шт)")
                    selectedContacts.forEach { contact ->
                        sendVideoToContactWithTracking(contact, videoFile, contactsSegmentNum)
                    }
                    // Ждем немного чтобы TDLib успел обработать файл
                    delay(2000)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка отправки видео контактам: ${e.message}")
            }

            // 4) Удаляем файл после обработки (если не нужно сохранять)
            try {
                if (!shouldSaveToGallery && videoFile.exists()) {
                    videoFile.delete()
                    Log.d("MainActivity", "Файл ${videoFile.name} удален после обработки")
                }
            } catch (_: Exception) {}
        }
    }
    
    /**
     * Удаляет сообщение из чата через Bot API
     */
    private suspend fun deleteMessageFromBotChat(token: String, chatId: String, messageId: Int) {
        try {
            withContext(Dispatchers.IO) {
                val url = "https://api.telegram.org/bot${token}/deleteMessage?chat_id=$chatId&message_id=$messageId"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val response = client.newCall(request).execute()
                val success = response.isSuccessful
                response.close()
                
                if (success) {
                    Log.d("MainActivity", "Сообщение $messageId успешно удалено из группы")
                } else {
                    Log.w("MainActivity", "Не удалось удалить сообщение $messageId из группы")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка удаления сообщения из группы: ${e.message}")
        }
    }
    
    /**
     * Отправляет видео контакту с отслеживанием messageId и удалением промежуточных сегментов
     */
    private suspend fun sendVideoToContactWithTracking(contact: TelegramContact, videoFile: File, segmentNumber: Int) {
        return suspendCoroutine { continuation ->
            try {
                Log.d("MainActivity", "Отправка видео сегмента #$segmentNumber контакту ${contact.name}: ${videoFile.name}")
                
                // Создаем приватный чат с контактом
                telegramAuthHelper.createPrivateChat(contact.id) { chatId ->
                    if (chatId != null) {
                        // Если это сегмент 3+, удаляем предыдущий (промежуточный)
                        if (segmentNumber >= 3) {
                            val previousSegmentNumber = segmentNumber - 1
                            val contactMsgs = contactMessageIds[contact.id]
                            val previousMsgData = contactMsgs?.get(previousSegmentNumber)
                            if (previousMsgData != null) {
                                val (prevChatId, prevMessageId) = previousMsgData
                                Log.d("MainActivity", "Удаляем промежуточный сегмент #$previousSegmentNumber у контакта ${contact.name}")
                                telegramAuthHelper.deleteMessages(prevChatId, longArrayOf(prevMessageId), true) { success ->
                                    if (success) {
                                        Log.d("MainActivity", "Промежуточный сегмент #$previousSegmentNumber удален у ${contact.name}")
                                    }
                                }
                                contactMsgs.remove(previousSegmentNumber)
                            }
                        }
                        
                        // Отправляем видео с получением messageId
                        telegramAuthHelper.sendVideoWithMessageId(chatId, videoFile.absolutePath) { success, messageId, error ->
                            if (success && messageId != null) {
                                Log.d("MainActivity", "Видео сегмент #$segmentNumber отправлен контакту ${contact.name}, messageId=$messageId")
                                // Сохраняем messageId
                                val contactMsgs = contactMessageIds.getOrPut(contact.id) { mutableMapOf() }
                                contactMsgs[segmentNumber] = Pair(chatId, messageId)
                            } else {
                                Log.e("MainActivity", "Ошибка отправки видео контакту ${contact.name}: $error")
                            }
                            continuation.resume(Unit)
                        }
                    } else {
                        Log.e("MainActivity", "Не удалось создать чат с контактом ${contact.name}")
                        continuation.resume(Unit)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка при отправке видео контакту ${contact.name}: ${e.message}")
                continuation.resume(Unit)
            }
        }
    }
    
    private suspend fun sendVideoToContact(contact: TelegramContact, videoFile: File) {
        return suspendCoroutine { continuation ->
            try {
                Log.d("MainActivity", "Отправка видео контакту ${contact.name}: ${videoFile.name}")
                
                // Создаем приватный чат с контактом
                telegramAuthHelper.createPrivateChat(contact.id) { chatId ->
                    if (chatId != null) {
                        // Отправляем видео в созданный чат
                        telegramAuthHelper.sendVideo(chatId, videoFile.absolutePath) { success, error ->
                            if (success) {
                                Log.d("MainActivity", "Видео успешно отправлено контакту ${contact.name}")
                            } else {
                                Log.e("MainActivity", "Ошибка отправки видео контакту ${contact.name}: $error")
                            }
                            continuation.resume(Unit)
                        }
                    } else {
                        Log.e("MainActivity", "Не удалось создать чат с контактом ${contact.name}")
                        continuation.resume(Unit)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка при отправке видео контакту ${contact.name}: ${e.message}")
                continuation.resume(Unit)
            }
        }
    }
    
    // Функция для копирования видео файла в галерею с проверкой лимита памяти
    private fun copyVideoToGallery(sourceFile: File) {
        try {
            val prefs = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
            val storageLimitGb = prefs.getInt(KEY_STORAGE_LIMIT_GB, DEFAULT_STORAGE_LIMIT_GB)
                .coerceIn(MIN_STORAGE_LIMIT_GB, MAX_STORAGE_LIMIT_GB)
            val storageLimitBytes = storageLimitGb * 1024L * 1024L * 1024L
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Для Android 10+ используем MediaStore API
                
                // Лимит всегда включен и всегда удаляем старые видео при достижении лимита
                enforceStorageLimitMediaStore(storageLimitBytes, sourceFile.length())
                
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, sourceFile.name)
                    put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/Taxi Helper")
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                }
                
                val resolver = contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        sourceFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    
                    // Обновляем IS_PENDING чтобы файл стал виден в галерее
                    contentValues.clear()
                    contentValues.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                    
                    Log.d("MainActivity", "Видео ${sourceFile.name} скопировано в галерею")
                }
            } else {
                // Для старых версий Android сохраняем в публичную директорию
                val moviesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
                val taxiSosDir = File(moviesDir, "Taxi Helper")
                if (!taxiSosDir.exists()) {
                    taxiSosDir.mkdirs()
                }
                
                // Лимит всегда включен и всегда удаляем старые видео при достижении лимита
                enforceStorageLimitLegacy(taxiSosDir, storageLimitBytes, sourceFile.length())
                
                val destFile = File(taxiSosDir, sourceFile.name)
                sourceFile.copyTo(destFile, overwrite = true)
                
                // Сканируем файл чтобы он появился в галерее
                MediaScannerConnection.scanFile(this, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null)
                
                Log.d("MainActivity", "Видео ${sourceFile.name} скопировано в галерею")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка копирования видео в галерею: ${e.message}")
        }
    }
    
    // Проверка и очистка лимита памяти для Android 10+ (MediaStore API)
    private fun enforceStorageLimitMediaStore(limitBytes: Long, newFileSize: Long) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
            
            val resolver = contentResolver
            val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            
            // Получаем все видео из папки Taxi SOS
            val projection = arrayOf(
                android.provider.MediaStore.Video.Media._ID,
                android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                android.provider.MediaStore.Video.Media.SIZE,
                android.provider.MediaStore.Video.Media.DATE_ADDED,
                android.provider.MediaStore.Video.Media.RELATIVE_PATH
            )
            
            val selection = "${android.provider.MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%Taxi Helper%")
            val sortOrder = "${android.provider.MediaStore.Video.Media.DATE_ADDED} ASC"
            
            data class VideoInfo(val id: Long, val name: String, val size: Long, val dateAdded: Long)
            val videos = mutableListOf<VideoInfo>()
            var totalSize = 0L
            
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.SIZE)
                val dateColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATE_ADDED)
                
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn)
                    // Проверяем, что это файл нашего приложения
                    if (name.startsWith("taxi_helper_")) {
                        val id = cursor.getLong(idColumn)
                        val size = cursor.getLong(sizeColumn)
                        val dateAdded = cursor.getLong(dateColumn)
                        videos.add(VideoInfo(id, name, size, dateAdded))
                        totalSize += size
                    }
                }
            }
            
            Log.d("MainActivity", "Текущий размер видео в галерее: ${totalSize / 1024 / 1024} МБ, лимит: ${limitBytes / 1024 / 1024} МБ")
            
            // Удаляем старые файлы пока не освободим место для нового файла
            var deletedCount = 0
            while (totalSize + newFileSize > limitBytes && videos.isNotEmpty()) {
                val oldest = videos.removeAt(0)
                val deleteUri = android.content.ContentUris.withAppendedId(collection, oldest.id)
                try {
                    resolver.delete(deleteUri, null, null)
                    totalSize -= oldest.size
                    deletedCount++
                    Log.d("MainActivity", "Удалено старое видео: ${oldest.name}, освобождено: ${oldest.size / 1024} КБ")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Ошибка удаления видео ${oldest.name}: ${e.message}")
                }
            }
            
            if (deletedCount > 0) {
                Log.d("MainActivity", "Удалено $deletedCount старых видео для освобождения места")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка проверки лимита памяти: ${e.message}")
        }
    }
    
    // Проверка и очистка лимита памяти для старых версий Android
    private fun enforceStorageLimitLegacy(taxiSosDir: File, limitBytes: Long, newFileSize: Long) {
        try {
            // Получаем все файлы taxi_sos_*.mp4 из папки
            val files = taxiSosDir.listFiles { f ->
                f.isFile && f.name.startsWith("taxi_helper_") && f.name.endsWith(".mp4")
            }?.sortedBy { it.lastModified() }?.toMutableList() ?: return
            
            var totalSize = files.sumOf { it.length() }
            
            Log.d("MainActivity", "Текущий размер видео (legacy): ${totalSize / 1024 / 1024} МБ, лимит: ${limitBytes / 1024 / 1024} МБ")
            
            // Удаляем старые файлы пока не освободим место для нового файла
            var deletedCount = 0
            while (totalSize + newFileSize > limitBytes && files.isNotEmpty()) {
                val oldest = files.removeAt(0)
                val freedSize = oldest.length()
                if (oldest.delete()) {
                    totalSize -= freedSize
                    deletedCount++
                    Log.d("MainActivity", "Удалено старое видео: ${oldest.name}, освобождено: ${freedSize / 1024} КБ")
                    
                    // Обновляем медиа-сканер
                    MediaScannerConnection.scanFile(this, arrayOf(oldest.absolutePath), null, null)
                } else {
                    Log.e("MainActivity", "Не удалось удалить файл: ${oldest.name}")
                }
            }
            
            if (deletedCount > 0) {
                Log.d("MainActivity", "Удалено $deletedCount старых видео для освобождения места")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка проверки лимита памяти (legacy): ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.d("MainActivity", "onDestroy вызван")
        
        // Отписываемся от broadcast плиток
        try {
            unregisterReceiver(tileToggleReceiver)
        } catch (_: Exception) {}
        
        super.onDestroy()

        // Сначала корректно останавливаем процессы, затем отменяем scope.
        // Иначе stopLiveLocation()/финализация сегмента могут не стартовать из-за отменённого scope.
        try {
            stop()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка остановки: ${e.message}")
        }

        // Отменяем корутины Activity после stop()
        try {
            ioScope.cancel()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка отмены корутин: ${e.message}")
        }
        try {
            autoStopJob?.cancel()
            autoStopJob = null
        } catch (_: Exception) {}
        
        // Освобождаем ресурсы TDLib
        // Не нужно проверять инициализацию для lazy val
        try {
            Log.d("MainActivity", "Освобождаем ресурсы TelegramAuthHelper в onDestroy")
            // Не вызываем destroy для singleton, так как он может использоваться в других Activity
            // telegramAuthHelper.destroy()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка освобождения TelegramAuthHelper в onDestroy: ${e.message}")
        }
        
        // Принудительно освобождаем камеры
        try {
            Log.d("MainActivity", "Освобождение ресурсов камер...")
            streamingCamera?.stopStream()
            streamingCamera?.stopRecord()
            streamingCamera?.stopPreview()
            recordingCamera?.stopRecord()
            recordingCamera?.stopPreview()
            
            // Обнуляем ссылки на камеры
            streamingCamera = null
            recordingCamera = null
            Log.d("MainActivity", "Ресурсы камер освобождены")
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка освобождения камер: ${e.message}")
        }
        
        Log.d("MainActivity", "onDestroy завершен")
        
        // Сбрасываем флаги инициализации
        isStreamingCameraPrepared = false
        isRecordingCameraPrepared = false
    }

    private suspend fun getFreshLocation(): android.location.Location? {
        val fused = LocationServices.getFusedLocationProviderClient(this)
        return fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, /* cancellationToken = */ null).await()
    }
    
    private fun checkPermissions(): Boolean {
        val requiredPermissions = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun requestPermissions() {
        val requiredPermissions = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // Все разрешения получены, можно запускать
                    start()
                } else {
                    // Разрешения не получены
                    Toast.makeText(this, "Необходимы разрешения для работы приложения", Toast.LENGTH_LONG).show()
                }
            }
            1100, 1002, 1003 -> {
                val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    try {
                        BluetoothButtonService.stop(this)
                        BluetoothButtonService.start(this)
                        Toast.makeText(this, "Разрешения предоставлены", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                } else {
                    Toast.makeText(this, "Разрешения не предоставлены", Toast.LENGTH_SHORT).show()
                }
            }
            TELEGRAM_AUTH_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // Все необходимые разрешения получены
                    startTelegramAuth()
                } else {
                    Toast.makeText(this, "Для авторизации необходимы все запрошенные разрешения", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun htmlEscape(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private suspend fun sendUserInfoMessage() {
        // Используем флаги активных плиток вместо настроек
        val sendToGroup = isTileSendToGroupActive
        val sendToContacts = isTileSendToContactsActive && selectedContacts.isNotEmpty()
        
        // Если ни одно направление не активно - выходим
        if (!sendToGroup && !sendToContacts) {
            Log.d("MainActivity", "Пропускаем инфо-сообщение - нет активных направлений")
            return
        }

        try {
                // Получаем данные из настроек
                val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                val telegramUserPrefs = getSharedPreferences("telegram_user_prefs", Context.MODE_PRIVATE)
                val registrationNumber = sharedPrefs.getString("registration_number", "") ?: ""
                val taxiNumber = sharedPrefs.getString("taxi_number", "") ?: ""
                val telegramPhone = sharedPrefs.getString("telegram_phone", null)
                val telegramUsername = sharedPrefs.getString("telegram_username", null)
                
                // Формируем информационное сообщение о пользователе
                var userInfoMessage = getString(R.string.sos_start_header) + "\n\n"
                
                // 1. Только Имя (без фамилии)
                if (userName.isNotEmpty()) {
                    userInfoMessage += "👤 $userName\n"
                }
                
                // 2. Ссылка на профиль в Telegram как @username
                telegramUsername?.let { username ->
                    userInfoMessage += "📱 @$username\n"
                }
                // Телефон НЕ отправляем (по требованиям)
                
                // 4. Машина, цвет и регистрационный номер в одну строку
                val carInfo = mutableListOf<String>()
                if (userCar.isNotEmpty()) carInfo.add(userCar)
                if (userCarColor.isNotEmpty()) carInfo.add(userCarColor)
                
                if (carInfo.isNotEmpty()) {
                    userInfoMessage += "${carInfo.joinToString(", ")}\n"
                }
                
                // 4.1. Регистрационный номер отдельной строкой с форматированием
                if (registrationNumber.isNotEmpty()) {
                    val formattedReg = "<b><u>${htmlEscape(registrationNumber)}</u></b>"
                    userInfoMessage += "🚗 ${getString(R.string.registration_number)}: $formattedReg\n"
                }

                // 5. Бортовой номер
                if (taxiNumber.isNotEmpty()) {
                    val formattedTaxi = "<b><u>${htmlEscape(taxiNumber)}</u></b>"
                    userInfoMessage += "🚕 ${getString(R.string.taxi_board_number)}: $formattedTaxi\n"
                }
                
                // Добавляем информацию о режиме работы
                userInfoMessage += "\n📹 ${getString(R.string.mode_label)}: "
                when (currentWorkMode) {
                    WorkMode.VIDEO_SEGMENTS -> {
                        userInfoMessage += getString(R.string.mode_video_desc)
                        // Добавляем длительность сегмента из настроек
                        val segmentDurationSec = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
                            .getInt(KEY_SEGMENT_DURATION_SECONDS, MIN_SEGMENT_DURATION_SECONDS)
                            .coerceIn(MIN_SEGMENT_DURATION_SECONDS, MAX_SEGMENT_DURATION_SECONDS)
                        val formattedDuration = formatSegmentDuration(segmentDurationSec)
                        userInfoMessage += "\n" + getString(R.string.segment_duration_prefix, formattedDuration)
                    }
                    WorkMode.RTMP_STREAMING -> {
                        userInfoMessage += getString(R.string.mode_streaming_desc)
                    }
                }
                
                // Добавляем информацию о том, что контактам всегда отправляются видео
                if (selectedContacts.isNotEmpty()) {
                    userInfoMessage += "\n\n📱 ${getString(R.string.selected_contacts_sending_info)}"
                }
                
                userInfoMessage += "\n📅 ${getString(R.string.date_label)}: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                
                // 1) Отправка в группу (если включено) — отдельным блоком, чтобы не ломать контакты
                if (sendToGroup) {
                    try {
                        val token = getTelegramBotToken()
                        if (token.isNotBlank()) {
                            val chatId = getResolvedBotChatId(token)
                            val messageBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                                .addFormDataPart("chat_id", chatId)
                                .addFormDataPart("text", userInfoMessage)
                                .addFormDataPart("parse_mode", "HTML")
                                .build()

                            val messageUrl = "https://api.telegram.org/bot${token}/sendMessage"
                            val messageRequest = Request.Builder()
                                .url(messageUrl)
                                .post(messageBody)
                                .build()

                            OkHttpClient.Builder()
                                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                                .newCall(messageRequest)
                                .execute()
                                .close()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Ошибка отправки инфо-сообщения в группу: ${e.message}")
                    }
                }

                // 2) Если активна плитка контактов — отправляем каждому
                try {
                    if (sendToContacts && telegramAuthHelper.isAuthenticated()) {
                        selectedContacts.forEach { contact ->
                            sendMessageToContact(contact, userInfoMessage)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Ошибка отправки инфо-сообщения контактам: ${e.message}")
                }

        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка формирования информационного сообщения: ${e.message}")
        }
    }
    
    private suspend fun sendMessageToContact(contact: TelegramContact, message: String) {
        try {
            Log.d("MainActivity", "Отправка сообщения контакту ${contact.name}: $message")
            
            // Создаем приватный чат с контактом
            telegramAuthHelper.createPrivateChat(contact.id) { chatId ->
                if (chatId != null) {
                    // Отправляем сообщение в созданный чат
                    telegramAuthHelper.sendMessage(chatId, message) { success, error ->
                        if (success) {
                            Log.d("MainActivity", "Сообщение успешно отправлено контакту ${contact.name}")
                        } else {
                            Log.e("MainActivity", "Ошибка отправки сообщения контакту ${contact.name}: $error")
                        }
                    }
                } else {
                    Log.e("MainActivity", "Не удалось создать чат с контактом ${contact.name}")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка при отправке сообщения контакту ${contact.name}: ${e.message}")
        }
    }

    private fun startLiveLocation() {
        ioScope.launch {
            while (MainActivity.isActive) {
                sendLiveLocation()
                delay(60_000)   // 1 минута
            }
        }
    }

    private fun sendLiveLocation(){
        ioScope.launch {
            try {
                // Используем флаги активных плиток
                val sendToGroup = isTileSendToGroupActive
                val sendToContacts = isTileSendToContactsActive && selectedContacts.isNotEmpty()
                
                // Если ни одно направление не активно - выходим
                if (!sendToGroup && !sendToContacts) {
                    return@launch
                }
                
                val location = getFreshLocation()
                val token = getTelegramBotToken()
                if (location == null) return@launch

                // 1) Группа (Bot API) — только если плитка группы активна
                if (sendToGroup && token.isNotBlank()) {
                    val chatId = getResolvedBotChatId(token)
                    if (liveLocationMessageId == null) {
                        val url = "https://api.telegram.org/bot${token}" +
                                "/sendLocation?chat_id=${chatId}" +
                                "&latitude=${location.latitude}" +
                                "&longitude=${location.longitude}" +
                                "&live_period=${86400}"
                        val response =
                            OkHttpClient().newCall(Request.Builder().url(url).get().build()).execute()
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            val messageIdRegex = """"message_id":(\d+)""".toRegex()
                            val matchResult = messageIdRegex.find(responseBody)
                            if (matchResult != null && matchResult.groupValues.size > 1) {
                                liveLocationMessageId = matchResult.groupValues[1].toInt()
                            }
                        }
                        response.close()
                    } else {
                        val url = "https://api.telegram.org/bot${token}" +
                                "/editMessageLiveLocation" +
                                "?chat_id=${chatId}" +
                                "&message_id=${liveLocationMessageId}" +
                                "&latitude=${location.latitude}" +
                                "&longitude=${location.longitude}"
                        OkHttpClient().newCall(Request.Builder().url(url).get().build()).execute().close()
                    }
                }

                // 2) Контакты (TDLib) — только если плитка контактов активна
                if (sendToContacts && telegramAuthHelper.isAuthenticated()) {
                    val lat = location.latitude
                    val lon = location.longitude
                    for (contact in selectedContacts) {
                        val existing = contactLiveLocationState[contact.id]
                        val chatId = existing?.first ?: run {
                            suspendCoroutine<Long?> { cont ->
                                telegramAuthHelper.createPrivateChat(contact.id) { chat ->
                                    cont.resume(chat)
                                }
                            }
                        } ?: continue

                        val messageId = existing?.second
                        val updatedId = suspendCoroutine<Long?> { cont ->
                            telegramAuthHelper.sendOrUpdateLiveLocation(
                                chatId = chatId,
                                messageId = messageId,
                                latitude = lat,
                                longitude = lon,
                                livePeriodSeconds = 86400,
                                callback = { mid -> cont.resume(mid) }
                            )
                        }
                        if (updatedId != null) {
                            contactLiveLocationState[contact.id] = chatId to updatedId
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Live location update failed: ${e.message}")
            }
        }
    }

    private fun stopLiveLocation() {
        ioScope.launch {
            try {
                // 1) Останавливаем live location в группе (если она была запущена)
                val token = getTelegramBotToken()
                if (token.isNotBlank() && liveLocationMessageId != null) {
                    val chatId = getResolvedBotChatId(token)
                    val location = getFreshLocation()
                    val lat = location?.latitude
                    val lon = location?.longitude
                    val url = "https://api.telegram.org/bot${token}" +
                            "/stopMessageLiveLocation" +
                            "?chat_id=${chatId}" +
                            "&message_id=${liveLocationMessageId}" +
                            (if (lat != null) "&latitude=$lat" else "") +
                            (if (lon != null) "&longitude=$lon" else "")
                    OkHttpClient().newCall(Request.Builder().url(url).get().build()).execute().close()
                    Log.d("MainActivity", "Live location остановлен для сообщения ID: $liveLocationMessageId")
                }
                liveLocationMessageId = null

                // 2) Останавливаем live location для контактов (TDLib)
                if (contactLiveLocationState.isNotEmpty() && telegramAuthHelper.isAuthenticated()) {
                    val snapshot = contactLiveLocationState.toMap()
                    contactLiveLocationState.clear()
                    snapshot.values.forEach { (chatId, messageId) ->
                        try {
                            telegramAuthHelper.stopLiveLocation(chatId, messageId)
                        } catch (_: Exception) {}
                    }
                } else {
                    contactLiveLocationState.clear()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка при остановке live location: ${e.message}")
                // В случае ошибки просто сбрасываем ID
                liveLocationMessageId = null
                contactLiveLocationState.clear()
            }
        }
    }

    private fun loadRtmpSettings() {
        val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
        rtmpUrl = sharedPrefs.getString("rtmp_url", "").toString() // Возвращаем RTMPS
        rtmpStreamKey = sharedPrefs.getString("rtmp_stream_key", "").toString()
        fullRtmpUrl = "$rtmpUrl/$rtmpStreamKey"
    }
    
    private fun loadUserSettings() {
        val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
        userName = sharedPrefs.getString("first_name", "") ?: ""
        userCar = sharedPrefs.getString("car_brand", "") ?: ""
        userCarColor = sharedPrefs.getString("car_color", "") ?: ""
    }

    private fun showRtmpSettingsDialog(returnToMainMenu: Boolean = false) {
        // Отображаем диалог с прогрессбаром пока загружаем каналы
        val progressDialog = ProgressDialog(this).apply {
            setMessage("Загрузка списка каналов...")
            setCancelable(false)
            show()
        }
        
        // Загружаем список каналов
        fetchChannelsFromSheet { success ->
            progressDialog.dismiss()
            
            if (success && channelsList.isNotEmpty()) {
                showChannelSelectionDialog(returnToMainMenu)
            } else {
                Toast.makeText(this, "Не удалось загрузить список каналов", Toast.LENGTH_LONG).show()
                // Если не удалось загрузить каналы, но нужно вернуться в основное меню
                if (returnToMainMenu) {
                    showSettingsDialog()
                }
            }
        }
    }
    
    private fun fetchChannelsFromSheet(callback: (Boolean) -> Unit) {
        channelsList.clear()
        
        ioScope.launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(SPREADSHEET_URL)
                    .build()
                
                val response = client.newCall(request).execute()
                val csvData = response.body?.string()
                
                if (csvData != null) {
                    // Обрабатываем CSV данные
                    val lines = csvData.lines()
                    // Пропускаем заголовок (первую строку)
                    for (i in 1 until lines.size) {
                        val line = lines[i].trim()
                        if (line.isNotEmpty()) {
                            val columns = line.split(",")
                            if (columns.size >= 3) {
                                val name = columns[0].trim()
                                val url = columns[1].trim()
                                val key = columns[2].trim()
                                channelsList.add(ChannelInfo(name, url, key))
                            }
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        callback(true)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        callback(false)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка при загрузке каналов: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback(false)
                }
            }
        }
    }
    
    private fun showChannelSelectionDialog(returnToMainMenu: Boolean = false) {
        val dialogLayout = LinearLayout(this)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(50, 30, 50, 30)

        // Заголовок
        val titleTextView = TextView(this)
        titleTextView.text = "Выберите канал"
        titleTextView.textSize = 18f
        titleTextView.setPadding(0, 0, 0, 30)
        dialogLayout.addView(titleTextView)

        // Создаем адаптер с названиями каналов
        val channelNames = channelsList.map { it.name }.toTypedArray()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, channelNames)
        
        // Список каналов
        val listView = ListView(this)
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE
        
        // Находим текущий выбранный канал
        var selectedChannelIndex = -1
        for (i in channelsList.indices) {
            if (channelsList[i].url == rtmpUrl && channelsList[i].key == rtmpStreamKey) {
                selectedChannelIndex = i
                break
            }
        }
        
        // Выделяем текущий канал если он найден
        if (selectedChannelIndex >= 0) {
            listView.setItemChecked(selectedChannelIndex, true)
        }
        
        dialogLayout.addView(listView)

        // Создаем и отображаем диалог
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogLayout)
            .setPositiveButton("Сохранить") { _, _ ->
                val checkedPosition = listView.checkedItemPosition
                if (checkedPosition != ListView.INVALID_POSITION) {
                    val selectedChannel = channelsList[checkedPosition]
                    rtmpUrl = selectedChannel.url // Возвращаем RTMPS
                    rtmpStreamKey = selectedChannel.key
                    fullRtmpUrl = "$rtmpUrl/$rtmpStreamKey"
                    
                    // Сохраняем настройки
                    val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                    sharedPrefs.edit()
                        .putString("rtmp_url", rtmpUrl)
                        .putString("rtmp_stream_key", rtmpStreamKey)
                        .putString("channel_name", selectedChannel.name) // Сохраняем название канала
                        .apply()
                    
                    Toast.makeText(this, getString(R.string.selected_channel_toast_format, selectedChannel.name), Toast.LENGTH_SHORT).show()
                    
                    // Возвращаемся в основное меню настроек если нужно
                    if (returnToMainMenu) {
                        showSettingsDialog()
                    }
                }
            }
            .setNegativeButton("Отмена") { _, _ ->
                // Возвращаемся в основное меню настроек если нужно
                if (returnToMainMenu) {
                    showSettingsDialog()
                }
            }
            .create()
        
        // Центрирование заголовка и применение жёлтого цвета к кнопкам
        dialog.setOnShowListener {
            val titleView = dialog.findViewById<TextView>(android.R.id.title)
            titleView?.gravity = android.view.Gravity.CENTER
            applyYellowColorToDialogButtons(dialog)
        }
        
        dialog.show()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            root.alpha = 0f
        } else {
            root.alpha = 1f
        }
    }

    private suspend fun <T> Task<T>.await(): T? {
        return suspendCancellableCoroutine { continuation ->
            this.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    continuation.resume(task.result, null)
                } else {
                    continuation.resume(null, null)
                }
            }
        }
    }

    override fun onConnectionStarted(url: String) {
        Log.d("MainActivity", "RTMP соединение начато: $url")
    }

    override fun onAuthError() {
        Log.e("MainActivity", "Ошибка авторизации RTMP")
    }

    override fun onAuthSuccess() {
        Log.d("MainActivity", "Успешная авторизация RTMP")
    }

    override fun onDisconnect() {
        Log.d("MainActivity", "RTMP соединение закрыто")
    }

    override fun onConnectionSuccess() {
        Log.d("MainActivity", "RTMP соединение успешно установлено")
    }

    override fun onConnectionFailed(reason: String) {
        Log.e("MainActivity", "RTMP соединение прервано: $reason")
    }

    // Метод для запуска авторизации в Telegram
    private fun startTelegramAuth() {
        // Показываем диалог авторизации через TDLib
        TelegramAuthDialogs.showTelegramLoginDialog(
            context = this,
            authHelper = telegramAuthHelper,
            onAuthSuccess = { userData ->
                // Успешная авторизация через TDLib
                telegramUserId = userData.id
                telegramUserName = "${userData.first_name}${userData.last_name?.let { " $it" } ?: ""}"
                telegramUserPhone = userData.phone_number
                
                // Сохраняем данные в SharedPreferences
                val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                sharedPrefs.edit()
                    .putLong("telegram_user_id", userData.id)
                    .putString("telegram_user_name", telegramUserName)
                    .putString("telegram_username", userData.username)
                    .putString("telegram_phone", userData.phone_number)
                    .apply()
                
                Toast.makeText(this, "Успешная авторизация через Telegram! Добро пожаловать, ${userData.first_name}!", Toast.LENGTH_SHORT).show()
                
                // Загружаем контакты
                loadTelegramContacts()
                
                // Обновляем диалог настроек
                showSettingsDialog()
            },
            onAuthError = { error ->
                // Ошибка авторизации
                TelegramAuthDialogs.showAuthErrorDialog(this, error) {
                    // Повторная попытка
                    startTelegramAuth()
                }
            }
        )
    }
    
    // Метод для загрузки контактов
    private fun loadTelegramContacts() {
        Log.d("MainActivity", "loadTelegramContacts: начинаем загрузку контактов")
        Log.d("MainActivity", "loadTelegramContacts: isAuthenticated = ${telegramAuthHelper.isAuthenticated()}")
        
        if (!telegramAuthHelper.isAuthenticated()) {
            Log.w("MainActivity", "Пользователь не авторизован, запускаем авторизацию")
            Toast.makeText(this, getString(R.string.telegram_auth_required), Toast.LENGTH_SHORT).show()
            startTelegramAuth()
            return
        }
        
        telegramAuthHelper.getContacts()
        // Контакты будут получены через callback onContactsReceived
    }

    // Метод для выхода из аккаунта Telegram
    private fun logoutFromTelegram() {
        TelegramAuthDialogs.showLogoutConfirmDialog(this) {
            telegramAuthHelper.logout()
            
            // Очищаем локальные данные
            telegramUserId = null
            telegramUserName = null
            telegramUserPhone = null
            telegramContacts.clear()
            selectedContacts.clear()
            
            // Очищаем все связанные с Telegram данные
            val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
            val telegramPrefs = getSharedPreferences("telegram_auth_prefs", Context.MODE_PRIVATE)
            
            sharedPrefs.edit()
                .remove("telegram_user_id")
                .remove("telegram_user_name")
                .remove("telegram_user_phone")
                .remove("selected_contacts")
                .apply()
                
            telegramPrefs.edit()
                .clear()
                .apply()
            
            Toast.makeText(this, getString(R.string.logout_done), Toast.LENGTH_SHORT).show()
            
            // Принудительно обновляем диалог настроек через небольшую задержку
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                showSettingsDialog()
            }, 100)
        }
    }

    // Метод для показа диалога выбора контактов
    private fun showContactsSelectionDialog() {
        Log.d("MainActivity", "showContactsSelectionDialog: telegramContacts.size = ${telegramContacts.size}")
        
        if (telegramContacts.isEmpty()) {
            Log.w("MainActivity", "Контакты пусты, пытаемся загрузить заново")
            Toast.makeText(this, getString(R.string.loading_contacts), Toast.LENGTH_SHORT).show()
            loadTelegramContacts()
            return
        }
        
        val dialogLayout = LinearLayout(this)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(50, 30, 50, 30)
        
        // Заголовок
        val titleTextView = TextView(this)
        titleTextView.text = "Выберите контакты"
        titleTextView.textSize = 18f
        titleTextView.setPadding(0, 0, 0, 20)
        dialogLayout.addView(titleTextView)
        
        // Поле поиска
        val searchInput = EditText(this)
        searchInput.hint = "Поиск контактов..."
        searchInput.setPadding(20, 15, 20, 15)
        val searchParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        searchParams.setMargins(0, 0, 0, 40)
        searchInput.layoutParams = searchParams
        dialogLayout.addView(searchInput)
        
        // Создаем список контактов с чекбоксами
        val contactsListView = ListView(this)
        var filteredContacts = telegramContacts.toMutableList()
        
        // Функция для обновления списка контактов
        fun updateContactsList(query: String = "") {
            Log.d("MainActivity", "updateContactsList: query='$query', selectedContacts.size=${selectedContacts.size}")
            
            // Сначала сохраняем текущее состояние выбора из ListView
            try {
                if (filteredContacts.isNotEmpty() && contactsListView.count > 0) {
                    for (i in 0 until contactsListView.count) {
                        val contact = filteredContacts[i]
                        val isChecked = contactsListView.isItemChecked(i)
                        
                        if (isChecked) {
                            // Добавляем в выбранные, если его там нет
                            if (!selectedContacts.any { it.id == contact.id }) {
                                selectedContacts.add(contact)
                            }
                        } else {
                            // Удаляем из выбранных
                            selectedContacts.removeAll { it.id == contact.id }
                        }
                    }
                }
            } catch (e: Exception) {
                // Игнорируем ошибки при первой инициализации
            }
            
            filteredContacts = if (query.isEmpty()) {
                telegramContacts.toMutableList()
            } else {
                telegramContacts.filter { 
                    it.name.contains(query, ignoreCase = true) || 
                    it.phone.contains(query, ignoreCase = true) 
                }.toMutableList()
            }
            
            Log.d("MainActivity", "updateContactsList: filteredContacts.size=${filteredContacts.size}")
            
            val contactItems = Array(filteredContacts.size) { i -> filteredContacts[i].name }
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, contactItems)
            contactsListView.adapter = adapter
            contactsListView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
            
            // Отмечаем ранее выбранные контакты
            for (i in filteredContacts.indices) {
                val contact = filteredContacts[i]
                val isSelected = selectedContacts.any { it.id == contact.id }
                if (isSelected) {
                    contactsListView.setItemChecked(i, true)
                    Log.d("MainActivity", "updateContactsList: отмечен контакт ${contact.name} на позиции $i")
                }
            }
        }
        
        // Инициализируем список
        updateContactsList()
        
        // Добавляем слушатель для поиска
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateContactsList(s.toString())
            }
        })
        
        dialogLayout.addView(contactsListView)
        
        // Добавляем слушатель для отслеживания изменений выбора в реальном времени
        contactsListView.setOnItemClickListener { _, _, position, _ ->
            val contact = filteredContacts[position]
            val isChecked = contactsListView.isItemChecked(position)
            
            Log.d("MainActivity", "Клик по контакту ${contact.name} на позиции $position, выбран: $isChecked")
            
            if (isChecked) {
                // Добавляем контакт в выбранные, если его там еще нет
                if (!selectedContacts.any { it.id == contact.id }) {
                    selectedContacts.add(contact)
                    Log.d("MainActivity", "Добавлен контакт ${contact.name}, всего выбрано: ${selectedContacts.size}")
                }
            } else {
                // Удаляем контакт из выбранных
                selectedContacts.removeAll { it.id == contact.id }
                Log.d("MainActivity", "Удален контакт ${contact.name}, всего выбрано: ${selectedContacts.size}")
            }
        }

        // Создаем и отображаем диалог
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogLayout)
            .setPositiveButton("Сохранить") { _, _ ->
                // Сохраняем ID выбранных контактов
                val selectedIds = selectedContacts.map { it.id }.toLongArray()
                val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                sharedPrefs.edit()
                    .putString("selected_contacts", selectedIds.joinToString(","))
                    .apply()
                
                Log.d("MainActivity", "Сохранено ${selectedContacts.size} контактов: ${selectedContacts.map { it.name }}")
                
                // Возвращаемся в диалог настроек
                showSettingsDialog()
            }
            .setNegativeButton("Отмена") { _, _ ->
                // Ничего не делаем, просто закрываем диалог
            }
            .create()
        
        // Центрирование заголовка и применение жёлтого цвета к кнопкам
        dialog.setOnShowListener {
            val titleView = dialog.findViewById<TextView>(android.R.id.title)
            titleView?.gravity = android.view.Gravity.CENTER
            applyYellowColorToDialogButtons(dialog)
        }
        
        dialog.show()
    }

    // Инициализация Telegram авторизации (вызывается только один раз)
    private fun initializeTelegramAuth() {
        // Инициализируем TelegramAuthHelper с колбэком
        telegramAuthHelper.init(object : TelegramAuthHelper.AuthCallback {
            override fun onAuthStateChanged(state: TelegramAuthHelper.AuthState) {
                when (state) {
                    TelegramAuthHelper.AuthState.AUTHENTICATED -> {
                        // Пользователь уже авторизован, загружаем контакты
                        loadTelegramContacts()
                    }
                    TelegramAuthHelper.AuthState.NOT_AUTHENTICATED -> {
                        // Пользователь не авторизован
                        telegramUserId = null
                        telegramUserName = null
                        telegramUserPhone = null
                        telegramContacts.clear()
                        selectedContacts.clear()
                    }
                    TelegramAuthHelper.AuthState.ERROR -> {
                        // Ошибка авторизации, очищаем данные
                        telegramUserId = null
                        telegramUserName = null
                        telegramUserPhone = null
                        telegramContacts.clear()
                        selectedContacts.clear()
                    }

                    else -> {
                        // Другие состояния (ожидание ввода телефона, кода, пароля)
                    }
                }
            }
            
            override fun onUserDataReceived(userData: TelegramAuthHelper.TelegramAuthData) {
                // Сохраняем данные пользователя
                telegramUserId = userData.id
                telegramUserName = "${userData.first_name}${userData.last_name?.let { " $it" } ?: ""}"
                telegramUserPhone = userData.phone_number
            }
            
            override fun onContactsReceived(contacts: List<TelegramAuthHelper.TelegramContact>) {
                Log.d("MainActivity", "onContactsReceived: получено ${contacts.size} контактов")
                
                // Обновляем список контактов
                telegramContacts.clear()
                contacts.forEach { contact ->
                    Log.d("MainActivity", "Контакт: ${contact.name} (${contact.phone})")
                    telegramContacts.add(
                        TelegramContact(
                            id = contact.id,
                            name = contact.name,
                            phone = contact.phone
                        )
                    )
                }
                
                Log.d("MainActivity", "onContactsReceived: telegramContacts.size = ${telegramContacts.size}")
                
                // Загружаем выбранные контакты из настроек
                val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                val selectedContactIds = sharedPrefs.getString("selected_contacts", "")?.split(",")?.mapNotNull { 
                    it.toLongOrNull() 
                } ?: listOf()
                
                // Восстанавливаем выбранные контакты
                selectedContacts.clear()
                for (contact in telegramContacts) {
                    if (selectedContactIds.contains(contact.id)) {
                        selectedContacts.add(contact)
                    }
                }
            }
            
            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, getString(R.string.telegram_error_format, error), Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    // Добавим метод для проверки разрешений перед запуском авторизации
    private fun checkTelegramAuthPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val permissions = arrayOf(
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            
            val missingPermissions = permissions.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            
            if (missingPermissions.isNotEmpty()) {
                requestPermissions(missingPermissions, TELEGRAM_AUTH_PERMISSION_REQUEST_CODE)
            } else {
                // Все разрешения уже предоставлены, запускаем авторизацию
                startTelegramAuth()
            }
        } else {
            // Для API < 23 разрешения уже предоставлены в манифесте
            startTelegramAuth()
        }
    }

    private fun handleQuickTileIntent() {
        // Quick Settings Tiles доступны только с API 24+
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return
        }
        
        val intent = intent
        
        // Проверяем, есть ли параметры от Quick Settings Tiles
        val quickTileMode = intent.getStringExtra("quick_tile_mode")
        val autoStart = intent.getBooleanExtra("auto_start", false)
        val quickTileAction = intent.getStringExtra("quick_tile_action")
        val tileAction = intent.getStringExtra("tile_action")
        val tileValue = intent.getBooleanExtra("tile_value", false)
        
        when {
            // Обработка новых плиток (3 независимых переключателя)
            tileAction != null -> {
                handleTileToggle(tileAction, tileValue)
            }
            quickTileAction == "stop_and_close" -> {
                Log.d("MainActivity", "Получен сигнал закрытия через плитку")
                isClosingFromTile = true

                // Останавливаем приложение (НЕ блокируем UI: финализация/отправка последнего сегмента идёт в фоне)
                if (isActive) {
                    Log.d("MainActivity", "Останавливаем активность")
                    stop()
                    // Не закрываем активити сразу, даем время на отправку
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try { finishAndRemoveTask() } catch (_: Exception) {}
                    }, 1200)
                }
                // Сбрасываем состояние плиток позже, вместе с закрытием
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        StreamingTileService.setTileState(this, false)
                        VideoSegmentsTileService.setTileState(this, false)
                        // Сбрасываем новые плитки
                        SendToGroupTileService.setTileState(this, false)
                        SendToContactsTileService.setTileState(this, false)
                        SaveToGalleryTileService.setTileState(this, false)
                    } catch (_: Exception) {}
                    // Не уничтожаем singleton TelegramAuthHelper, так как он может использоваться в других Activity
                }, 1000)
            }
            quickTileMode != null && autoStart -> {
                // Проверяем, не запущено ли уже приложение
                if (isActive) {
                    Log.d("MainActivity", "Приложение уже активно, переключаем режим")
                    // Останавливаем текущий режим
                    stop()
                }
                
                // Устанавливаем режим работы
                val mode = when (quickTileMode) {
                    "RTMP_STREAMING" -> WorkMode.RTMP_STREAMING
                    "VIDEO_SEGMENTS" -> WorkMode.VIDEO_SEGMENTS
                    else -> WorkMode.RTMP_STREAMING
                }
                
                // Проверяем готовность Surface
                if (isSurfaceReady) {
                    // Surface готов, запускаем сразу
                    currentWorkMode = mode
                    if (!isActive) {
                        start()
                    }
                } else {
                    // Surface не готов, откладываем запуск
                    Log.d("MainActivity", "Surface не готов, откладываем автостарт")
                    pendingAutoStart = true
                    pendingWorkMode = mode
                }
            }
        }
    }
    
    /**
     * Обрабатывает переключение отдельных плиток
     */
    private fun handleTileToggle(action: String, value: Boolean) {
        Log.d("MainActivity", "handleTileToggle: action=$action, value=$value")
        
        // Запоминаем, была ли это новая активация плитки (для отправки инфо-сообщения)
        var newGroupActivated = false
        var newContactsActivated = false
        
        when (action) {
            "toggle_send_to_group" -> {
                // Если активируем плитку группы - сбрасываем её счётчик и сообщения
                if (value && !isTileSendToGroupActive) {
                    sentSegmentCountGroup = 0
                    botGroupMessageIds.clear()
                    newGroupActivated = true
                    Log.d("MainActivity", "Сброшен счётчик сегментов для группы")
                }
                isTileSendToGroupActive = value
                Log.d("MainActivity", "Плитка 'В группу' = $value")
            }
            "toggle_send_to_contacts" -> {
                // Если активируем плитку контактов - сбрасываем её счётчик и сообщения
                if (value && !isTileSendToContactsActive) {
                    sentSegmentCountContacts = 0
                    contactMessageIds.clear()
                    newContactsActivated = true
                    Log.d("MainActivity", "Сброшен счётчик сегментов для контактов")
                }
                isTileSendToContactsActive = value
                Log.d("MainActivity", "Плитка 'Контактам' = $value")
            }
            "toggle_save_to_gallery" -> {
                isTileSaveToGalleryActive = value
                Log.d("MainActivity", "Плитка 'В галерею' = $value")
            }
        }
        
        // Проверяем, есть ли хотя бы одна активная плитка
        val anyTileActive = isTileSendToGroupActive || isTileSendToContactsActive || isTileSaveToGalleryActive
        
        Log.d("MainActivity", "Состояние плиток: group=$isTileSendToGroupActive, contacts=$isTileSendToContactsActive, gallery=$isTileSaveToGalleryActive, anyActive=$anyTileActive")
        
        if (anyTileActive) {
            // Если есть хотя бы одна активная плитка - запускаем запись (если еще не запущена)
            if (!isActive) {
                Log.d("MainActivity", "Запускаем запись (активирована плитка)")
                currentWorkMode = WorkMode.VIDEO_SEGMENTS
                
                if (isSurfaceReady) {
                    start()
                } else {
                    Log.d("MainActivity", "Surface не готов, откладываем автостарт")
                    pendingAutoStart = true
                    pendingWorkMode = WorkMode.VIDEO_SEGMENTS
                }
            } else {
                // Приложение уже активно - отправляем инфо-сообщение и геолокацию для новой плитки
                if (newGroupActivated || newContactsActivated) {
                    Log.d("MainActivity", "Приложение уже активно, отправляем инфо для новой плитки: group=$newGroupActivated, contacts=$newContactsActivated")
                    ioScope.launch {
                        // Отправляем инфо-сообщение для новых направлений
                        sendUserInfoMessageForNewTile(newGroupActivated, newContactsActivated)
                        // Геолокация уже работает в цикле startLiveLocation, она сама проверяет флаги
                    }
                }
            }
        } else {
            // Если все плитки выключены - останавливаем запись
            if (isActive) {
                Log.d("MainActivity", "Останавливаем запись (все плитки выключены)")
                isClosingFromTile = true
                stop()
                
                // Закрываем приложение с задержкой
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try { finishAndRemoveTask() } catch (_: Exception) {}
                }, 1200)
            }
        }
    }
    
    /**
     * Отправляет инфо-сообщение только для вновь активированных направлений
     */
    private suspend fun sendUserInfoMessageForNewTile(toGroup: Boolean, toContacts: Boolean) {
        if (!toGroup && !toContacts) return
        
        try {
            // Получаем данные из настроек
            val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
            val registrationNumber = sharedPrefs.getString("registration_number", "") ?: ""
            val taxiNumber = sharedPrefs.getString("taxi_number", "") ?: ""
            val telegramUsername = sharedPrefs.getString("telegram_username", null)
            
            // Формируем информационное сообщение о пользователе
            var userInfoMessage = getString(R.string.sos_start_header) + "\n\n"
            
            // 1. Только Имя (без фамилии)
            if (userName.isNotEmpty()) {
                userInfoMessage += "👤 $userName\n"
            }
            
            // 2. Ссылка на профиль в Telegram
            telegramUsername?.let { username ->
                userInfoMessage += "📱 @$username\n"
            }
            
            // 3. Машина и цвет
            val carInfo = mutableListOf<String>()
            if (userCar.isNotEmpty()) carInfo.add(userCar)
            if (userCarColor.isNotEmpty()) carInfo.add(userCarColor)
            if (carInfo.isNotEmpty()) {
                userInfoMessage += "${carInfo.joinToString(", ")}\n"
            }
            
            // 4. Регистрационный номер
            if (registrationNumber.isNotEmpty()) {
                val formattedReg = "<b><u>${htmlEscape(registrationNumber)}</u></b>"
                userInfoMessage += "🚗 ${getString(R.string.registration_number)}: $formattedReg\n"
            }

            // 5. Бортовой номер
            if (taxiNumber.isNotEmpty()) {
                val formattedTaxi = "<b><u>${htmlEscape(taxiNumber)}</u></b>"
                userInfoMessage += "🚕 ${getString(R.string.taxi_board_number)}: $formattedTaxi\n"
            }
            
            // Режим работы
            userInfoMessage += "\n📹 ${getString(R.string.mode_label)}: "
            userInfoMessage += getString(R.string.mode_video_desc)
            val segmentDurationSec = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
                .getInt(KEY_SEGMENT_DURATION_SECONDS, MIN_SEGMENT_DURATION_SECONDS)
                .coerceIn(MIN_SEGMENT_DURATION_SECONDS, MAX_SEGMENT_DURATION_SECONDS)
            val formattedDuration = formatSegmentDuration(segmentDurationSec)
            userInfoMessage += "\n" + getString(R.string.segment_duration_prefix, formattedDuration)
            
            if (toContacts && selectedContacts.isNotEmpty()) {
                userInfoMessage += "\n\n📱 ${getString(R.string.selected_contacts_sending_info)}"
            }
            
            userInfoMessage += "\n📅 ${getString(R.string.date_label)}: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
            
            // Отправляем в группу (если это новая активация группы)
            if (toGroup) {
                try {
                    val token = getTelegramBotToken()
                    if (token.isNotBlank()) {
                        val chatId = getResolvedBotChatId(token)
                        val messageBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("chat_id", chatId)
                            .addFormDataPart("text", userInfoMessage)
                            .addFormDataPart("parse_mode", "HTML")
                            .build()

                        val messageUrl = "https://api.telegram.org/bot${token}/sendMessage"
                        val messageRequest = Request.Builder()
                            .url(messageUrl)
                            .post(messageBody)
                            .build()

                        OkHttpClient.Builder()
                            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                            .newCall(messageRequest)
                            .execute()
                            .close()
                        
                        Log.d("MainActivity", "Инфо-сообщение отправлено в группу (новая активация)")
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Ошибка отправки инфо-сообщения в группу: ${e.message}")
                }
            }

            // Отправляем контактам (если это новая активация контактов)
            if (toContacts && selectedContacts.isNotEmpty() && telegramAuthHelper.isAuthenticated()) {
                try {
                    selectedContacts.forEach { contact ->
                        sendMessageToContact(contact, userInfoMessage)
                    }
                    Log.d("MainActivity", "Инфо-сообщение отправлено контактам (новая активация)")
                } catch (e: Exception) {
                    Log.e("MainActivity", "Ошибка отправки инфо-сообщения контактам: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка формирования информационного сообщения для новой плитки: ${e.message}")
        }
    }

    /**
     * Проверка, включена ли служба специальных возможностей
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${VolumeButtonAccessibilityService::class.java.canonicalName}"
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service) == true
    }

    /**
     * Открытие настроек специальных возможностей
     */
    private fun openAccessibilitySettings() {
        try {
            // Сохраняем флаг что диалог настроек был открыт
            val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("settings_dialog_open", true).apply()
            
            Log.d("MainActivity", "openAccessibilitySettings: Флаг settings_dialog_open установлен в TRUE, dialog?.isShowing=${dialog?.isShowing}")
            
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            
            Toast.makeText(
                this,
                getString(R.string.accessibility_enable_instructions),
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка открытия настроек специальных возможностей: ${e.message}")
            Toast.makeText(this, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
        }
    }

    // Открытие селектора BLE: пока что упрощённо — запрашиваем адрес у пользователя через ввод
    // (дальше можно заменить на полноценный сканер BLE)
    private fun openBleSelector() {
        // Не используется — заменено на BleDevicePickerActivity
    }

    private fun showBleConnectedDevicesDialog(onDeviceSelected: ((String) -> Unit)? = null) {
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val connected = try {
                bm.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
            } catch (e: SecurityException) {
                emptyList<android.bluetooth.BluetoothDevice>()
            }
            val items = connected
                .filter { it.type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE || it.type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL }
                .map { (it.name ?: getString(R.string.device_no_name)) + " | " + it.address }
                .toTypedArray()

            val builder = androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.ble_devices_dialog_title))
                .setItems(if (items.isNotEmpty()) items else arrayOf(getString(R.string.no_ble_devices))) { dialog, which ->
                    if (items.isEmpty()) return@setItems
                    val addr = items[which].substringAfterLast(" | ")
                    val name = items[which].substringBeforeLast(" | ")
                    
                    Log.d(TAG, "📱 Выбрано BLE-устройство: $name ($addr)")
                    
                    getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
                        .edit()
                        .putString("ble_device_address", addr) // ИСПРАВЛЕН КЛЮЧ!
                        .putString("ble_device_name", name)
                        .putBoolean("ble_device_connected", false) // Будет обновлено через BluetoothReceiver
                        .apply()
                    
                    Toast.makeText(this, getString(R.string.ble_device_saved, "$name ($addr)"), Toast.LENGTH_LONG).show()
                    
                    // Перезапускаем сервис
                    BluetoothButtonService.stop(this)
                    BluetoothButtonService.start(this)
                    
                    // Вызываем callback для обновления UI
                    onDeviceSelected?.invoke(name)
                    
                    dialog.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel), null)

            val dlg = builder.create()
            dlg.setOnShowListener {
                val w = dlg.window
                w?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
                applyYellowColorToDialogButtons(dlg)
            }
            dlg.show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_showing_devices, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2000) {
            // Включение Bluetooth (если запрашивали)
            return
        }
        if (requestCode == 2003 && resultCode == RESULT_OK) {
            val addr = data?.getStringExtra("ble_address")?.trim()
            val name = data?.getStringExtra("ble_name")?.trim() ?: getString(R.string.device_no_name)
            if (!addr.isNullOrEmpty()) {
                getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
                    .edit()
                    .putString("ble_device_address", addr) // ИСПРАВЛЕН КЛЮЧ!
                    .putString("ble_device_name", name)
                    .putBoolean("ble_device_connected", false)
                    .apply()
                Toast.makeText(this, getString(R.string.ble_device_saved, "$name ($addr)"), Toast.LENGTH_LONG).show()
                BluetoothButtonService.stop(this)
                BluetoothButtonService.start(this)
            }
        }
    }

    // Перегрузка onRequestPermissionsResult уже реализована выше (под TELEGRAM/общие разрешения). Дублирующая удалена.

    /**
     * Применяет жёлтый цвет ко всем кнопкам диалога
     */
    private fun applyYellowColorToDialogButtons(dialog: androidx.appcompat.app.AlertDialog) {
        val yellowColor = getColor(R.color.yellow)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(yellowColor)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(yellowColor)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(yellowColor)
    }

}