# Настройка TDLib для интеграции с Telegram

## Получение API ID и API Hash

Для работы с TDLib необходимо получить API ID и API Hash от Telegram:

### Шаг 1: Регистрация приложения
1. Перейдите на https://my.telegram.org/apps
2. Войдите в свой аккаунт Telegram
3. Нажмите "Create new application"

### Шаг 2: Заполнение формы
Заполните форму регистрации приложения:
- **App title**: SOSTaxi (или любое другое название)
- **Short name**: sostaxi (короткое имя, только латинские буквы)
- **URL**: Можно оставить пустым или указать любой URL
- **Platform**: Android
- **Description**: Emergency taxi application (описание приложения)

### Шаг 3: Получение данных
После создания приложения вы получите:
- **API ID** (числовое значение)
- **API Hash** (строка из букв и цифр)

### Шаг 4: Обновление кода
Откройте файл `app/src/main/java/com/example/sostaxi/TelegramAuthHelper.kt` и замените:

```kotlin
private const val API_ID = 94575  // Замените на ваш API ID
private const val API_HASH = "a3406de8d171bb422bb6ddf3bbd800e2"  // Замените на ваш API Hash
```

На ваши реальные значения:

```kotlin
private const val API_ID = YOUR_API_ID  // Ваш API ID
private const val API_HASH = "YOUR_API_HASH"  // Ваш API Hash
```

## Важные замечания

1. **Безопасность**: API ID и API Hash являются конфиденциальными данными. Не публикуйте их в открытом коде.

2. **Ограничения**: У каждого API ID есть лимиты на количество запросов. Для обычного использования этих лимитов достаточно.

3. **Тестирование**: Для тестирования можно использовать тестовые серверы Telegram, но для продакшена нужны реальные API данные.

## Возможные проблемы

### Ошибка "Invalid API ID or Hash"
- Проверьте правильность введенных API ID и API Hash
- Убедитесь, что приложение зарегистрировано для платформы Android

### Ошибка "Database encryption key is needed"
- Эта ошибка автоматически решается в нашей реализации
- Мы добавили обработку состояния `AuthorizationStateWaitEncryptionKey`
- Если ошибка все еще возникает, проверьте права доступа к папке приложения

### Ошибка "Too many requests"
- Подождите некоторое время перед повторной попыткой
- Проверьте, не превышены ли лимиты API

### Проблемы с авторизацией
- Убедитесь, что номер телефона введен в международном формате (+7...)
- Проверьте, что у вас есть доступ к Telegram на этом номере
- Убедитесь, что код подтверждения введен правильно

## Дополнительная информация

- [Документация TDLib](https://core.telegram.org/tdlib)
- [Telegram API документация](https://core.telegram.org/api)
- [Примеры использования TDLib](https://github.com/tdlib/td/tree/master/example) 