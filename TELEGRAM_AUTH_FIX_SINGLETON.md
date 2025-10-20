# Исправление ошибки множественной инициализации TDLib

## Проблема

При запуске приложения возникали следующие ошибки:

```
Ошибка установки параметров: Can't lock file "/data/data/com.example.sostaxi/cache/tdlib/td.binlog", 
because it is already in use by current program

Ошибка отправки номера: Initialization parameters are needed: call setTdlibParameters first
```

### Причина

Проблема заключалась в том, что при каждом создании `MainActivity` создавался новый экземпляр `TelegramAuthHelper`, который пытался инициализировать новый TDLib клиент. Это приводило к тому, что несколько экземпляров TDLib пытались одновременно получить доступ к одному и тому же файлу базы данных (`td.binlog`), что вызывало конфликт блокировки.

## Решение

### 1. Преобразование TelegramAuthHelper в Singleton

**Файл:** `app/src/main/java/com/example/sostaxi/TelegramAuthHelper.kt`

Изменен конструктор класса с `public` на `private` и добавлен статический метод `getInstance()`:

```kotlin
class TelegramAuthHelper private constructor(private val context: Context) {
    
    companion object {
        // ... другие константы ...
        
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
}
```

**Преимущества:**
- Гарантируется, что существует только один экземпляр `TelegramAuthHelper` на все приложение
- Thread-safe реализация с использованием `@Volatile` и `synchronized`
- Используется `applicationContext` для предотвращения утечек памяти

### 2. Добавлена очистка файлов блокировки

Добавлен метод `cleanupLockFiles()`, который удаляет старые файлы блокировки перед созданием нового клиента:

```kotlin
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
```

### 3. Улучшена инициализация

Добавлена проверка и закрытие старого клиента перед созданием нового:

```kotlin
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
        client = Client.create(...)
        
        isInitialized = true
        Log.d(TAG, "TDLib клиент создан успешно")
        
        // Устанавливаем уровень логирования
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        
    } catch (e: Exception) {
        Log.e(TAG, "Ошибка инициализации TDLib: ${e.message}")
        authCallback?.onError("Ошибка инициализации: ${e.message}")
    }
}
```

### 4. Обновлена MainActivity

**Файл:** `app/src/main/java/com/example/sostaxi/MainActivity.kt`

Изменено создание `TelegramAuthHelper` с использованием `lazy` делегата:

```kotlin
// Было:
private lateinit var telegramAuthHelper: TelegramAuthHelper

override fun onCreate(savedInstanceState: Bundle?) {
    // ...
    if (!::telegramAuthHelper.isInitialized && !isClosingFromTile) {
        telegramAuthHelper = TelegramAuthHelper(this)
        initializeTelegramAuth()
    }
}

// Стало:
private val telegramAuthHelper: TelegramAuthHelper by lazy {
    TelegramAuthHelper.getInstance(applicationContext)
}

override fun onCreate(savedInstanceState: Bundle?) {
    // ...
    if (!isClosingFromTile) {
        initializeTelegramAuth()
    }
}
```

Удалены проверки инициализации в `onDestroy()`, так как singleton не должен уничтожаться при уничтожении Activity.

## Результаты

После внесения изменений:

1. ✅ Создается только один экземпляр TDLib клиента на все приложение
2. ✅ Нет конфликтов блокировки файлов базы данных
3. ✅ Параметры TDLib устанавливаются успешно
4. ✅ Отправка номера телефона работает корректно
5. ✅ Авторизация и работа с контактами функционируют нормально

## Логи после исправления

```
10-20 14:12:49.367 24109 24109 D TelegramAuthHelper: Создан новый singleton экземпляр TelegramAuthHelper
10-20 14:12:49.400 24109 24109 D TelegramAuthHelper: TDLib клиент создан успешно
10-20 14:12:49.591 24109 24109 D TelegramAuthHelper: Состояние авторизации: AuthorizationStateWaitTdlibParameters
10-20 14:12:49.593 24109 24109 D TelegramAuthHelper: Отправляем параметры TDLib...
10-20 14:12:49.666 24109 24138 D TelegramAuthHelper: Параметры TDLib установлены успешно
10-20 14:12:49.802 24109 24109 D TelegramAuthHelper: Соединение с Telegram установлено
10-20 14:12:49.698 24109 24138 D TelegramAuthHelper: parseContacts: все контакты получены
```

## Дата исправления

20 октября 2025 года

## Автор

AI Assistant (Cursor)

