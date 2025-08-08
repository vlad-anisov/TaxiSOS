# Расширенные исправления Background Activity Launch для Android 14+
# Advanced Background Activity Launch Fixes for Android 14+

## Проблема / Problem

После первоначальных исправлений BAL в Quick Settings Tiles, на Android 14+ все еще возникали ошибки:
After initial BAL fixes in Quick Settings Tiles, Android 14+ still produced errors:

```
Background activity launch blocked! goo.gle/android-bal
balRequireOptInByPendingIntentCreator: true
realCallerStartMode: MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED
```

## Причина / Root Cause

Android 14+ (API 34+) ввел дополнительные ограничения на Background Activity Launch:
Android 14+ (API 34+) introduced additional Background Activity Launch restrictions:

1. **Строгая проверка PendingIntent** - требуется явное разрешение от создателя
2. **Дополнительные флаги безопасности** - новые требования к флагам PendingIntent
3. **Ограничения на системные сервисы** - TileService имеет ограниченные права запуска Activity

## Решение / Solution

### 1. Расширенные ActivityOptions

```kotlin
val activityOptions = if (Build.VERSION.SDK_INT >= 34) { // Android 14+
    try {
        ActivityOptions.makeBasic().apply {
            // Явное разрешение Background Activity Launch
            setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            // Дополнительные опции для Android 14+
            setLaunchDisplayId(0) // Primary display
        }
    } catch (e: Exception) {
        // Fallback с базовыми опциями
        try {
            ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
            }
        } catch (e2: Exception) {
            ActivityOptions.makeBasic()
        }
    }
} else {
    null
}
```

### 2. Дополнительные флаги PendingIntent

```kotlin
val pendingIntentFlags = if (Build.VERSION.SDK_INT >= 34) {
    PendingIntent.FLAG_UPDATE_CURRENT or 
    PendingIntent.FLAG_IMMUTABLE or
    PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
} else {
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
```

### 3. Расширенный метод отправки PendingIntent

```kotlin
if (Build.VERSION.SDK_INT >= 34) {
    // Попытка через send с дополнительными параметрами
    val sendResult = pendingIntent.send(
        this, // context
        0, // code
        null, // intent
        null, // onFinished
        null, // handler
        null, // requiredPermission
        activityOptions?.toBundle() // options
    )
} else {
    pendingIntent.send()
}
```

### 4. Дополнительные флаги Intent

```kotlin
val intent = Intent(this, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
            Intent.FLAG_ACTIVITY_CLEAR_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
    
    // Дополнительные данные для Android 14+
    if (Build.VERSION.SDK_INT >= 34) {
        putExtra("from_tile_service", true)
        putExtra("timestamp", System.currentTimeMillis())
    }
}
```

### 5. Уникальные request codes

```kotlin
val pendingIntent = PendingIntent.getActivity(
    this, 
    System.currentTimeMillis().toInt(), // Уникальный request code
    intent,
    pendingIntentFlags,
    activityOptions?.toBundle()
)
```

## Измененные файлы / Modified Files

1. **app/src/main/java/com/example/sostaxi/StreamingTileService.kt**
   - Добавлены расширенные ActivityOptions
   - Добавлены дополнительные флаги PendingIntent
   - Добавлен fallback механизм
   - Улучшена обработка ошибок

2. **app/src/main/java/com/example/sostaxi/VideoSegmentsTileService.kt**
   - Аналогичные изменения как в StreamingTileService
   - Синхронизированы методы для обеих tile services

## Тестирование / Testing

Используйте скрипт для тестирования:
Use the script for testing:

```bash
./test_advanced_android14_bal_fix.sh
```

### Что проверяет скрипт:
### What the script checks:

1. **Подключение устройства** - проверяет наличие Android устройства
2. **Информация об API уровне** - определяет версию Android
3. **Сборка и установка** - собирает и устанавливает приложение
4. **Мониторинг BAL ошибок** - отслеживает ошибки в real-time
5. **Проверка запуска приложения** - подтверждает успешный запуск
6. **Анализ результатов** - детальный анализ оставшихся проблем

## Потенциальные проблемы / Potential Issues

### Если BAL ошибки все еще возникают:
### If BAL errors still occur:

1. **Очень строгие настройки системы**
   - Некоторые производители (например, Samsung) могут иметь дополнительные ограничения
   - Проверьте настройки разработчика

2. **Оптимизация батареи**
   - Отключите оптимизацию батареи для SOSTaxi
   - Настройки > Батарея > Оптимизация батареи

3. **Разрешения приложения**
   - Проверьте все разрешения в настройках системы
   - Убедитесь, что приложение может отображаться поверх других приложений

4. **Настройки Quick Settings**
   - Попробуйте удалить и снова добавить tile в Quick Settings
   - Перезагрузите устройство после изменений

## Альтернативные подходы / Alternative Approaches

Если проблемы продолжаются, рассмотрите:
If issues persist, consider:

1. **Использование Notification с PendingIntent**
2. **Создание Shortcut вместо Tile**
3. **Использование Intent с ACTION_MAIN**
4. **Реализация через BroadcastReceiver**

## Технические детали / Technical Details

### Ключевые параметры BAL лога:
### Key BAL log parameters:

- `balRequireOptInByPendingIntentCreator: true` - требуется opt-in от создателя
- `balAllowedByPiCreator: BSP.ALLOW_BAL` - создатель разрешает BAL
- `realCallerStartMode: MODE_BACKGROUND_ACTIVITY_START_ALLOWED` - режим запуска
- `resultIfPiCreatorAllowsBal: BAL_BLOCK` - итоговый результат

### Совместимость:
### Compatibility:

- ✅ Android 14+ (API 34+) - полная поддержка расширенных исправлений
- ✅ Android 13 (API 33) - базовые исправления
- ✅ Android 12 и ниже - стандартный подход

## Заключение / Conclusion

Расширенные исправления обеспечивают максимальную совместимость с Android 14+ при сохранении работоспособности на старых версиях. Многоуровневый fallback механизм гарантирует стабильную работу Quick Settings Tiles.

Advanced fixes provide maximum Android 14+ compatibility while maintaining functionality on older versions. Multi-level fallback mechanism ensures stable Quick Settings Tiles operation. 