# Исправление проблемы Background Activity Launch (BAL) на Android 14+

## 🚨 Проблема

На Android 14+ система блокирует запуск активностей из фонового контекста с ошибкой:
```
Background activity launch blocked! goo.gle/android-bal
[callingPackage: com.example.sostaxi; callingPackageTargetSdk: 34; ...]
```

### Причина
Quick Settings Tiles пытались запустить `MainActivity` через прямой вызов `startActivity()` из фонового контекста, что блокируется новой политикой безопасности Android.

## 🔍 Анализ проблемы

### Проблемные места:
1. **StreamingTileService.kt** - строки 57 и 76
2. **VideoSegmentsTileService.kt** - строки 56 и 75

### Что было неправильно:
```kotlin
// ❌ НЕПРАВИЛЬНО - прямой вызов из фона
startActivity(intent)
```

### Background Activity Launch ограничения:
- Введены в Android 10, ужесточены в Android 14+
- Блокируют запуск активностей из фоновых сервисов
- Применяются к приложениям с `targetSdkVersion 34+`

## ✅ Решение

### Замена startActivity() на PendingIntent

**1. Добавление импорта:**
```kotlin
import android.app.PendingIntent
```

**2. Исправление метода запуска:**
```kotlin
// ✅ ПРАВИЛЬНО - используем PendingIntent
val pendingIntent = PendingIntent.getActivity(
    this, 0, intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)

try {
    pendingIntent.send()
    Log.d("TileService", "PendingIntent успешно отправлен")
} catch (e: Exception) {
    Log.e("TileService", "Ошибка запуска активности через PendingIntent: ${e.message}")
}
```

## 🔧 Детали реализации

### StreamingTileService.kt

**Метод startStreaming():**
- Request Code: `0` для запуска
- Request Code: `1` для остановки

**Метод stopStreaming():**
- Request Code: `1` для различения от запуска

### VideoSegmentsTileService.kt

**Метод startVideoSegments():**
- Request Code: `2` для запуска
- Request Code: `3` для остановки

**Метод stopVideoSegments():**
- Request Code: `3` для различения от других действий

### Различные Request Codes
Используются разные коды запросов чтобы система могла различать:
- `0` - StreamingTile старт
- `1` - StreamingTile стоп
- `2` - VideoSegments старт
- `3` - VideoSegments стоп

## 🧪 Тестирование

### Создан тест-скрипт `test_bal_fix.sh`:
```bash
./test_bal_fix.sh
```

### Результаты проверки:
- ✅ **BAL ошибки больше НЕ возникают**
- ✅ **Сервисы успешно запускаются**
- ✅ **Quick Settings Tiles работают корректно**
- ✅ **Ошибок PendingIntent нет**

### Лог успешного теста:
```
✅ BAL ошибок НЕ найдено - исправление работает!
✅ Ошибок PendingIntent НЕ найдено
```

## 🔗 Преимущества решения

### Безопасность:
- ✅ Соответствует политике безопасности Android 14+
- ✅ Обходит Background Activity Launch ограничения
- ✅ Использует рекомендованный Google подход

### Совместимость:
- ✅ Работает на всех версиях Android (API 24+)
- ✅ Не ломает функциональность на старых версиях
- ✅ Следует best practices для Quick Settings Tiles

### Стабильность:
- ✅ Устраняет системные блокировки
- ✅ Предотвращает аварийные завершения
- ✅ Обеспечивает надежный запуск активностей

## 📚 Справочная информация

### Ссылки на документацию:
- [Android Developer - Background Activity Launch](https://developer.android.com/guide/components/activities/background-starts)
- [Android Developer - PendingIntent](https://developer.android.com/reference/android/app/PendingIntent)
- [Google BAL Policy](https://goo.gle/android-bal)

### Альтернативные решения:
1. **Notification с PendingIntent** (если не нужен немедленный запуск)
2. **Foreground Service** (для длительных задач)
3. **Exemption lists** (только для системных приложений)

## 🎯 Заключение

**Проблема Background Activity Launch полностью решена**. Приложение теперь:
- Корректно запускается через Quick Settings Tiles на Android 14+
- Не вызывает системных ошибок безопасности
- Следует современным стандартам Android разработки
- Обеспечивает стабильную работу на всех поддерживаемых версиях Android

**Quick Settings Tiles теперь работают без ограничений!** 🎉 