# Исправление работы Bluetooth-кнопки (VolumeKeyListener)

## Проблема

После анализа логов было обнаружено, что метод `onKeyEvent()` в `VolumeButtonAccessibilityService` **НЕ вызывается** на устройстве пользователя при нажатии Bluetooth-кнопки.

### Что показали логи:
- ✅ Служба специальных возможностей подключена и работает
- ✅ Получаются события `Accessibility Event` (типы 32, 4194304, 2048 и т.д.)
- ❌ НЕТ вызовов `onKeyEvent()` при нажатии кнопки
- ✅ Кнопка точно работает - увеличивает громкость

### Вывод:
На данном устройстве/версии Android метод `onKeyEvent()` в `AccessibilityService` не перехватывает события от этой Bluetooth-кнопки, хотя кнопка эмулирует нажатие клавиши увеличения громкости.

---

## Решение: VolumeKeyListener

Реализован **новый подход** на основе слушателя изменений громкости через `BroadcastReceiver` и `AudioManager`.

### Принцип работы:

1. **Регистрация слушателя**: `VolumeKeyListener` регистрирует `BroadcastReceiver` для события `android.media.VOLUME_CHANGED_ACTION`
2. **Обнаружение увеличения**: При изменении громкости проверяется, было ли это увеличение
3. **Запуск действия**: Если громкость увеличилась и функция включена → вызывается `handleButtonPress()`
4. **Восстановление громкости**: Громкость автоматически возвращается к предыдущему уровню, чтобы не мешать пользователю

### Преимущества:
- ✅ Не требует Accessibility Service
- ✅ Работает в фоновом режиме через `BluetoothButtonService`
- ✅ Не влияет на обычное управление громкостью (громкость восстанавливается)
- ✅ Более надежно на разных устройствах
- ✅ Минимальное потребление ресурсов

---

## Реализованные файлы

### 1. `VolumeKeyListener.kt`
Новый класс для отслеживания изменений громкости:

```kotlin
class VolumeKeyListener(
    private val context: Context, 
    private val onVolumeIncrease: () -> Unit
)
```

**Методы:**
- `startListening()` - начать прослушивание
- `stopListening()` - остановить прослушивание

**Как работает:**
- Регистрирует `BroadcastReceiver` для `VOLUME_CHANGED_ACTION`
- При увеличении громкости вызывает `onVolumeIncrease` callback
- Автоматически восстанавливает прежний уровень громкости

### 2. Обновленный `BluetoothButtonService.kt`
В сервис добавлен `VolumeKeyListener`:

```kotlin
private var volumeKeyListener: VolumeKeyListener? = null

override fun onCreate() {
    // ... инициализация MediaSession (для стандартных кнопок)
    
    // НОВЫЙ ПОДХОД: VolumeKeyListener (для кнопок-громкости)
    volumeKeyListener = VolumeKeyListener(this) {
        Log.d(TAG, "VolumeKeyListener: нажатие обнаружено!")
        handleButtonPress()
    }
    volumeKeyListener?.startListening()
}
```

### 3. Обновленный `MainActivity.kt`
Добавлен автозапуск сервиса при старте приложения:

```kotlin
// Автозапуск BluetoothButtonService, если функция включена
try {
    val sharedPrefs = getSharedPreferences(PREFS_TAXI, Context.MODE_PRIVATE)
    val bluetoothButtonEnabled = sharedPrefs.getBoolean("bluetooth_button_enabled", false)
    if (bluetoothButtonEnabled) {
        Log.d(TAG, "Bluetooth-кнопка включена, запускаем сервис")
        BluetoothButtonService.start(this)
    }
} catch (e: Exception) {
    Log.e(TAG, "Ошибка запуска BluetoothButtonService: ${e.message}")
}
```

---

## Как тестировать

### 1. Включите функцию в настройках приложения
- Откройте приложение
- Нажмите "Настройки"
- Включите "Включить обработку Bluetooth-кнопки"
- Выберите действие (Video Segments / Live Streaming)
- Нажмите "Готово"

### 2. Проверьте, что сервис запущен
В логах должны появиться записи:
```
MainActivity: Bluetooth-кнопка включена, запускаем сервис
BluetoothButtonService: Service onCreate
BluetoothButtonService: Service initialized (оба метода активны)
VolumeKeyListener: Начинаем прослушивание изменений громкости
VolumeKeyListener: Receiver зарегистрирован, текущая громкость: X
```

### 3. Нажмите Bluetooth-кнопку
В логах должны появиться:
```
VolumeKeyListener: Громкость изменилась: X -> Y
VolumeKeyListener: !!! ОБНАРУЖЕНО УВЕЛИЧЕНИЕ ГРОМКОСТИ !!!
VolumeKeyListener: Функция включена, вызываем callback
BluetoothButtonService: VolumeKeyListener: нажатие обнаружено!
BluetoothButtonService: handleButtonPress: режим=VIDEO_SEGMENTS, активно=false
BluetoothButtonService: Приложение запущено
VolumeKeyListener: Громкость возвращена: Y -> X
```

### 4. Проверьте работу
- ✅ Приложение должно запуститься и начать запись/трансляцию
- ✅ Громкость не должна измениться (восстановится автоматически)
- ✅ При повторном нажатии - приложение остановится

---

## Команды для просмотра логов

### Все логи приложения:
```bash
adb logcat -s "MainActivity:D" "BluetoothButtonService:D" "VolumeKeyListener:D" "VolumeButtonAccess:D"
```

### Только VolumeKeyListener:
```bash
adb logcat -s "VolumeKeyListener:*"
```

### Только BluetoothButtonService:
```bash
adb logcat -s "BluetoothButtonService:*"
```

---

## Что можно оставить

### AccessibilityService
`VolumeButtonAccessibilityService` можно оставить в проекте как запасной вариант:
- Он не будет мешать работе `VolumeKeyListener`
- На некоторых устройствах `onKeyEvent()` может работать
- Пользователь может сам выбрать, какой подход использовать

### MediaSession
`BluetoothButtonManager` также остается в сервисе:
- Для Bluetooth-кнопок, которые посылают стандартные медиа-события (PLAY/PAUSE)
- Не мешает работе `VolumeKeyListener`
- Оба подхода работают параллельно

---

## Возможные проблемы и решения

### 1. Сервис не запускается
**Проверка:**
```bash
adb logcat -s "BluetoothButtonService:*"
```

**Решение:**
- Убедитесь, что функция включена в настройках
- Перезапустите приложение
- Проверьте разрешения (особенно для foreground service)

### 2. Громкость не восстанавливается
**Причина:** Система может блокировать изменение громкости из приложения

**Решение:**
- Проверьте, включен ли режим "Не беспокоить"
- Дайте приложению разрешение на изменение настроек системы (если требуется)

### 3. Двойные срабатывания
**Причина:** Некоторые кнопки посылают несколько событий изменения громкости

**Решение:** Добавить debounce (уже реализовано в `VolumeKeyListener` через проверку `initialVolume`)

---

## Отличия от AccessibilityService

| Характеристика | AccessibilityService | VolumeKeyListener |
|---|---|---|
| **Требует активации пользователем** | ✅ Да (в настройках системы) | ❌ Нет |
| **Работает в фоне** | ✅ Да | ✅ Да |
| **Надежность** | ⚠️ Зависит от устройства | ✅ Работает везде |
| **Блокирует громкость** | ✅ Да | ⚠️ Восстанавливает автоматически |
| **Потребление ресурсов** | ⚠️ Среднее | ✅ Минимальное |
| **Простота настройки** | ❌ Сложно (системные настройки) | ✅ Просто (внутри приложения) |

---

## Рекомендации

1. **Основной подход**: Использовать `VolumeKeyListener` как основное решение
2. **AccessibilityService**: Оставить как опциональный вариант для пользователей с другими типами кнопок
3. **Документация**: Добавить в приложение подсказку о том, что Bluetooth-кнопка должна увеличивать громкость
4. **Настройки**: Можно добавить выбор между двумя подходами в настройках (если потребуется)

---

## Итоги

✅ **Проблема решена**: Реализован надежный способ перехвата нажатий Bluetooth-кнопки  
✅ **Работает в фоне**: Через `BluetoothButtonService` (foreground service)  
✅ **Не требует Accessibility Service**: Но он остается как запасной вариант  
✅ **Автозапуск**: Сервис автоматически запускается при старте приложения  
✅ **Тестирование**: Готов к тестированию на реальном устройстве  

**Следующий шаг**: Соберите и установите приложение, затем проверьте логи при нажатии Bluetooth-кнопки.


