# Интеграция официальной TDLib в SOSTaxi

## Обзор

Проект SOSTaxi теперь использует официальную библиотеку TDLib версии 1.8.47, скачанную с официального репозитория Telegram и собранную для Android.

## Источник библиотек

- **Официальный сайт**: https://core.telegram.org/tdlib
- **GitHub репозиторий**: https://github.com/tdlib/td
- **Готовые библиотеки**: https://github.com/up9cloud/android-libtdjson (версия 1.8.47)

## Структура интеграции

### 1. Нативные библиотеки
Расположение: `app/src/main/jniLibs/`
```
jniLibs/
├── arm64-v8a/
│   └── libtdjson.so (28MB)
├── armeabi-v7a/
│   └── libtdjson.so (18MB)
├── x86/
│   └── libtdjson.so
└── x86_64/
    └── libtdjson.so
```

### 2. Java интерфейс
Файл: `app/src/main/java/org/drinkless/tdlib/JsonClient.java`

Содержит нативные методы для работы с TDLib:
- `td_json_client_create()` - создание клиента
- `td_json_client_send()` - отправка запросов
- `td_json_client_receive()` - получение ответов
- `td_json_client_destroy()` - уничтожение клиента

### 3. Kotlin обёртка
Файл: `app/src/main/java/com/example/sostaxi/TelegramAuthHelper.kt`

Полностью переписанный класс для работы с официальной TDLib через JSON API:
- Асинхронная обработка запросов и ответов
- Управление состояниями авторизации
- Работа с контактами и пользовательскими данными
- Использование продакшн серверов Telegram

## Ключевые особенности

### 1. Продакшн серверы
```kotlin
put("use_test_dc", false) // Используем продакшн серверы
```

### 2. JSON API
Все взаимодействие с TDLib происходит через JSON:
```kotlin
val request = JSONObject().apply {
    put("@type", "setAuthenticationPhoneNumber")
    put("phone_number", phoneNumber)
}
```

### 3. Асинхронная архитектура
- Отдельный поток для получения обновлений
- Система callback'ов для обработки ответов
- Thread-safe операции

## Конфигурация проекта

### build.gradle
```gradle
// Конфигурация для нативных библиотек TDLib
sourceSets {
    main {
        jniLibs.srcDirs = ['src/main/jniLibs']
    }
}
```

### API ключи
В `TelegramAuthHelper.kt`:
```kotlin
private const val API_ID = 27274131
private const val API_HASH = "ade6f43cffd569b9ff1f0b7e21bad4df"
```

## Использование

### Инициализация
```kotlin
val telegramAuth = TelegramAuthHelper(context)
telegramAuth.init(object : TelegramAuthHelper.AuthCallback {
    override fun onAuthStateChanged(state: AuthState) {
        // Обработка изменения состояния
    }
    
    override fun onUserDataReceived(userData: TelegramAuthData) {
        // Получение данных пользователя
    }
    
    override fun onContactsReceived(contacts: List<TelegramContact>) {
        // Получение списка контактов
    }
    
    override fun onError(error: String) {
        // Обработка ошибок
    }
})
```

### Авторизация
```kotlin
// 1. Отправка номера телефона
telegramAuth.setPhoneNumber("+1234567890")

// 2. Проверка кода
telegramAuth.checkAuthenticationCode("12345")

// 3. Проверка пароля (если включена 2FA)
telegramAuth.checkAuthenticationPassword("password")
```

## Преимущества новой интеграции

1. **Официальная поддержка** - используется оригинальная библиотека от Telegram
2. **Продакшн серверы** - подключение к реальным серверам Telegram
3. **Актуальная версия** - TDLib 1.8.47 с последними возможностями
4. **Стабильность** - проверенная и оптимизированная библиотека
5. **Полная функциональность** - доступ ко всем возможностям Telegram API

## Размер библиотек

- **arm64-v8a**: ~28MB (основная архитектура для современных устройств)
- **armeabi-v7a**: ~18MB (совместимость со старыми устройствами)
- **x86/x86_64**: для эмуляторов

## Следующие шаги

1. Тестирование авторизации с реальными номерами телефонов
2. Проверка отправки видео через Telegram
3. Оптимизация размера APK (при необходимости)
4. Добавление дополнительных функций Telegram API

## Техническая поддержка

При возникновении проблем:
1. Проверьте логи с тегом "TelegramAuthHelper"
2. Убедитесь, что API_ID и API_HASH корректны
3. Проверьте наличие всех нативных библиотек
4. Обратитесь к официальной документации TDLib: https://core.telegram.org/tdlib/docs/ 