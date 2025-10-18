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
    private var userLastName: String = ""
    private var userCar: String = ""
    private var userCarColor: String = ""
    
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

        const val TELEGRAM_BOT_TOKEN = "7960834986:AAGr7DfkvN2cRi2FWWqKMVhbmIbu9li6SFE"
        const val TELEGRAM_CHAT_ID = "-4706227781"
        
        // Добавляем константы для запроса разрешений
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val TELEGRAM_AUTH_PERMISSION_REQUEST_CODE = 1002

      // Настройки длительности видеосегментов
      private const val PREFS_TAXI = "taxi_sos_prefs"
      private const val KEY_SEGMENT_DURATION_SECONDS = "segment_duration_seconds"
      private const val KEY_LAST_SENT_FILE_NAME = "last_sent_file_name"
      private const val MIN_SEGMENT_DURATION_SECONDS = 10
      private const val MAX_SEGMENT_DURATION_SECONDS = 300
    }
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var liveLocationMessageId: Int? = null
    private var autoStopJob: Job? = null

    // Добавим свойство для телеграм-авторизации в класс MainActivity
    private lateinit var telegramAuthHelper: TelegramAuthHelper

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
        
        // Инициализируем помощник авторизации Telegram только если он еще не инициализирован
        if (!::telegramAuthHelper.isInitialized && !isClosingFromTile) {
            telegramAuthHelper = TelegramAuthHelper(this)
            // Инициализируем TelegramAuthHelper только один раз
            initializeTelegramAuth()
        } else {
            Log.d("MainActivity", "TelegramAuthHelper уже инициализирован или приложение закрывается")
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

        startStopButton.setOnClickListener {
            if (!isActive) {
                start()
            } else {
                stop()
            }
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

    private fun showSettingsDialog() {
        // Создаем ScrollView для прокрутки
        val scrollView = ScrollView(this)
        val dialogLayout = LinearLayout(this)
        dialogLayout.orientation = LinearLayout.VERTICAL
        dialogLayout.setPadding(50, 30, 50, 30)
        scrollView.addView(dialogLayout)

        // Объявляем переменную dialog заранее
        lateinit var dialog: androidx.appcompat.app.AlertDialog

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
                try { dialog.dismiss() } catch (_: Exception) {}
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

        // Добавляем заголовок "Telegram канал"
        val channelLabel = TextView(this)
        channelLabel.text = getString(R.string.telegram_channel)
        channelLabel.textSize = 16f
        channelLabel.setPadding(0, 0, 0, 10)
        dialogLayout.addView(channelLabel)
        
        // Создаем элемент для отображения текущего канала
        val channelInfoContainer = LinearLayout(this)
        channelInfoContainer.orientation = LinearLayout.HORIZONTAL
        channelInfoContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        
        // Текущий канал (или сообщение, если канал не выбран)
        val currentChannelText = TextView(this)
        currentChannelText.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        )
        
        // Определяем текущий канал
        val currentChannelName = if (rtmpUrl.isEmpty() || rtmpStreamKey.isEmpty()) {
            getString(R.string.channel_not_selected)
        } else {
            // Пытаемся найти имя канала по сохраненным url и key
            val sharedPrefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
            sharedPrefs.getString("channel_name", getString(R.string.unknown_channel))
        }
        
        currentChannelText.text = currentChannelName
        currentChannelText.textSize = 14f
        
        // Значок выбора (только текст "Изменить" без стрелки)
        val selectIcon = TextView(this)
        selectIcon.text = getString(R.string.change)
        selectIcon.textSize = 14f
        
        // Добавляем элементы в контейнер
        channelInfoContainer.addView(currentChannelText)
        channelInfoContainer.addView(selectIcon)
        
        // Делаем весь контейнер кликабельным
        channelInfoContainer.isClickable = true
        channelInfoContainer.isFocusable = true
        
        // Добавляем фон с эффектом при нажатии
        val outValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        channelInfoContainer.setBackgroundResource(outValue.resourceId)
        
        dialogLayout.addView(channelInfoContainer)
        
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
                dialog.dismiss() // Закрываем текущий диалог
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
        val lastName = sharedPrefs.getString("last_name", "") ?: ""
        val registrationNumber = sharedPrefs.getString("registration_number", "") ?: ""
        val taxiNumber = sharedPrefs.getString("taxi_number", "") ?: ""
        val carBrand = sharedPrefs.getString("car_brand", "") ?: ""
        val carColor = sharedPrefs.getString("car_color", "") ?: ""
        val telegramPhone = sharedPrefs.getString("telegram_phone", null)
        
        // Поле для ввода имени
        val firstNameLabel = TextView(this)
        firstNameLabel.text = getString(R.string.first_name) + ":"
        firstNameLabel.textSize = 14f
        dialogLayout.addView(firstNameLabel)
        
        val firstNameInput = EditText(this)
        firstNameInput.setText(firstName)
        firstNameInput.hint = getString(R.string.first_name)
        dialogLayout.addView(firstNameInput)
        
        // Поле для ввода фамилии
        val lastNameLabel = TextView(this)
        lastNameLabel.text = getString(R.string.last_name) + ":"
        lastNameLabel.textSize = 14f
        lastNameLabel.setPadding(0, 10, 0, 0)
        dialogLayout.addView(lastNameLabel)
        
        val lastNameInput = EditText(this)
        lastNameInput.setText(lastName)
        lastNameInput.hint = getString(R.string.last_name)
        dialogLayout.addView(lastNameInput)
        
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
        
        // Неизменяемое поле телефона из Telegram
        if (telegramPhone != null) {
            val phoneLabel = TextView(this)
            phoneLabel.text = getString(R.string.phone_from_telegram) + ":"
            phoneLabel.textSize = 14f
            phoneLabel.setPadding(0, 10, 0, 0)
            dialogLayout.addView(phoneLabel)
            
            val phoneDisplay = TextView(this)
            // Добавляем знак "+" если его нет
            val formattedPhone = if (telegramPhone.startsWith("+")) telegramPhone else "+$telegramPhone"
            phoneDisplay.text = formattedPhone
            phoneDisplay.textSize = 14f
            phoneDisplay.setBackgroundColor(0x10000000) // Легкий серый фон
            phoneDisplay.setPadding(20, 15, 20, 15)
            phoneDisplay.setTextColor(android.graphics.Color.GRAY) // Серый цвет текста
            dialogLayout.addView(phoneDisplay)
        }

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
        
        // Создаем и отображаем диалог с кнопкой "Готово" по центру
        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.done)) { _, _ ->
                // Сохраняем выбранный режим
                currentWorkMode = when (modeSpinner.selectedItemPosition) {
                    0 -> WorkMode.VIDEO_SEGMENTS
                    1 -> WorkMode.RTMP_STREAMING
                    else -> WorkMode.RTMP_STREAMING
                }
                
                // Сохраняем информацию о пользователе
                userName = firstNameInput.text.toString().trim()
                userLastName = lastNameInput.text.toString().trim()
                userCar = carBrandInput.text.toString().trim()
                userCarColor = carColorInput.text.toString().trim()
                
                // Сохраняем в SharedPreferences
                val sharedPrefs = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
                sharedPrefs.edit()
                    .putString("first_name", firstNameInput.text.toString().trim())
                    .putString("last_name", lastNameInput.text.toString().trim())
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
                    .apply()
                
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
                }
            }
            .create()
        
        // Центрирование заголовка диалога
        dialog.setOnShowListener {
            val titleView = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
            titleView?.gravity = android.view.Gravity.CENTER
            
            // Центрирование кнопок
            val positiveButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            val layoutParams = positiveButton.layoutParams as LinearLayout.LayoutParams
            layoutParams.gravity = android.view.Gravity.CENTER
            layoutParams.width = LinearLayout.LayoutParams.WRAP_CONTENT
            positiveButton.layoutParams = layoutParams
            
            // Перемещение кнопки в центр
            val buttonLayout = positiveButton.parent as LinearLayout
            buttonLayout.gravity = android.view.Gravity.CENTER
        }

        // Настраиваем действие для элемента выбора канала
        channelInfoContainer.setOnClickListener {
            dialog.dismiss()
            showRtmpSettingsDialog(true) // Передаем флаг, указывающий, что нужно вернуться в основное меню
        }
        
        dialog.show()
    }

    private fun start() {
        if (isActive) return
        
        Log.d("MainActivity", "Начинаем запуск с режимом $currentWorkMode")
        
        // Проверяем разрешения перед началом
        if (!checkPermissions()) {
            Log.d("MainActivity", "Разрешения не получены, запрашиваем")
            requestPermissions()
            return
        }
        
        Log.d("MainActivity", "Разрешения получены, продолжаем запуск")
        
        isActive = true
        
        // Запускаем в корутине для последовательной отправки сообщений
        ioScope.launch {
            // Сначала отправляем информационное сообщение
            sendUserInfoMessage()
            
            // Затем запускаем геолокацию
            startLiveLocation()
        }
        
        // Всегда запускаем запись видео сегментов для отправки контактам

        
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

        // Планируем авто-остановку через 1 минуту, если пользователь не остановит вручную
        try {
            autoStopJob?.cancel()
        } catch (_: Exception) {}
        autoStopJob = CoroutineScope(Dispatchers.Main).launch {
            delay(60_000)
            if (isActive) {
                Log.d("MainActivity", "Авто-остановка сессии по таймеру 1 минута (эмулируем повторное нажатие на плитку)")
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
        // Отменяем авто-остановку, если была запланирована
        try {
            autoStopJob?.cancel()
            autoStopJob = null
        } catch (_: Exception) {}

      // Принудительно завершаем и отправляем последний сегмент (даже неполный)
      try {
          finalizeLastSegmentBlocking()
      } catch (e: Exception) {
          Log.e("MainActivity", "Ошибка при финализации последнего сегмента: ${e.message}")
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
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка сброса состояния плиток: ${e.message}")
            }
        }
        
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
                      val file = File(getExternalFilesDir(null), "taxi_sos_${ts}_segment${segmentCount}.mp4")
                        
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
                            // Сканируем файл и отправляем
                            MediaScannerConnection.scanFile(this@MainActivity, arrayOf(file.absolutePath), null, null)
                            sendVideo(file)
                            Log.d("MainActivity", "Сегмент $segmentCount отправлен")
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
      var lastFile = currentSegmentFile
      // Если ничего не записывается, нечего завершать
      if (lastFile == null && !isSegmentRecordingActive) {
          // Попробуем найти последний файл по шаблону на случай гонки
          lastFile = tryFindLatestSegmentFile()
          Log.d("MainActivity", "Резервный поиск файла сегмента: ${lastFile?.name}")
      }
      if (lastFile == null) return

      try {
          // Останавливаем запись, если еще идет
          if (isSegmentRecordingActive) {
              try {
                  recordingCamera?.stopRecord()
              } catch (_: Exception) {}
              isSegmentRecordingActive = false
          }

          // Дождаться финализации файла: до 3 секунд, проверяя каждые 200 мс
          var finalizedFile: File? = lastFile
          val deadline = System.currentTimeMillis() + 3000
          while (System.currentTimeMillis() < deadline) {
              val candidate = tryFindLatestSegmentFile() ?: finalizedFile
              if (candidate != null && candidate.exists() && candidate.length() > 0) {
                  finalizedFile = candidate
                  // Небольшая доп. задержка для записи moov/mdat
                  kotlinx.coroutines.delay(200)
                  break
              }
              kotlinx.coroutines.delay(200)
          }

          val fileToSend = finalizedFile
          if (fileToSend != null && fileToSend.exists() && fileToSend.length() > 0) {
              // Сканируем файл
              MediaScannerConnection.scanFile(this, arrayOf(fileToSend.absolutePath), null, null)

              // Отправляем синхронно (блокирующе), чтобы гарантировать отправку до закрытия
              Log.d("MainActivity", "Отправка финального сегмента: ${fileToSend.name}, размер=${fileToSend.length()} байт")
              sendVideoNow(fileToSend)
              Log.d("MainActivity", "Финальный сегмент отправлен: ${fileToSend.name}")
          }
      } finally {
          currentSegmentFile = null
      }
  }

  private fun tryFindLatestSegmentFile(): File? {
      return try {
          val dir = getExternalFilesDir(null) ?: return null
          val files = dir.listFiles { f ->
              f.isFile && f.name.startsWith("taxi_sos_") && f.name.contains("_segment") && f.name.endsWith(".mp4")
          } ?: return null
          files.maxByOrNull { it.lastModified() }
      } catch (_: Exception) { null }
  }

  private suspend fun sendVideoNow(videoFile: File) {
      // Отправка в канал (режим VIDEO_SEGMENTS)
      try {
          if (currentWorkMode == WorkMode.VIDEO_SEGMENTS) {
              withContext(Dispatchers.IO) {
                  val videoRequestBody = videoFile.asRequestBody("video/mp4".toMediaTypeOrNull())
                  val multipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                      .addFormDataPart("chat_id", TELEGRAM_CHAT_ID)
                      .addFormDataPart("video", videoFile.name, videoRequestBody)
                      .build()
                  val url = "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendVideo"
                  val request = Request.Builder()
                      .url(url)
                      .post(multipartBody)
                      .build()
                  val client = OkHttpClient.Builder()
                      .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                      .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                      .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                      .build()
                  client.newCall(request).execute().close()
              }
          }
      } catch (e: Exception) {
          Log.e("MainActivity", "Ошибка отправки видео в канал: ${e.message}")
      }

      // Отправка выбранным контактам (если есть)
      try {
          if (selectedContacts.isNotEmpty() && telegramUserId != null) {
              for (contact in selectedContacts) {
                  sendVideoToContact(contact, videoFile)
              }
              // Небольшая задержка чтобы TDLib обработал файл
              kotlinx.coroutines.delay(500)
          }
      } catch (e: Exception) {
          Log.e("MainActivity", "Ошибка отправки видео контактам: ${e.message}")
      }

      // Удаляем файл после отправки
      try {
          if (videoFile.exists()) videoFile.delete()
      } catch (_: Exception) {}
  }

    private fun sendVideo(videoFile: File) {
        ioScope.launch {
            try {
                // Отправляем видео в канал только в режиме VIDEO_SEGMENTS
                if (currentWorkMode == WorkMode.VIDEO_SEGMENTS) {
                    val videoRequestBody = videoFile.asRequestBody("video/mp4".toMediaTypeOrNull())
                    val multipartBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("chat_id", TELEGRAM_CHAT_ID)
                        .addFormDataPart("video", videoFile.name, videoRequestBody)
                        .build()
                    val url = "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendVideo"
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
                    response.close()
                }
                
                // Всегда отправляем видео контактам, если они выбраны
                if (selectedContacts.isNotEmpty() && telegramUserId != null) {
                    selectedContacts.forEach { contact ->
                        sendVideoToContact(contact, videoFile)
                    }
                    
                    // Ждем немного чтобы TDLib успел обработать файл
                    delay(2000)
                }
                
                // Удаляем файл после отправки
                if (videoFile.exists()) {
                    videoFile.delete()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Network error while sending video: ${e.message}")
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

    override fun onDestroy() {
        Log.d("MainActivity", "onDestroy вызван")
        super.onDestroy()
        
        // Отменяем все корутины
        try {
            ioScope.cancel()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка отмены корутин: ${e.message}")
        }
        try {
            autoStopJob?.cancel()
            autoStopJob = null
        } catch (_: Exception) {}
        
        // Останавливаем все процессы
        try {
            stop()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка остановки: ${e.message}")
        }
        
        // Освобождаем ресурсы TDLib
        if (::telegramAuthHelper.isInitialized) {
            try {
                Log.d("MainActivity", "Освобождаем ресурсы TelegramAuthHelper в onDestroy")
                telegramAuthHelper.destroy()
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка освобождения TelegramAuthHelper в onDestroy: ${e.message}")
            }
        }
        
        // Принудительно освобождаем камеры
        try {
            streamingCamera?.stopStream()
            streamingCamera?.stopRecord()
            recordingCamera?.stopRecord()
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
                
                // 1. Имя и Фамилия из настроек (на первом месте)
                val fullName = buildString {
                    if (userName.isNotEmpty()) append(userName)
                    if (userLastName.isNotEmpty()) {
                        if (userName.isNotEmpty()) append(" ")
                        append(userLastName)
                    }
                }
                if (fullName.isNotEmpty()) {
                    userInfoMessage += "👤 $fullName\n"
                }
                
                // 2. Ссылка на профиль в Telegram как @username
                telegramUsername?.let { username ->
                    userInfoMessage += "📱 @$username\n"
                }
                
                // 3. Телефон из Telegram
                telegramPhone?.let { phone ->
                    val formattedPhone = if (phone.startsWith("+")) phone else "+$phone"
                    userInfoMessage += "📞 $formattedPhone\n"
                }
                
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
                
                // Отправляем информационное сообщение
                val messageBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", TELEGRAM_CHAT_ID)
                    .addFormDataPart("text", userInfoMessage)
                    .addFormDataPart("parse_mode", "HTML")
                    .build()
                
                val messageUrl = "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/sendMessage"
                val messageRequest = Request.Builder()
                    .url(messageUrl)
                    .post(messageBody)
                    .build()
                
                OkHttpClient().newCall(messageRequest).execute().close()
                
                // Если выбраны контакты, отправляем сообщение каждому из них
                if (selectedContacts.isNotEmpty() && telegramUserId != null) {
                    selectedContacts.forEach { contact ->
                        sendMessageToContact(contact, userInfoMessage)
                    }
                }
                
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка при отправке информационного сообщения: ${e.message}")
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
                val location = getFreshLocation()
                if (liveLocationMessageId == null) {
                    val url = "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}" +
                            "/sendLocation?chat_id=${TELEGRAM_CHAT_ID}" +
                            "&latitude=${location?.latitude}" +
                            "&longitude=${location?.longitude}" +
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
                    val url = "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}" +
                            "/editMessageLiveLocation" +
                            "?chat_id=${TELEGRAM_CHAT_ID}" +
                            "&message_id=${liveLocationMessageId}" +
                            "&latitude=${location?.latitude}" +
                            "&longitude=${location?.longitude}"
                    OkHttpClient().newCall(Request.Builder().url(url).get().build()).execute().close()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Live location update failed: ${e.message}")
            }
        }
    }

    private fun stopLiveLocation() {
        ioScope.launch {
            try {
                // Останавливаем live location, отправив финальное обновление координат
                if (liveLocationMessageId != null) {
                    val location = getFreshLocation()
                    val url = "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}" +
                            "/stopMessageLiveLocation" +
                            "?chat_id=${TELEGRAM_CHAT_ID}" +
                            "&message_id=${liveLocationMessageId}" +
                            "&latitude=${location?.latitude}" +
                            "&longitude=${location?.longitude}"
                    OkHttpClient().newCall(Request.Builder().url(url).get().build()).execute().close()
                    Log.d("MainActivity", "Live location остановлен для сообщения ID: $liveLocationMessageId")
                }
                liveLocationMessageId = null
            } catch (e: Exception) {
                Log.e("MainActivity", "Ошибка при остановке live location: ${e.message}")
                // В случае ошибки просто сбрасываем ID
                liveLocationMessageId = null
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
        userLastName = sharedPrefs.getString("last_name", "") ?: ""
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
        
        // Центрирование заголовка
        dialog.setOnShowListener {
            val titleView = dialog.findViewById<TextView>(android.R.id.title)
            titleView?.gravity = android.view.Gravity.CENTER
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
        
        // Центрирование заголовка
        dialog.setOnShowListener {
            val titleView = dialog.findViewById<TextView>(android.R.id.title)
            titleView?.gravity = android.view.Gravity.CENTER
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
        
        when {
            quickTileAction == "stop_and_close" -> {
                Log.d("MainActivity", "Получен сигнал закрытия через плитку")
                isClosingFromTile = true

                // Останавливаем приложение (блокирующе дождемся отправки последнего сегмента)
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
                    } catch (_: Exception) {}
                    // Освобождаем ресурсы Telegram
                    if (::telegramAuthHelper.isInitialized) {
                        try { telegramAuthHelper.destroy() } catch (_: Exception) {}
                    }
                    try { ioScope.cancel() } catch (_: Exception) {}
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


}