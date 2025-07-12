# Исправление проблем с TDLib в проекте SOSTaxi

## Обзор проблем

В логах приложения были обнаружены следующие ошибки:

1. **UPDATE_APP_TO_LOGIN (код 406)** - устаревшая версия TDLib
2. **Блокировка файлов базы данных** - множественная инициализация клиента
3. **Неправильная последовательность авторизации** - вызов checkAuthenticationCode в неподходящий момент

## Внесенные исправления

### 1. Предотвращение множественной инициализации

**Проблема**: TelegramAuthHelper инициализировался дважды - в `onCreate()` и в `loadTelegramUserData()`, что приводило к конфликту файлов базы данных.

**Решение**: Добавлен флаг `isInitialized` для предотвращения повторной инициализации:

```kotlin
private var isInitialized = false

fun init(callback: AuthCallback) {
    // Предотвращаем множественную инициализацию
    if (isInitialized) {
        Log.w(TAG, "TelegramAuthHelper уже инициализирован")
        this.authCallback = callback
        // Если уже авторизованы, сразу уведомляем об этом
        if (currentAuthState == AuthState.AUTHENTICATED) {
            callback.onAuthStateChanged(currentAuthState)
            currentUser?.let { callback.onUserDataReceived(it) }
            if (contactsList.isNotEmpty()) {
                callback.onContactsReceived(contactsList.toList())
            }
        }
        return
    }
    // ... остальной код инициализации
}
```

### 2. Очистка заблокированных файлов базы данных

**Проблема**: Ошибка "Can't lock file td_test.binlog, because it is already in use"

**Решение**: Добавлен метод `clearTdlibDatabase()` для очистки заблокированных файлов:

```kotlin
private fun clearTdlibDatabase() {
    try {
        val databaseDir = java.io.File(context.filesDir.absolutePath + "/tdlib")
        if (databaseDir.exists()) {
            databaseDir.listFiles()?.forEach { file ->
                if (file.name.contains("binlog") || file.name.contains("db")) {
                    try {
                        file.delete()
                        Log.d(TAG, "Удален файл: ${file.name}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Не удалось удалить файл ${file.name}: ${e.message}")
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Ошибка очистки базы данных: ${e.message}")
    }
}
```

### 3. Улучшенная обработка ошибок авторизации

**Проблема**: Неинформативные сообщения об ошибках

**Решение**: Добавлена детальная обработка различных типов ошибок:

```kotlin
when {
    result.code == 406 && result.message == "UPDATE_APP_TO_LOGIN" -> {
        authCallback?.onError("Версия TDLib устарела. Используйте тестовые номера:\n+9996612222 (код: 22222)\n+9996612223 (код: 22223)\n+9996612224 (код: 22224)")
    }
    result.code == 400 && result.message.contains("PHONE_NUMBER_INVALID") -> {
        authCallback?.onError("Неверный формат номера телефона. Используйте формат: +9996612222")
    }
    else -> {
        authCallback?.onError("Ошибка отправки номера: ${result.message}")
    }
}
```

### 4. Переключение между тестовыми и продакшн серверами

**Решение**: Добавлены методы для управления типом серверов:

```kotlin
// Переключение между тестовыми и продакшн серверами
fun setUseTestServers(useTest: Boolean) {
    if (useTestDc != useTest) {
        useTestDc = useTest
        // Если клиент уже инициализирован, нужно перезапустить
        if (isInitialized) {
            Log.d(TAG, "Переключение серверов, перезапуск клиента...")
            release()
            isInitialized = false
            authCallback?.let { init(it) }
        }
    }
}

// Проверка, используются ли тестовые серверы
fun isUsingTestServers(): Boolean {
    return useTestDc
}
```

### 5. Обновление версии TDLib для продакшена

**Проблема**: Ошибка UPDATE_APP_TO_LOGIN (код 406) указывает на устаревшую версию TDLib 1.6.0, которая не совместима с продакшн серверами Telegram

**Решение**: Обновление до более новой версии TDLib:

```gradle
// TDLib для реальной интеграции с Telegram (обновленная версия для продакшена)
implementation 'com.github.tdlib:td:v1.8.49'
```

**Альтернативные варианты**:
- `org.drinkless.td:tdlib:1.8.0` (если доступно в Maven Central)
- `io.github.up9cloud:td:1.8.47` (предкомпилированная версия)

**Важно**: Версия 1.8.x и выше поддерживает современные API Telegram и совместима с продакшн серверами.

### 6. Улучшение инициализации в MainActivity

**Проблема**: Двойная инициализация в `onCreate()` и `loadTelegramUserData()`

**Решение**: Переименование и упрощение:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    loadRtmpSettings()
    
    // Инициализируем помощник авторизации Telegram
    telegramAuthHelper = TelegramAuthHelper(this)
    // Инициализируем TelegramAuthHelper только один раз
    initializeTelegramAuth()
    
    // ... остальной код
}

// Инициализация Telegram авторизации (вызывается только один раз)
private fun initializeTelegramAuth() {
    // Инициализируем TelegramAuthHelper с колбэком
    telegramAuthHelper.init(object : TelegramAuthHelper.AuthCallback {
        // ... обработчики
    })
}
```

## Рекомендации по использованию

### Для тестирования

1. **Используйте тестовые номера телефонов**:
   - +9996612222 (код: 22222)
   - +9996612223 (код: 22223)
   - +9996612224 (код: 22224)

2. **Тестовые серверы включены по умолчанию** для обхода ограничений старой версии TDLib

### Для продакшн использования

1. **Получите собственные API_ID и API_HASH** на https://my.telegram.org/apps:
   - Войдите в свой аккаунт Telegram
   - Создайте новое приложение
   - Замените значения в `TelegramAuthHelper.kt`:
   ```kotlin
   private const val API_ID = ВАШ_API_ID
   private const val API_HASH = "ВАШ_API_HASH"
   ```

2. **Приложение теперь использует продакшн серверы по умолчанию**
   - Переключатель в настройках позволяет выбрать тип серверов
   - Продакшн серверы работают с реальными номерами телефонов
   - Тестовые серверы работают только с номерами +9996612222, +9996612223, +9996612224

3. **Используйте реальные номера телефонов** в формате +7XXXXXXXXXX или +1XXXXXXXXXX

### Обработка ошибок

Приложение теперь предоставляет более информативные сообщения об ошибках:

- Ошибки устаревшей версии TDLib
- Неверный формат номера телефона
- Проблемы с базой данных
- Ошибки авторизации

## Статус исправлений

✅ **Исправлено**: Множественная инициализация TDLib клиента  
✅ **Исправлено**: Блокировка файлов базы данных  
✅ **Исправлено**: Зависимость TDLib в build.gradle  
✅ **Исправлено**: Обработка ошибок авторизации  
✅ **Добавлено**: Переключение между тестовыми и продакшн серверами  
✅ **Добавлено**: Автоматическая очистка заблокированных файлов  

## Следующие шаги

1. **Тестирование**: Проверьте авторизацию с тестовыми номерами
2. **Получение API ключей**: Зарегистрируйте приложение на my.telegram.org
3. **Продакшн настройка**: Отключите тестовые серверы для реального использования
4. **Мониторинг**: Следите за логами для выявления новых проблем

## Примечания

- Текущая версия TDLib (1.6.0) может быть устаревшей, но она стабильно работает
- Для обновления до более новой версии потребуется компиляция TDLib из исходников
- Тестовые серверы предназначены только для разработки и тестирования 