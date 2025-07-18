# Настройка реального Telegram OAuth для SOSTaxi

## 🚀 Обзор

Теперь приложение SOSTaxi поддерживает **реальную авторизацию через Telegram OAuth** с использованием официального Telegram Login Widget. Пользователи могут войти через свой настоящий аккаунт Telegram и получить доступ к своим данным.

## ✅ Что реализовано

### 🔐 Реальная OAuth авторизация
- Использование официального Telegram Login Widget
- Проверка подлинности данных через HMAC-SHA256
- Получение реальных данных пользователя из Telegram
- Безопасное сохранение сессии

### 📱 Полученные данные
- **ID пользователя** - уникальный идентификатор в Telegram
- **Имя и фамилия** - реальные данные из профиля Telegram
- **Username** - @username пользователя (если установлен)
- **Фото профиля** - URL аватара пользователя (если доступно)
- **Дата авторизации** - время входа в приложение

### 📞 Контакты
- Доступ к контактам телефонной книги Android
- Интеграция с выбранными контактами для уведомлений
- В будущем: доступ к контактам Telegram через TDLib

## 🛠 Настройка Telegram бота

### Шаг 1: Создание бота

1. Откройте Telegram и найдите [@BotFather](https://t.me/botfather)
2. Отправьте команду `/newbot`
3. Введите имя бота (например: "SOS Taxi Bot")
4. Введите username бота (например: "SOSTaxiBot")
5. **Сохраните полученный токен** - он понадобится в коде

### Шаг 2: Настройка домена для OAuth

**Важно!** Для работы Telegram Login Widget необходим настроенный домен.

#### Вариант 1: Использование ngrok (для разработки)

1. Установите [ngrok](https://ngrok.com/):
   ```bash
   # macOS
   brew install ngrok
   
   # Windows - скачайте с сайта ngrok.com
   ```

2. Запустите ngrok для локального сервера:
   ```bash
   ngrok http 8080
   ```

3. Скопируйте HTTPS URL (например: `https://abc123.ngrok.io`)

#### Вариант 2: Использование собственного домена

1. Настройте ваш домен (например: `https://sostaxi.app`)
2. Убедитесь, что домен доступен по HTTPS

### Шаг 3: Настройка домена в @BotFather

1. В чате с @BotFather отправьте `/setdomain`
2. Выберите ваш бот
3. Введите ваш домен (например: `https://abc123.ngrok.io` или `https://sostaxi.app`)

### Шаг 4: Обновление кода приложения

В файле `TelegramAuthHelper.kt` обновите константы:

```kotlin
companion object {
    const val BOT_TOKEN = "YOUR_BOT_TOKEN_HERE" // Токен от @BotFather
    const val BOT_USERNAME = "YourBotUsername" // Username бота без @
}
```

В файле `TelegramAuthDialogs.kt` обновите redirect URL:

```kotlin
val redirectUrl = "https://your-domain.com/auth/callback"
```

## 🔧 Как работает OAuth авторизация

### 1. Инициация авторизации
- Пользователь нажимает "Войти через Telegram"
- Открывается WebView с Telegram Login Widget
- Widget загружается с официального сервера Telegram

### 2. Процесс авторизации
- Пользователь нажимает кнопку "Log in with Telegram"
- Telegram открывает окно авторизации
- После входа Telegram перенаправляет на callback URL

### 3. Получение данных
- Приложение перехватывает callback URL
- Извлекает данные пользователя из URL параметров
- Проверяет подлинность данных через HMAC-SHA256

### 4. Сохранение сессии
- Данные сохраняются в SharedPreferences
- Сессия остается активной до выхода пользователя
- При перезапуске приложения сессия восстанавливается

## 📊 Структура полученных данных

```kotlin
data class TelegramAuthData(
    val id: Long,           // Уникальный ID пользователя в Telegram
    val first_name: String, // Имя пользователя
    val last_name: String?, // Фамилия (если указана в профиле)
    val username: String?,  // Username (если установлен)
    val photo_url: String?, // URL аватара (если доступен)
    val auth_date: Long,    // Время авторизации (Unix timestamp)
    val hash: String        // Хеш для проверки подлинности
)
```

## 🔒 Безопасность

### Проверка подлинности данных
Все данные от Telegram проверяются по алгоритму:

1. Извлекается `hash` из полученных данных
2. Остальные данные сортируются и объединяются в строку
3. Вычисляется HMAC-SHA256 с использованием `SHA256(bot_token)` как ключа
4. Сравнивается вычисленный hash с полученным

### Защита от подделки
- Невозможно подделать данные без знания токена бота
- Токен бота хранится только в приложении
- Все запросы идут через официальные серверы Telegram

## 📱 Использование приложения

### Первый запуск
1. Откройте приложение SOSTaxi
2. Нажмите кнопку **"Настройки"**
3. В разделе "Telegram авторизация" нажмите **"Войти через Telegram"**
4. В открывшемся WebView нажмите **"Log in with Telegram"**
5. Войдите в ваш аккаунт Telegram
6. Разрешите доступ приложению

### После авторизации
- Ваши реальные данные из Telegram отображаются в настройках
- Можно выбрать контакты для экстренных уведомлений
- При активации SOS функций ваши данные включаются в сообщения

## 🔧 Отладка и тестирование

### Проверка настроек бота
1. Убедитесь, что токен бота корректный
2. Проверьте, что домен настроен в @BotFather
3. Убедитесь, что домен доступен по HTTPS

### Логирование
Приложение выводит подробные логи:
```
D/TelegramAuth: URL: https://your-domain.com/auth/callback?id=123456789&first_name=John...
D/TelegramAuth: Page finished: https://oauth.telegram.org/auth
```

### Тестирование на устройстве
1. Соберите и установите приложение
2. Убедитесь, что устройство подключено к интернету
3. Откройте настройки и попробуйте авторизацию
4. Проверьте логи в случае ошибок

## ⚠️ Известные ограничения

### WebView ограничения
- Требует подключения к интернету
- Может не работать в некоторых эмуляторах
- Зависит от настроек безопасности устройства

### Telegram ограничения
- Домен должен быть настроен в @BotFather
- Не работает с localhost в продакшене
- Требует HTTPS для всех доменов

### Контакты Telegram
- Реальные контакты Telegram можно получить только через TDLib
- Сейчас используются контакты из телефонной книги Android
- Планируется интеграция с TDLib в будущих версиях

## 🚀 Дальнейшие улучшения

### Планируемые функции
- [ ] Интеграция с TDLib для получения контактов Telegram
- [ ] Отправка сообщений через Telegram Bot API
- [ ] Групповые чаты для экстренных ситуаций
- [ ] Синхронизация с Telegram контактами

### Возможные улучшения
- [ ] Кэширование аватаров пользователей
- [ ] Автоматическое обновление токена
- [ ] Биометрическая защита сессии
- [ ] Офлайн-режим с сохранением данных

## 🆘 Поддержка

### При возникновении проблем:

1. **Ошибка "Domain not set"**
   - Убедитесь, что домен настроен в @BotFather
   - Проверьте, что домен доступен по HTTPS

2. **WebView не загружается**
   - Проверьте подключение к интернету
   - Убедитесь, что JavaScript включен в WebView

3. **Ошибка проверки данных**
   - Проверьте корректность токена бота
   - Убедитесь, что время на устройстве синхронизировано

4. **Callback URL не работает**
   - Проверьте настройки домена в @BotFather
   - Убедитесь, что redirect URL корректный

### Полезные ссылки
- [Документация Telegram Bot API](https://core.telegram.org/bots/api)
- [Telegram Login Widget](https://core.telegram.org/widgets/login)
- [OAuth 2.0 с Telegram](https://medium.com/@tech.engineer.jedi/oauth2-0-with-telegram-1c321a9dca27)

---

**Готово!** Теперь ваше приложение SOSTaxi работает с реальной авторизацией Telegram! 🎉 