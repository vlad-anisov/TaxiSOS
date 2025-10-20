# Исправление: Камера остается активной после остановки трансляции

## Проблема

После остановки трансляции или отправки видео сегментов камера оставалась активной, хотя приложение должно было полностью закрыться. В логах появлялись предупреждения:

```
A resource failed to call Surface.release.
A resource failed to call close.
```

**Дополнительная проблема:** Аудио продолжало захватываться и отправляться даже после остановки видео. См. `AUDIO_STOP_FIX.md` для деталей.

## Причина

Камера останавливалась методами `stopStream()`, `stopRecord()`, `stopPreview()`, но ссылки на объекты камеры не обнулялись, что приводило к тому, что ресурсы не освобождались полностью. В логах появлялись предупреждения о незакрытых Surface и других ресурсах.

**Важно:** Проект использует библиотеку **RootEncoder 2.5.9** (`com.github.pedroSG94.RootEncoder`), в которой у класса `RtmpCamera2` **нет метода `release()`**. Освобождение ресурсов происходит автоматически при вызове `stopPreview()`.

## Решение

### 1. Добавлено обнуление ссылок на камеры в методе `stop()` (строки 1272-1288)

```kotlin
// Останавливаем камеры
try {
    streamingCamera?.stopStream()
    streamingCamera?.stopRecord()
    streamingCamera?.stopPreview()
    recordingCamera?.stopRecord()
    recordingCamera?.stopPreview()
    
    // ВАЖНО: В RootEncoder ресурсы освобождаются при stopPreview()
    // Обнуляем ссылки на камеры для полного освобождения памяти
    streamingCamera = null
    recordingCamera = null
    
    Log.d("MainActivity", "Камеры полностью остановлены и освобождены")
} catch (e: Exception) {
    Log.e("MainActivity", "Ошибка остановки камер: ${e.message}")
}
```

### 2. Добавлено обнуление ссылок на камеры в методе `onDestroy()` (строки 1811-1825)

```kotlin
// Принудительно освобождаем камеры
try {
    Log.d("MainActivity", "Освобождение ресурсов камер...")
    streamingCamera?.stopStream()
    streamingCamera?.stopRecord()
    streamingCamera?.stopPreview()
    recordingCamera?.stopRecord()
    recordingCamera?.stopPreview()
    
    // В RootEncoder ресурсы освобождаются при stopPreview()
    // Обнуляем ссылки на камеры для полного освобождения памяти
    streamingCamera = null
    recordingCamera = null
    Log.d("MainActivity", "Ресурсы камер освобождены")
} catch (e: Exception) {
    Log.e("MainActivity", "Ошибка освобождения камер: ${e.message}")
}
```

### 3. Обновлен finally блок `startRecord()` (строки 1499-1510)

```kotlin
finally {
    // Полностью останавливаем камеру только при завершении
    try {
        Log.d("MainActivity", "Окончательная остановка камеры записи")
        recordingCamera?.stopRecord()
        recordingCamera?.stopPreview()
        // В RootEncoder ресурсы освобождаются автоматически при stopPreview()
        Log.d("MainActivity", "Камера записи полностью освобождена")
    } catch (e: Exception) {
        Log.e("MainActivity", "Ошибка остановки камеры записи: ${e.message}")
    }
}
```

### 4. Добавлена реинициализация камер при повторном запуске

#### В `startStream()` (строки 1334-1340):
```kotlin
// Проверяем и пересоздаем камеру, если она была освобождена
if (streamingCamera == null) {
    Log.d("MainActivity", "Пересоздаем стриминговую камеру после освобождения")
    streamingCamera = RtmpCamera2(openGlView, this)
    streamingCamera?.switchCamera()
    isStreamingCameraPrepared = false
}
```

#### В `startRecord()` (строки 1379-1385):
```kotlin
// Проверяем и пересоздаем камеру, если она была освобождена
if (recordingCamera == null) {
    Log.d("MainActivity", "Пересоздаем камеру записи после освобождения")
    recordingCamera = RtmpCamera2(openGlView, this@MainActivity)
    recordingCamera?.switchCamera()
    isRecordingCameraPrepared = false
}
```

## Важная информация о библиотеке RootEncoder

Проект использует библиотеку **RootEncoder 2.5.9** от pedroSG94:
```gradle
implementation 'com.github.pedroSG94.RootEncoder:rtmp:2.5.9'
implementation 'com.github.pedroSG94.RootEncoder:library:2.5.9'
```

**В классе `RtmpCamera2` НЕТ метода `release()`**. Освобождение ресурсов происходит так:
- `stopStream()` - останавливает RTMP стриминг
- `stopRecord()` - останавливает запись в файл
- `stopPreview()` - **автоматически освобождает все ресурсы камеры**, включая Surface
- Обнуление ссылки (`camera = null`) - освобождает память объекта

## Результат

Теперь камера корректно освобождается при:
1. ✅ Остановке через плитку Quick Settings
2. ✅ Остановке через кнопку Bluetooth
3. ✅ Закрытии приложения
4. ✅ Завершении записи сегментов

При повторном запуске камера автоматически пересоздается, если была освобождена ранее.

## Тестирование

Для проверки исправления:

1. Запустите трансляцию или запись сегментов
2. Остановите через Bluetooth-кнопку или плитку
3. Проверьте логи - должно появиться: `Камеры полностью остановлены и освобождены`
4. Убедитесь, что больше нет предупреждений:
   - ❌ `A resource failed to call Surface.release`
   - ❌ `A resource failed to call close`
5. Запустите снова - камера должна пересоздаться автоматически

## Ошибка "Unresolved reference: release"

Если вы видите ошибку `Unresolved reference: release` - это нормально! 

**У `RtmpCamera2` нет метода `release()`** в библиотеке RootEncoder. Используйте только:
- `stopStream()`
- `stopRecord()`
- `stopPreview()` ← этот метод освобождает все ресурсы
- Обнуление ссылки: `camera = null`

## Дата исправления

20 октября 2025

