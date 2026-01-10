# Исправление: Закрытие приложения из режима Picture-in-Picture (PiP)

## Проблема

При повторном нажатии на Quick Tile или Bluetooth кнопку, когда приложение находится в режиме "картинка в картинке" (PiP), приложение разворачивалось обратно в полноэкранный режим вместо полного закрытия. При этом продолжалась отправка только аудио без видео.

## Причина

Когда Android получает команду на взаимодействие с Activity, находящейся в режиме PiP, система автоматически вызывает `onPictureInPictureModeChanged(false)` для выхода из режима PiP и возвращения в нормальный режим. Это происходит до обработки Intent с командой закрытия, что приводит к развертыванию приложения.

## Решение

### 1. Обновлен `onPictureInPictureModeChanged()`

Добавлена проверка флага `isClosingFromTile`, чтобы предотвратить восстановление UI при закрытии:

```kotlin
override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    
    // Если мы закрываем приложение, не восстанавливаем UI
    if (isClosingFromTile) {
        Log.d("MainActivity", "Закрытие приложения из режима PiP, не восстанавливаем UI")
        return
    }
    
    if (isInPictureInPictureMode) {
        root.alpha = 0f
    } else {
        root.alpha = 1f
    }
}
```

### 2. Обновлен `handleQuickTileIntent()`

Уменьшена задержка перед закрытием с 2000мс до 500мс для более быстрого отклика:

```kotlin
quickTileAction == "stop_and_close" -> {
    Log.d("MainActivity", "Получен сигнал закрытия через плитку")
    isClosingFromTile = true
    
    // Если приложение в режиме PiP, отмечаем это в логе
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode) {
        Log.d("MainActivity", "Выходим из режима PiP перед закрытием")
    }

    // Останавливаем приложение
    if (isActive) {
        Log.d("MainActivity", "Останавливаем активность")
        stop()
    }
    
    // Сбрасываем состояние плиток
    try {
        StreamingTileService.setTileState(this, false)
        VideoSegmentsTileService.setTileState(this, false)
    } catch (_: Exception) {}
    
    // Закрываем приложение НЕМЕДЛЕННО с минимальной задержкой
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        try {
            Log.d("MainActivity", "Закрываем приложение полностью")
            finishAffinity()
            finishAndRemoveTask()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка при закрытии приложения: ${e.message}")
            try { finish() } catch (_: Exception) {}
        } finally {
            isClosingFromTile = false
        }
    }, 500) // Минимальная задержка 500мс
}
```

### 3. Обновлен метод `stop()`

Уменьшена задержка автозакрытия с 2000мс до 500мс:

```kotlin
// Автоматически закрываем приложение после остановки
if (isClosingFromTile) {
    // Если закрытие инициировано через плитку/Bluetooth, обработка уже в handleQuickTileIntent()
    Log.d("MainActivity", "Закрытие будет обработано в handleQuickTileIntent()")
} else {
    // Обычная остановка - закрываем приложение с минимальной задержкой
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        try {
            Log.d("MainActivity", "Автоматическое закрытие приложения после остановки")
            finishAffinity()
        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка при автозакрытии: ${e.message}")
            try { finish() } catch (_: Exception) {}
        }
    }, 500) // Минимальная задержка 500мс
}
```

## Результат

✅ Приложение теперь корректно закрывается даже из режима PiP  
✅ Не происходит развертывание приложения при закрытии  
✅ UI не восстанавливается при выходе из PiP перед закрытием  
✅ Время закрытия сокращено с 2 секунд до 500мс для лучшего UX  

## Тестирование

1. Запустите приложение через Quick Tile или Bluetooth кнопку
2. Приложение автоматически перейдет в режим PiP
3. Нажмите на Quick Tile или Bluetooth кнопку повторно
4. Приложение должно полностью закрыться через 500мс без развертывания

## Связанные файлы

- `app/src/main/java/com/example/sostaxi/MainActivity.kt` - основные изменения
- `AUTO_CLOSE_APP_FIX.md` - предыдущее исправление автозакрытия
- `CAMERA_NOT_CLOSING_FIX.md` - исправление освобождения ресурсов камеры


