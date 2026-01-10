package com.example.sostaxi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class TelegramAuthHelper private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "TelegramAuthHelper"
        private const val PREFS_NAME = "telegram_auth_prefs"
        
        // ВАЖНО: Замените эти значения на ваши собственные из https://my.telegram.org/apps
        private const val API_ID = 27274131  // ЗАМЕНИТЕ НА ВАШ API ID
        private const val API_HASH = "ade6f43cffd569b9ff1f0b7e21bad4df"  // ЗАМЕНИТЕ НА ВАШ API HASH
        
        // Тестовые API ключи для диагностики (работают только с тестовыми серверами)
        private const val TEST_API_ID = 94575  // Официальный тестовый API ID
        private const val TEST_API_HASH = "a3406de8d171bb422bb6ddf3480800fd"  // Соответствующий hash
        
        @Volatile
        private var INSTANCE: TelegramAuthHelper? = null
        
        fun getInstance(context: Context): TelegramAuthHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TelegramAuthHelper(context.applicationContext).also { 
                    INSTANCE = it
                    Log.d(TAG, "Создан новый singleton экземпляр TelegramAuthHelper")
                }
            }
        }
    }
    
    private var client: Client? = null
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var authCallback: AuthCallback? = null
    private var isInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Состояния авторизации
    enum class AuthState {
        NOT_AUTHENTICATED,
        WAIT_PHONE_NUMBER,
        WAIT_CODE,
        WAIT_PASSWORD,
        AUTHENTICATED,
        ERROR
    }
    
    // Интерфейс для обратных вызовов
    interface AuthCallback {
        fun onAuthStateChanged(state: AuthState)
        fun onContactsReceived(contacts: List<TelegramContact>)
        fun onUserDataReceived(userData: TelegramAuthData)
        fun onError(error: String)
    }
    
    // Данные пользователя Telegram
    data class TelegramAuthData(
        val id: Long,
        val first_name: String,
        val last_name: String?,
        val username: String?,
        val phone_number: String?
    )
    
    // Контакт Telegram
    data class TelegramContact(
        val id: Long,
        val name: String,
        val phone: String,
        val username: String?
    )
    
    private var currentAuthState = AuthState.NOT_AUTHENTICATED
    private var currentUser: TelegramAuthData? = null
    private val contactsList = mutableListOf<TelegramContact>()
    private var useTestDc = false // Всегда используем продакшн серверы
    
    fun init(callback: AuthCallback) {
        if (isInitialized) {
            Log.w(TAG, "TelegramAuthHelper уже инициализирован")
            this.authCallback = callback
            if (currentAuthState == AuthState.AUTHENTICATED) {
                callback.onAuthStateChanged(currentAuthState)
                currentUser?.let { callback.onUserDataReceived(it) }
                if (contactsList.isNotEmpty()) {
                    callback.onContactsReceived(contactsList.toList())
                }
            }
            return
        }
        
        this.authCallback = callback
        
        try {
            // Закрываем старый клиент, если он существует
            if (client != null) {
                Log.w(TAG, "Обнаружен старый клиент, закрываем его")
                try {
                    client?.send(TdApi.Close()) { }
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка закрытия старого клиента: ${e.message}")
                }
                client = null
                Thread.sleep(500) // Даем время на закрытие
            }
            
            // Очищаем файлы блокировки, если они существуют
            cleanupLockFiles()
            
            // Создаем TDLib клиент с новым API
            client = Client.create(
                { update -> handleUpdate(update) },  // updateHandler
                { exception -> Log.e(TAG, "Update exception: ${exception.message}", exception) },  // updateExceptionHandler
                { exception -> Log.e(TAG, "Default exception: ${exception.message}", exception) }   // defaultExceptionHandler
            )
            
            isInitialized = true
            Log.d(TAG, "TDLib клиент создан успешно")
            
            // Устанавливаем уровень логирования
            Client.execute(TdApi.SetLogVerbosityLevel(1))
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка инициализации TDLib: ${e.message}")
            authCallback?.onError("Ошибка инициализации: ${e.message}")
        }
    }
    
    private fun cleanupLockFiles() {
        try {
            val databaseDir = context.cacheDir.absolutePath + "/tdlib"
            val databaseFile = java.io.File(databaseDir)
            if (databaseFile.exists()) {
                // Удаляем файлы блокировки
                val lockFiles = databaseFile.listFiles { file ->
                    file.name.endsWith(".binlog.lock") || 
                    file.name.endsWith(".lock") ||
                    file.name == "td.binlog.lock"
                }
                lockFiles?.forEach { lockFile ->
                    try {
                        if (lockFile.delete()) {
                            Log.d(TAG, "Удален файл блокировки: ${lockFile.name}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось удалить файл блокировки ${lockFile.name}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка очистки файлов блокировки: ${e.message}")
        }
    }
    
    private fun handleUpdate(update: TdApi.Object) {
        mainHandler.post {
            try {
                Log.d(TAG, "Получено обновление: ${update.javaClass.simpleName}")
                
                when (update) {
                    is TdApi.UpdateAuthorizationState -> {
                        handleAuthorizationState(update.authorizationState)
                    }
                    is TdApi.UpdateUser -> {
                        if (currentUser?.id == update.user.id) {
                            updateCurrentUser(update.user)
                        }
                    }
                    is TdApi.UpdateConnectionState -> {
                        Log.d(TAG, "Состояние соединения: ${update.state.javaClass.simpleName}")
                        
                        when (update.state) {
                            is TdApi.ConnectionStateConnecting -> {
                                Log.d(TAG, "Подключение к серверам Telegram...")
                            }
                            is TdApi.ConnectionStateConnectingToProxy -> {
                                Log.d(TAG, "Подключение через прокси...")
                            }
                            is TdApi.ConnectionStateReady -> {
                                Log.d(TAG, "Соединение с Telegram установлено")
                            }
                            is TdApi.ConnectionStateUpdating -> {
                                Log.d(TAG, "Обновление данных...")
                            }
                            is TdApi.ConnectionStateWaitingForNetwork -> {
                                Log.w(TAG, "Ожидание сетевого соединения")
                                authCallback?.onError("Проверьте подключение к интернету")
                            }
                        }
                    }
                    is TdApi.Error -> {
                        Log.e(TAG, "Ошибка TDLib: код ${update.code}, сообщение: ${update.message}")
                        authCallback?.onError("Ошибка TDLib: ${update.message}")
                    }
                    else -> {
                        Log.v(TAG, "Необработанное обновление: ${update.javaClass.simpleName}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка обработки обновления: ${e.message}", e)
            }
        }
    }
    
    private fun handleAuthorizationState(authState: TdApi.AuthorizationState) {
        val stateType = authState.javaClass.simpleName
        Log.d(TAG, "Состояние авторизации: $stateType")
        
        when (authState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                Log.d(TAG, "Ожидание параметров TDLib")
                setTdlibParameters()
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                Log.d(TAG, "Ожидание номера телефона")
                currentAuthState = AuthState.WAIT_PHONE_NUMBER
                authCallback?.onAuthStateChanged(currentAuthState)
            }
            is TdApi.AuthorizationStateWaitCode -> {
                Log.d(TAG, "Ожидание кода подтверждения")
                currentAuthState = AuthState.WAIT_CODE
                authCallback?.onAuthStateChanged(currentAuthState)
            }
            is TdApi.AuthorizationStateWaitPassword -> {
                Log.d(TAG, "Ожидание пароля двухфакторной аутентификации")
                currentAuthState = AuthState.WAIT_PASSWORD
                authCallback?.onAuthStateChanged(currentAuthState)
            }
            is TdApi.AuthorizationStateReady -> {
                Log.d(TAG, "Авторизация завершена успешно")
                currentAuthState = AuthState.AUTHENTICATED
                authCallback?.onAuthStateChanged(currentAuthState)
                getCurrentUser()
            }
            is TdApi.AuthorizationStateClosed -> {
                Log.d(TAG, "Авторизация закрыта")
                currentAuthState = AuthState.NOT_AUTHENTICATED
                authCallback?.onAuthStateChanged(currentAuthState)
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                Log.d(TAG, "Ожидание подтверждения с другого устройства")
            }
            is TdApi.AuthorizationStateWaitRegistration -> {
                Log.d(TAG, "Ожидание регистрации нового пользователя")
            }
            else -> {
                Log.w(TAG, "Неизвестное состояние авторизации: $stateType")
            }
        }
    }
    
    private fun setTdlibParameters() {
        Log.d(TAG, "=== НАЧАЛО setTdlibParameters ===")
        
        // Используем кэш-директорию
        val databaseDir = context.cacheDir.absolutePath + "/tdlib"
        val databaseFile = java.io.File(databaseDir)
        if (!databaseFile.exists()) {
            val created = databaseFile.mkdirs()
            Log.d(TAG, "Создание директории базы данных: $created")
        }
        
        Log.d(TAG, "Database directory: $databaseDir")
        
        // Выбираем API ключи в зависимости от типа серверов
        val apiId = if (useTestDc) TEST_API_ID else API_ID
        val apiHash = if (useTestDc) TEST_API_HASH else API_HASH
        
        Log.d(TAG, "API ID: $apiId")
        Log.d(TAG, "Use test DC: $useTestDc")
        
        // Создаем параметры TDLib с правильным конструктором
        val parameters = TdApi.SetTdlibParameters(
            useTestDc,                    // useTestDc
            databaseDir,                  // databaseDirectory
            "",                           // filesDirectory (пустая строка = использовать databaseDirectory)
            byteArrayOf(),               // databaseEncryptionKey (пустой массив = без шифрования)
            true,                        // useFileDatabase
            true,                        // useChatInfoDatabase
            true,                        // useMessageDatabase
            false,                       // useSecretChats
            apiId,                       // apiId
            apiHash,                     // apiHash
            "en",                        // systemLanguageCode
            "Android",                   // deviceModel
            "11",                        // systemVersion
            "1.0"                        // applicationVersion
        )
        
        Log.d(TAG, "Отправляем параметры TDLib...")
        
        client?.send(parameters) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "Параметры TDLib установлены успешно")
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Ошибка установки параметров: ${result.message}")
                    mainHandler.post {
                        authCallback?.onError("Ошибка настройки: ${result.message}")
                    }
                }
                else -> {
                    Log.w(TAG, "Неожиданный ответ на setTdlibParameters: ${result.javaClass.simpleName}")
                }
            }
        }
        
        Log.d(TAG, "=== КОНЕЦ setTdlibParameters ===")
    }
    
    private fun getCurrentUser() {
        client?.send(TdApi.GetMe()) { result ->
            when (result) {
                is TdApi.User -> {
                    updateCurrentUser(result)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Ошибка получения пользователя: ${result.message}")
                }
            }
        }
    }
    
    private fun updateCurrentUser(user: TdApi.User) {
        try {
            val userData = TelegramAuthData(
                id = user.id,
                first_name = user.firstName,
                last_name = if (user.lastName.isNotEmpty()) user.lastName else null,
                username = user.usernames?.let { usernames ->
                    if (usernames.activeUsernames.isNotEmpty()) usernames.activeUsernames[0] else null
                },
                phone_number = if (user.phoneNumber.isNotEmpty()) user.phoneNumber else null
            )
            
            currentUser = userData
            
            // Переключаемся на главный поток для UI операций
            Handler(Looper.getMainLooper()).post {
                authCallback?.onUserDataReceived(userData)
            }
            
            // Сохраняем данные пользователя
            saveUserData(userData)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обновления данных пользователя: ${e.message}")
        }
    }
    
    // Публичные методы для авторизации
    fun setPhoneNumber(phoneNumber: String) {
        Log.d(TAG, "Отправка номера телефона: $phoneNumber")
        
        if (currentAuthState != AuthState.WAIT_PHONE_NUMBER) {
            Log.w(TAG, "Попытка отправить номер в неправильном состоянии: $currentAuthState")
        }
        
        val settings = TdApi.PhoneNumberAuthenticationSettings().apply {
            allowFlashCall = false
            isCurrentPhoneNumber = false
            allowSmsRetrieverApi = false
        }
        
        client?.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings)) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "Номер телефона успешно отправлен")
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Ошибка отправки номера: ${result.message}")
                    mainHandler.post {
                        authCallback?.onError("Ошибка отправки номера: ${result.message}")
                    }
                }
            }
        }
    }
    
    fun checkAuthenticationCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code)) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "Код подтверждения принят")
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Неверный код: ${result.message}")
                    mainHandler.post {
                        authCallback?.onError("Неверный код: ${result.message}")
                    }
                }
            }
        }
    }
    
    fun checkAuthenticationPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) { result ->
            when (result) {
                is TdApi.Ok -> {
                    Log.d(TAG, "Пароль принят")
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Неверный пароль: ${result.message}")
                    mainHandler.post {
                        authCallback?.onError("Неверный пароль: ${result.message}")
                    }
                }
            }
        }
    }
    
    fun getContacts() {
        Log.d(TAG, "getContacts: запрос контактов, client = $client, isAuthenticated = ${isAuthenticated()}")
        
        if (client == null) {
            Log.e(TAG, "getContacts: client is null")
            mainHandler.post {
                authCallback?.onError("Telegram клиент не инициализирован")
            }
            return
        }
        
        client?.send(TdApi.GetContacts()) { result ->
            Log.d(TAG, "getContacts: получен ответ: ${result.javaClass.simpleName}")
            when (result) {
                is TdApi.Users -> {
                    Log.d(TAG, "getContacts: получено ${result.userIds.size} ID пользователей")
                    parseContacts(result)
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Ошибка получения контактов: ${result.message}")
                    mainHandler.post {
                        authCallback?.onError("Ошибка получения контактов: ${result.message}")
                    }
                }
            }
        }
    }
    
    private fun parseContacts(users: TdApi.Users) {
        try {
            Log.d(TAG, "parseContacts: начинаем парсинг ${users.userIds.size} контактов")
            contactsList.clear()
            
            // Добавляем "Избранное" (чат с самим собой) в начало списка
            currentUser?.let { user ->
                val savedMessagesContact = TelegramContact(
                    id = user.id,
                    name = "⭐ Избранное",  // Специальное имя для чата с самим собой
                    phone = user.phone_number ?: "",
                    username = user.username
                )
                contactsList.add(savedMessagesContact)
                Log.d(TAG, "parseContacts: добавлен контакт 'Избранное' (самому себе)")
            }
            
            if (users.userIds.isEmpty()) {
                Log.w(TAG, "parseContacts: список ID пользователей пуст")
                mainHandler.post {
                    authCallback?.onContactsReceived(contactsList.toList())
                }
                return
            }
            
            // Счетчик обработанных контактов (thread-safe)
            val processedContacts = AtomicInteger(0)
            val totalContacts = users.userIds.size
            
            for (userId in users.userIds) {
                Log.d(TAG, "parseContacts: запрашиваем информацию о пользователе $userId")
                getUserInfo(userId) { user ->
                    if (user != null) {
                        // Пропускаем самого себя, так как уже добавили "Избранное"
                        if (user.id != currentUser?.id) {
                            synchronized(contactsList) {
                                val contact = TelegramContact(
                                    id = user.id,
                                    name = "${user.firstName} ${user.lastName}".trim(),
                                    phone = user.phoneNumber,
                                    username = user.usernames?.let { usernames ->
                                        if (usernames.activeUsernames.isNotEmpty()) usernames.activeUsernames[0] else null
                                    }
                                )
                                contactsList.add(contact)
                                Log.d(TAG, "parseContacts: добавлен контакт ${contact.name} (${contact.phone})")
                            }
                        } else {
                            Log.d(TAG, "parseContacts: пропускаем самого себя (уже добавлен как Избранное)")
                        }
                        
                        // Увеличиваем счетчик обработанных контактов
                        val processed = processedContacts.incrementAndGet()
                        Log.d(TAG, "parseContacts: обработано $processed/$totalContacts, в списке: ${contactsList.size}")
                        
                        // Уведомляем о получении контактов после обработки всех
                        if (processed == totalContacts) {
                            Log.d(TAG, "parseContacts: все контакты получены, отправляем callback")
                            mainHandler.post {
                                authCallback?.onContactsReceived(contactsList.toList())
                            }
                        }
                    } else {
                        Log.w(TAG, "parseContacts: не удалось получить информацию о пользователе $userId")
                        val processed = processedContacts.incrementAndGet()
                        if (processed == totalContacts) {
                            Log.d(TAG, "parseContacts: все контакты обработаны, отправляем callback")
                            mainHandler.post {
                                authCallback?.onContactsReceived(contactsList.toList())
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга контактов: ${e.message}")
        }
    }
    
    private fun getUserInfo(userId: Long, callback: (TdApi.User?) -> Unit) {
        client?.send(TdApi.GetUser(userId)) { result ->
            when (result) {
                is TdApi.User -> callback(result)
                else -> callback(null)
            }
        }
    }
    
    // Сохранение и загрузка данных
    private fun saveUserData(userData: TelegramAuthData) {
        sharedPrefs.edit().apply {
            putLong("user_id", userData.id)
            putString("first_name", userData.first_name)
            putString("last_name", userData.last_name)
            putString("username", userData.username)
            putString("phone_number", userData.phone_number)
            apply()
        }
    }
    
    fun getCurrentUserId(): Long {
        return sharedPrefs.getLong("user_id", 0)
    }
    
    fun isAuthenticated(): Boolean {
        return currentAuthState == AuthState.AUTHENTICATED && getCurrentUserId() != 0L
    }
    
    fun logout() {
        client?.send(TdApi.LogOut()) { result ->
            // Очищаем данные
            sharedPrefs.edit().clear().apply()
            currentUser = null
            contactsList.clear()
            currentAuthState = AuthState.NOT_AUTHENTICATED
            mainHandler.post {
                authCallback?.onAuthStateChanged(currentAuthState)
            }
        }
    }
    
    fun sendMessage(chatId: Long, text: String, callback: ((Boolean, String?) -> Unit)? = null) {
        Log.d(TAG, "Отправка сообщения в чат $chatId: $text")
        
        if (!isAuthenticated()) {
            Log.e(TAG, "Попытка отправить сообщение без авторизации")
            callback?.invoke(false, "Пользователь не авторизован")
            return
        }
        
        val formattedText = TdApi.FormattedText()
        formattedText.text = text
        formattedText.entities = arrayOf()
        
        val inputMessageContent = TdApi.InputMessageText()
        inputMessageContent.text = formattedText
        
        val sendOptions = TdApi.MessageSendOptions()
        sendOptions.disableNotification = false
        sendOptions.fromBackground = false
        sendOptions.protectContent = false
        sendOptions.updateOrderOfInstalledStickerSets = false
        sendOptions.schedulingState = null
        sendOptions.effectId = 0
        sendOptions.sendingId = 0
        sendOptions.onlyPreview = false
        
        val sendMessage = TdApi.SendMessage()
        sendMessage.chatId = chatId
        sendMessage.messageThreadId = 0
        sendMessage.replyTo = null
        sendMessage.options = sendOptions
        sendMessage.replyMarkup = null
        sendMessage.inputMessageContent = inputMessageContent
        
        client?.send(sendMessage) { result ->
            when (result) {
                is TdApi.Message -> {
                    Log.d(TAG, "Сообщение успешно отправлено, ID: ${result.id}")
                    mainHandler.post {
                        callback?.invoke(true, null)
                    }
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Ошибка отправки сообщения: ${result.message}")
                    mainHandler.post {
                        callback?.invoke(false, result.message)
                    }
                }
            }
        }
    }
    
    fun sendVideo(chatId: Long, videoPath: String, callback: ((Boolean, String?) -> Unit)? = null) {
        Log.d(TAG, "Отправка видео в чат $chatId: $videoPath")
        
        if (!isAuthenticated()) {
            Log.e(TAG, "Попытка отправить видео без авторизации")
            callback?.invoke(false, "Пользователь не авторизован")
            return
        }
        
        // Проверяем существование файла
        val videoFile = java.io.File(videoPath)
        if (!videoFile.exists()) {
            Log.e(TAG, "Файл не существует: $videoPath")
            callback?.invoke(false, "Файл не найден: $videoPath")
            return
        }
        
        if (!videoFile.canRead()) {
            Log.e(TAG, "Нет доступа для чтения файла: $videoPath")
            callback?.invoke(false, "Нет доступа к файлу: $videoPath")
            return
        }
        
        Log.d(TAG, "Файл найден, размер: ${videoFile.length()} байт")
        
        val inputFile = TdApi.InputFileLocal()
        inputFile.path = videoFile.absolutePath
        
        val captionText = TdApi.FormattedText()
        captionText.text = ""
        captionText.entities = arrayOf()
        
        val inputMessageContent = TdApi.InputMessageVideo()
        inputMessageContent.video = inputFile
        inputMessageContent.thumbnail = null
        inputMessageContent.addedStickerFileIds = intArrayOf()
        inputMessageContent.duration = 0
        inputMessageContent.width = 0
        inputMessageContent.height = 0
        inputMessageContent.supportsStreaming = true
        inputMessageContent.caption = captionText
        inputMessageContent.showCaptionAboveMedia = false
        inputMessageContent.selfDestructType = null
        inputMessageContent.hasSpoiler = false
        
        val sendOptions = TdApi.MessageSendOptions()
        sendOptions.disableNotification = false
        sendOptions.fromBackground = false
        sendOptions.protectContent = false
        sendOptions.updateOrderOfInstalledStickerSets = false
        sendOptions.schedulingState = null
        sendOptions.effectId = 0
        sendOptions.sendingId = 0
        sendOptions.onlyPreview = false
        
        val sendMessage = TdApi.SendMessage()
        sendMessage.chatId = chatId
        sendMessage.messageThreadId = 0
        sendMessage.replyTo = null
        sendMessage.options = sendOptions
        sendMessage.replyMarkup = null
        sendMessage.inputMessageContent = inputMessageContent
        
        client?.send(sendMessage) { result ->
            when (result) {
                is TdApi.Message -> {
                    Log.d(TAG, "Видео успешно отправлено, ID: ${result.id}")
                    mainHandler.post {
                        callback?.invoke(true, null)
                    }
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Ошибка отправки видео: ${result.message}")
                    mainHandler.post {
                        callback?.invoke(false, result.message)
                    }
                }
            }
        }
    }
    
    fun createPrivateChat(userId: Long, callback: ((Long?) -> Unit)? = null) {
        Log.d(TAG, "Создание приватного чата с пользователем $userId")
        
        client?.send(TdApi.CreatePrivateChat(userId, false)) { result ->
            when (result) {
                is TdApi.Chat -> {
                    Log.d(TAG, "Приватный чат создан, ID: ${result.id}")
                    mainHandler.post {
                        callback?.invoke(result.id)
                    }
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Ошибка создания приватного чата: ${result.message}")
                    mainHandler.post {
                        callback?.invoke(null)
                    }
                }
            }
        }
    }

    /**
     * Проверяет, является ли текущий пользователь участником указанного чата/канала.
     * Используем это как проверку доступа (Tribute добавляет/удаляет участников).
     */
    fun isCurrentUserMemberOfChat(chatId: Long, callback: (Boolean) -> Unit) {
        try {
            if (!isAuthenticated()) {
                mainHandler.post { callback(false) }
                return
            }
            val uid = getCurrentUserId()
            if (uid == 0L) {
                mainHandler.post { callback(false) }
                return
            }

            val memberId = TdApi.MessageSenderUser(uid)
            client?.send(TdApi.GetChatMember(chatId, memberId)) { result ->
                when (result) {
                    is TdApi.ChatMember -> {
                        val ok = when (result.status) {
                            is TdApi.ChatMemberStatusCreator,
                            is TdApi.ChatMemberStatusAdministrator,
                            is TdApi.ChatMemberStatusMember -> true
                            else -> false
                        }
                        mainHandler.post { callback(ok) }
                    }
                    is TdApi.Error -> {
                        // 404/USER_NOT_PARTICIPANT и т.п. считаем отсутствием доступа
                        Log.w(TAG, "GetChatMember error: ${result.message}")
                        mainHandler.post { callback(false) }
                    }
                    else -> {
                        mainHandler.post { callback(false) }
                    }
                }
            } ?: run {
                mainHandler.post { callback(false) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "isCurrentUserMemberOfChat error: ${e.message}")
            mainHandler.post { callback(false) }
        }
    }

    /**
     * Отправляет live location (если messageId == null) или обновляет её (если messageId != null).
     * callback возвращает id созданного сообщения для последующих обновлений.
     */
    fun sendOrUpdateLiveLocation(
        chatId: Long,
        messageId: Long?,
        latitude: Double,
        longitude: Double,
        livePeriodSeconds: Int,
        callback: (Long?) -> Unit
    ) {
        try {
            if (!isAuthenticated()) {
                mainHandler.post { callback(null) }
                return
            }
            val loc = TdApi.Location(latitude, longitude, 0.0)
            if (messageId == null) {
                val content = TdApi.InputMessageLocation(loc, livePeriodSeconds, 0, 0)
                val sendOptions = TdApi.MessageSendOptions().apply {
                    disableNotification = false
                    fromBackground = false
                    protectContent = false
                    updateOrderOfInstalledStickerSets = false
                    schedulingState = null
                    effectId = 0
                    sendingId = 0
                    onlyPreview = false
                }
                val msg = TdApi.SendMessage().apply {
                    this.chatId = chatId
                    messageThreadId = 0
                    replyTo = null
                    options = sendOptions
                    replyMarkup = null
                    inputMessageContent = content
                }
                client?.send(msg) { result ->
                    when (result) {
                        is TdApi.Message -> mainHandler.post { callback(result.id) }
                        is TdApi.Error -> {
                            Log.e(TAG, "send live location error: ${result.message}")
                            mainHandler.post { callback(null) }
                        }
                        else -> mainHandler.post { callback(null) }
                    }
                } ?: mainHandler.post { callback(null) }
            } else {
                val edit = TdApi.EditMessageLiveLocation(
                    chatId,
                    messageId,
                    null,
                    loc,
                    livePeriodSeconds,
                    0,
                    0
                )
                client?.send(edit) { result ->
                    when (result) {
                        is TdApi.Message -> mainHandler.post { callback(result.id) }
                        is TdApi.Error -> {
                            Log.e(TAG, "edit live location error: ${result.message}")
                            mainHandler.post { callback(null) }
                        }
                        else -> mainHandler.post { callback(null) }
                    }
                } ?: mainHandler.post { callback(null) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendOrUpdateLiveLocation error: ${e.message}")
            mainHandler.post { callback(null) }
        }
    }

    fun stopLiveLocation(chatId: Long, messageId: Long) {
        try {
            if (!isAuthenticated()) return
            val stop = TdApi.EditMessageLiveLocation(chatId, messageId, null, null, 0, 0, 0)
            client?.send(stop) { _ -> }
        } catch (e: Exception) {
            Log.e(TAG, "stopLiveLocation error: ${e.message}")
        }
    }

    /**
     * Удаляет сообщения в чате.
     * @param chatId ID чата
     * @param messageIds массив ID сообщений для удаления
     * @param revoke true - удалить у всех, false - удалить только у себя
     * @param callback колбек с результатом (успех/неудача)
     */
    fun deleteMessages(chatId: Long, messageIds: LongArray, revoke: Boolean = true, callback: ((Boolean) -> Unit)? = null) {
        try {
            if (!isAuthenticated()) {
                Log.w(TAG, "deleteMessages: не авторизован")
                mainHandler.post { callback?.invoke(false) }
                return
            }
            
            Log.d(TAG, "Удаление ${messageIds.size} сообщений из чата $chatId")
            
            val deleteRequest = TdApi.DeleteMessages(chatId, messageIds, revoke)
            client?.send(deleteRequest) { result ->
                when (result) {
                    is TdApi.Ok -> {
                        Log.d(TAG, "Сообщения успешно удалены")
                        mainHandler.post { callback?.invoke(true) }
                    }
                    is TdApi.Error -> {
                        Log.e(TAG, "Ошибка удаления сообщений: ${result.message}")
                        mainHandler.post { callback?.invoke(false) }
                    }
                    else -> {
                        Log.w(TAG, "Неожиданный результат удаления: $result")
                        mainHandler.post { callback?.invoke(false) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessages error: ${e.message}")
            mainHandler.post { callback?.invoke(false) }
        }
    }

    /**
     * Отправляет видео в чат и возвращает ID отправленного сообщения через callback.
     */
    fun sendVideoWithMessageId(chatId: Long, videoPath: String, callback: ((Boolean, Long?, String?) -> Unit)? = null) {
        Log.d(TAG, "Отправка видео в чат $chatId: $videoPath (с возвратом messageId)")
        
        if (!isAuthenticated()) {
            Log.e(TAG, "Попытка отправить видео без авторизации")
            callback?.invoke(false, null, "Пользователь не авторизован")
            return
        }
        
        val videoFile = java.io.File(videoPath)
        if (!videoFile.exists()) {
            Log.e(TAG, "Файл не существует: $videoPath")
            callback?.invoke(false, null, "Файл не найден: $videoPath")
            return
        }
        
        if (!videoFile.canRead()) {
            Log.e(TAG, "Нет доступа для чтения файла: $videoPath")
            callback?.invoke(false, null, "Нет доступа к файлу: $videoPath")
            return
        }
        
        Log.d(TAG, "Файл найден, размер: ${videoFile.length()} байт")
        
        val inputFile = TdApi.InputFileLocal()
        inputFile.path = videoFile.absolutePath
        
        val captionText = TdApi.FormattedText()
        captionText.text = ""
        captionText.entities = arrayOf()
        
        val inputMessageContent = TdApi.InputMessageVideo()
        inputMessageContent.video = inputFile
        inputMessageContent.thumbnail = null
        inputMessageContent.addedStickerFileIds = intArrayOf()
        inputMessageContent.duration = 0
        inputMessageContent.width = 0
        inputMessageContent.height = 0
        inputMessageContent.supportsStreaming = true
        inputMessageContent.caption = captionText
        inputMessageContent.showCaptionAboveMedia = false
        inputMessageContent.selfDestructType = null
        inputMessageContent.hasSpoiler = false
        
        val sendOptions = TdApi.MessageSendOptions()
        sendOptions.disableNotification = false
        sendOptions.fromBackground = false
        sendOptions.protectContent = false
        sendOptions.updateOrderOfInstalledStickerSets = false
        sendOptions.schedulingState = null
        sendOptions.effectId = 0
        sendOptions.sendingId = 0
        sendOptions.onlyPreview = false
        
        val sendMessage = TdApi.SendMessage()
        sendMessage.chatId = chatId
        sendMessage.messageThreadId = 0
        sendMessage.replyTo = null
        sendMessage.options = sendOptions
        sendMessage.replyMarkup = null
        sendMessage.inputMessageContent = inputMessageContent
        
        client?.send(sendMessage) { result ->
            when (result) {
                is TdApi.Message -> {
                    Log.d(TAG, "Видео успешно отправлено, ID: ${result.id}")
                    mainHandler.post {
                        callback?.invoke(true, result.id, null)
                    }
                }
                is TdApi.Error -> {
                    Log.e(TAG, "Ошибка отправки видео: ${result.message}")
                    mainHandler.post {
                        callback?.invoke(false, null, result.message)
                    }
                }
            }
        }
    }

    fun destroy() {
        Log.d(TAG, "Уничтожение TelegramAuthHelper")
        try {
            client?.send(TdApi.Close()) { result ->
                Log.d(TAG, "Клиент TDLib закрыт")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при закрытии клиента: ${e.message}")
        }
        client = null
        isInitialized = false
        authCallback = null
    }
} 