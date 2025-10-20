package com.example.sostaxi

import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Accessibility Service для отслеживания нажатий Bluetooth-кнопки через окно громкости.
 * 
 * ⚠️ КРИТИЧЕСКИ ВАЖНО: Работает ТОЛЬКО если:
 * 1. В настройках выбран конкретный MAC-адрес BLE-кнопки
 * 2. Эта BLE-кнопка РЕАЛЬНО подключена (ACL_CONNECTED) прямо сейчас
 * 3. Режим "только BLE" включен (по умолчанию true)
 * 
 * ПРОБЛЕМА:
 * И Bluetooth-кнопка, и физические кнопки громкости открывают окно громкости.
 * 
 * РЕШЕНИЕ (3 уровня защиты):
 * 
 * УРОВЕНЬ 1 - Проверка выбора устройства:
 * - Если MAC-адрес НЕ выбран → игнорируем ВСЕ события громкости
 * 
 * УРОВЕНЬ 2 - Проверка подключения:
 * - Если выбранная кнопка НЕ подключена → игнорируем ВСЕ события громкости
 * 
 * УРОВЕНЬ 3 - Проверка источника нажатия (4 секунды):
 * 1. Окно громкости открылось → запоминаем громкость
 * 2. Ждём 4 секунды (достаточно долго!)
 * 3. Проверяем:
 *    - Громкость НЕ изменилась за 4 секунды? → Bluetooth-кнопка → ЗАПУСКАЕМ ✅
 *    - Громкость изменилась? → Физическая кнопка → игнорируем ❌
 * 
 * ЛОГИКА: Пользователь точно не будет ждать 4 секунды после нажатия 
 * физической кнопки громкости без изменения громкости. Это отличный
 * баланс между скоростью отклика и надёжностью!
 * 
 * СЦЕНАРИИ:
 * - Выбранная BLE-кнопка подключена + нажата → запуск ✅
 * - Физическая кнопка телефона → игнорируем ❌
 * - Другая Bluetooth-кнопка → игнорируем ❌
 * - Никакая кнопка не выбрана → игнорируем ❌
 */
class VolumeButtonAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "VolumeButtonAccess"
        private var lastTriggerTime = 0L
        private const val DEBOUNCE_INTERVAL = 1000L // 1 секунда между срабатываниями
        private const val VOLUME_CHECK_DELAY = 4000L // 4 секунды - баланс между скоростью и надёжностью
        private const val PHYSICAL_BUTTON_MIN_INTERVAL = 50L // Физические кнопки срабатывают очень быстро при повторных нажатиях
    }
    
    private lateinit var audioManager: AudioManager
    private var lastKnownVolume = -1
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingVolumeCheck: Runnable? = null
    private var isCheckingVolume = false // Флаг активной проверки
    private var lastVolumeDialogOpenTime = 0L // Время последнего открытия диалога громкости
    private var volumeDialogOpenCount = 0 // Счётчик быстрых открытий (физическая кнопка удержана)
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        lastKnownVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        Log.d(TAG, "=== Accessibility Service ПОДКЛЮЧЕН ===")
        Log.d(TAG, "Текущая громкость: $lastKnownVolume")
        Log.d(TAG, "Режим: отслеживание открытия окна громкости (event 32)")
        
        // ДИАГНОСТИКА: Логируем все подключенные InputDevice
        logAllInputDevices()
        
        // Показываем уведомление
        android.widget.Toast.makeText(
            this,
            "✓ SOS Taxi: Bluetooth-кнопка активна (одно нажатие)",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    
    /**
     * Логирование всех InputDevice для диагностики
     */
    private fun logAllInputDevices() {
        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "📱 СПИСОК ВСЕХ INPUT DEVICES:")
        Log.d(TAG, "═══════════════════════════════════════════════════")
        
        val deviceIds = InputDevice.getDeviceIds()
        for (deviceId in deviceIds) {
            val device = InputDevice.getDevice(deviceId)
            if (device != null) {
                val name = device.name
                val vendorId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) device.vendorId else 0
                val productId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) device.productId else 0
                val sources = device.sources
                val descriptor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) device.descriptor else "N/A"
                
                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d(TAG, "Device ID: $deviceId")
                Log.d(TAG, "Name: $name")
                Log.d(TAG, "Vendor ID: 0x${vendorId.toString(16).uppercase()}")
                Log.d(TAG, "Product ID: 0x${productId.toString(16).uppercase()}")
                Log.d(TAG, "Descriptor: $descriptor")
                Log.d(TAG, "Sources: $sources")
                
                // Декодируем sources
                val sourcesList = mutableListOf<String>()
                if ((sources and InputDevice.SOURCE_KEYBOARD) != 0) sourcesList.add("KEYBOARD")
                if ((sources and InputDevice.SOURCE_DPAD) != 0) sourcesList.add("DPAD")
                if ((sources and InputDevice.SOURCE_TOUCHSCREEN) != 0) sourcesList.add("TOUCHSCREEN")
                if ((sources and InputDevice.SOURCE_MOUSE) != 0) sourcesList.add("MOUSE")
                if ((sources and InputDevice.SOURCE_BLUETOOTH_STYLUS) != 0) sourcesList.add("BLUETOOTH_STYLUS")
                
                Log.d(TAG, "Sources decoded: ${sourcesList.joinToString(", ")}")
                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            }
        }
        
        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "ℹ️ Найдите вашу HID-кнопку в списке выше")
        Log.d(TAG, "ℹ️ Запишите её Name, Vendor ID, Product ID")
        Log.d(TAG, "═══════════════════════════════════════════════════")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // КРИТИЧЕСКИ ВАЖНАЯ ПРОВЕРКА: реагируем ТОЛЬКО если выбранная кнопка реально подключена
        val prefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
        val selectedBleAddress = prefs.getString("ble_device_address", null)
        val bleOnlyMode = prefs.getBoolean("ble_only_mode", true)
        
        // СТРОГАЯ ПРОВЕРКА: Если не выбран MAC-адрес - игнорируем ВСЕ события
        if (selectedBleAddress.isNullOrEmpty() || selectedBleAddress == "unknown") {
            Log.v(TAG, "⛔ BLE-кнопка НЕ ВЫБРАНА в настройках → игнорируем все события громкости")
            return
        }
        
        // Если режим "только BLE" включен и выбран адрес
        if (bleOnlyMode) {
            // Проверяем, РЕАЛЬНО ли подключено устройство с этим MAC-адресом прямо сейчас
            val isDeviceConnected = isBluetoothDeviceConnected(selectedBleAddress)
            
            Log.d(TAG, "🔍 Проверка подключения: адрес=$selectedBleAddress, подключено=$isDeviceConnected")
            
            if (!isDeviceConnected) {
                // Выбранная кнопка НЕ подключена → ИГНОРИРУЕМ все события громкости
                Log.v(TAG, "⛔ Выбранная BLE-кнопка НЕ подключена → игнорируем события громкости")
                return
            }
            
            Log.d(TAG, "✅ Выбранная BLE-кнопка ПОДКЛЮЧЕНА → обрабатываем события")
        }

        val eventType = event.eventType
        
        // Детальное логирование для отладки (только TYPE_WINDOW_STATE_CHANGED)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.v(TAG, "Event: type=$eventType, pkg=${event.packageName}, class=${event.className}")
        }
        
        // TYPE_WINDOW_STATE_CHANGED = 32 - это открытие окна (в том числе окна громкости)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            // Проверяем, что это окно громкости Android
            val packageName = event.packageName?.toString() ?: ""
            val className = event.className?.toString() ?: ""
            
            // ИГНОРИРУЕМ события от собственного приложения!
            if (packageName == "com.example.sostaxi") {
                Log.v(TAG, "→ Игнорируем события собственного приложения")
                return
            }
            
            Log.d(TAG, "🔔 TYPE_WINDOW_STATE_CHANGED: pkg=$packageName, class=$className")
            
            // СТРОГАЯ проверка окна громкости:
            // Класс ОБЯЗАТЕЛЬНО должен содержать "Volume" в названии
            val isVolumeDialog = className.contains("Volume", ignoreCase = true)
            
            if (isVolumeDialog) {
                // Окно громкости открылось
                val currentTime = System.currentTimeMillis()
                
                // ДОПОЛНИТЕЛЬНАЯ ПРОВЕРКА: Физические кнопки при удержании быстро открывают диалог много раз
                if (currentTime - lastVolumeDialogOpenTime < PHYSICAL_BUTTON_MIN_INTERVAL) {
                    volumeDialogOpenCount++
                    if (volumeDialogOpenCount > 2) {
                        Log.d(TAG, "⚠️ Обнаружено быстрое повторное открытие (${volumeDialogOpenCount}x) → физическая кнопка удерживается")
                        lastVolumeDialogOpenTime = currentTime
                        return
                    }
                } else {
                    // Прошло достаточно времени - сбрасываем счётчик
                    volumeDialogOpenCount = 0
                }
                lastVolumeDialogOpenTime = currentTime
                
                // Если уже проверяем - игнорируем повторное открытие
                if (isCheckingVolume) {
                    Log.v(TAG, "→ Окно уже открыто, проверка в процессе")
                    return
                }
                
                val volumeAtOpen = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                Log.d(TAG, "✓ Окно громкости ОТКРЫЛОСЬ, громкость: $volumeAtOpen")
                
                isCheckingVolume = true
                
                // Отменяем предыдущую проверку (если есть)
                pendingVolumeCheck?.let {
                    handler.removeCallbacks(it)
                }
                
                // Создаём отложенную проверку через 4 секунды
                pendingVolumeCheck = Runnable {
                    val volumeAfterDelay = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val checkTime = System.currentTimeMillis()
                    Log.d(TAG, "Проверка через ${VOLUME_CHECK_DELAY / 1000} сек: $volumeAtOpen -> $volumeAfterDelay")
                    
                    // МНОГОУРОВНЕВАЯ ПРОВЕРКА:
                    // 1. Громкость НЕ изменилась за 4 секунды (ОЧЕНЬ ДОЛГО для физической кнопки!)
                    // 2. Не было быстрых повторных открытий (физическая кнопка удержана)
                    val volumeNotChanged = (volumeAfterDelay == volumeAtOpen)
                    val noRapidReopens = (volumeDialogOpenCount <= 2)
                    
                    if (volumeNotChanged && noRapidReopens) {
                        Log.d(TAG, "🎯 Громкость НЕ изменилась за 4 секунды + нет быстрых повторов → это Bluetooth-кнопка!")
                        Log.d(TAG, "   (Пользователь точно не ждёт 4 секунды без изменения громкости - это ваша HID-кнопка!)")
                        
                        // Проверяем debounce
                        if (checkTime - lastTriggerTime > DEBOUNCE_INTERVAL) {
                            Log.d(TAG, "✓ ЗАПУСКАЕМ ДЕЙСТВИЕ!")
                            lastTriggerTime = checkTime
                            // Сбрасываем счётчик после успешного запуска
                            volumeDialogOpenCount = 0
                            handleButtonPress()
                        } else {
                            val remaining = DEBOUNCE_INTERVAL - (checkTime - lastTriggerTime)
                            Log.d(TAG, "⏱ Debounce активен, осталось ${remaining}мс")
                        }
                    } else {
                        if (!volumeNotChanged) {
                            Log.d(TAG, "→ Громкость изменилась за 4 секунды → физическая кнопка, игнорируем")
                        }
                        if (!noRapidReopens) {
                            Log.d(TAG, "→ Обнаружены быстрые повторные открытия (${volumeDialogOpenCount}x) → физическая кнопка удерживается, игнорируем")
                        }
                        // Сбрасываем счётчик, если это была физическая кнопка
                        volumeDialogOpenCount = 0
                    }
                    
                    // Обновляем состояние
                    lastKnownVolume = volumeAfterDelay
                    isCheckingVolume = false
                    pendingVolumeCheck = null
                }
                
                // Запускаем проверку через 4 секунды
                handler.postDelayed(pendingVolumeCheck!!, VOLUME_CHECK_DELAY)
                Log.d(TAG, "Запланирована проверка через ${VOLUME_CHECK_DELAY / 1000} секунд")
                
            } else {
                // Открылось другое окно (не громкость)
                Log.v(TAG, "→ Открылось другое окно: $className")
            }
        }
        
        // Также обновляем громкость при других событиях, чтобы отслеживать изменения
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVolume != lastKnownVolume) {
                Log.v(TAG, "Громкость обновлена: $lastKnownVolume -> $currentVolume")
                
                // Если идёт проверка И громкость изменилась → отменяем проверку (физическая кнопка)
                if (isCheckingVolume && pendingVolumeCheck != null) {
                    handler.removeCallbacks(pendingVolumeCheck!!)
                    Log.d(TAG, "⚠️ Отменена проверка - громкость изменилась (физическая кнопка)")
                    pendingVolumeCheck = null
                    isCheckingVolume = false
                }
                
                lastKnownVolume = currentVolume
            }
        }
    }
    
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        
        // НОВАЯ ДИАГНОСТИКА: Логируем источник события
        if (event.action == KeyEvent.ACTION_DOWN && 
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            
            val deviceId = event.deviceId
            val device = InputDevice.getDevice(deviceId)
            
            if (device != null) {
                val name = device.name
                val vendorId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) device.vendorId else 0
                val productId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) device.productId else 0
                val sources = device.sources
                
                Log.d(TAG, "═══════════════════════════════════════════════════")
                Log.d(TAG, "🔑 VOLUME KEY EVENT DETECTED!")
                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d(TAG, "KeyCode: ${event.keyCode}")
                Log.d(TAG, "Device ID: $deviceId")
                Log.d(TAG, "Device Name: $name")
                Log.d(TAG, "Vendor ID: 0x${vendorId.toString(16).uppercase()}")
                Log.d(TAG, "Product ID: 0x${productId.toString(16).uppercase()}")
                Log.d(TAG, "Sources: $sources")
                Log.d(TAG, "═══════════════════════════════════════════════════")
                
                // Проверяем, является ли это нашей HID-кнопкой
                val prefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                val savedDeviceName = prefs.getString("hid_button_device_name", null)
                val savedVendorId = prefs.getInt("hid_button_vendor_id", -1)
                val savedProductId = prefs.getInt("hid_button_product_id", -1)
                
                if (savedDeviceName != null && name == savedDeviceName &&
                    (savedVendorId == -1 || vendorId == savedVendorId) &&
                    (savedProductId == -1 || productId == savedProductId)) {
                    
                    Log.d(TAG, "✅ ЭТО НАША HID-КНОПКА!")
                    
                    // Проверяем debounce
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastTriggerTime > DEBOUNCE_INTERVAL) {
                        Log.d(TAG, "✓ ЗАПУСКАЕМ ДЕЙСТВИЕ ИЗ onKeyEvent!")
                        lastTriggerTime = currentTime
                        handleButtonPress()
                        return true // Блокируем событие, чтобы не изменялась громкость
                    }
                } else {
                    Log.d(TAG, "❌ Это НЕ наша HID-кнопка (ожидается: $savedDeviceName)")
                }
            } else {
                Log.d(TAG, "⚠️ KeyEvent без InputDevice (deviceId=$deviceId)")
            }
        }
        
        // Не блокируем события, пропускаем их дальше
        return false
    }
    
    /**
     * Проверяет, подключено ли Bluetooth-устройство с указанным MAC-адресом прямо сейчас
     */
    private fun isBluetoothDeviceConnected(address: String): Boolean {
        try {
            // Проверяем разрешение BLUETOOTH_CONNECT для Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "⚠️ Нет разрешения BLUETOOTH_CONNECT для проверки подключения")
                    // Без разрешения не можем проверить, но не блокируем работу
                    // Возвращаем true, чтобы полагаться на старую логику с флагом
                    return true
                }
            }
            
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (bluetoothManager == null) {
                Log.w(TAG, "⚠️ BluetoothManager недоступен")
                return false
            }
            
            val bluetoothAdapter = bluetoothManager.adapter
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                Log.w(TAG, "⚠️ Bluetooth выключен или недоступен")
                return false
            }
            
            // Получаем список всех подключенных устройств
            val connectedDevices = bluetoothAdapter.bondedDevices ?: emptySet()
            
            for (device in connectedDevices) {
                if (device.address.equals(address, ignoreCase = true)) {
                    // Устройство спарено, но нам нужно проверить, действительно ли оно подключено
                    // К сожалению, нет универсального API для проверки ACL-подключения,
                    // поэтому полагаемся на флаг из BluetoothReceiver
                    val prefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
                    val isConnected = prefs.getBoolean("ble_device_connected", false)
                    Log.d(TAG, "📱 Устройство $address найдено в bondedDevices, флаг подключения: $isConnected")
                    return isConnected
                }
            }
            
            Log.d(TAG, "📱 Устройство $address не найдено среди спаренных")
            return false
            
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException при проверке подключения: ${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка проверки подключения: ${e.message}")
            return false
        }
    }
    
    /**
     * Обработка нажатия кнопки
     */
    private fun handleButtonPress() {
        val prefs = getSharedPreferences("taxi_sos_prefs", Context.MODE_PRIVATE)
        
        // Проверяем, включена ли функция (по умолчанию true, так как чекбокс удалён из UI)
        val isEnabled = prefs.getBoolean("bluetooth_button_enabled", true)
        if (!isEnabled) {
            Log.d(TAG, "✗ Функция отключена в настройках, игнорируем")
            return
        }
        
        val selectedAction = prefs.getString("bluetooth_button_action", "VIDEO_SEGMENTS") ?: "VIDEO_SEGMENTS"
        val selectedAddress = prefs.getString("ble_device_address", "unknown")
        
        Log.d(TAG, "═══════════════════════════════════════════════════")
        Log.d(TAG, "🎯 НАЖАТИЕ ВЫБРАННОЙ BLE-КНОПКИ!")
        Log.d(TAG, "📱 MAC-адрес: $selectedAddress")
        Log.d(TAG, "⚙️ Режим: $selectedAction")
        Log.d(TAG, "📊 Приложение активно: ${MainActivity.isActive}")
        Log.d(TAG, "═══════════════════════════════════════════════════")
        
        if (MainActivity.isActive) {
            // Если приложение активно, останавливаем
            Log.d(TAG, "→ Останавливаем приложение")
            stopApp()
        } else {
            // Если приложение неактивно, запускаем
            Log.d(TAG, "→ Запускаем приложение в режиме: $selectedAction")
            startApp(selectedAction)
        }
    }
    
    /**
     * Запуск приложения
     */
    private fun startApp(mode: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("quick_tile_mode", mode)
            putExtra("auto_start", true)
            putExtra("from_bluetooth_button", true)
        }
        
        try {
            startActivity(intent)
            Log.d(TAG, "Приложение запущено из Accessibility Service")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска приложения: ${e.message}")
        }
    }
    
    /**
     * Остановка приложения
     */
    private fun stopApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("quick_tile_action", "stop_and_close")
            putExtra("from_bluetooth_button", true)
        }
        
        try {
            startActivity(intent)
            Log.d(TAG, "Команда остановки отправлена из Accessibility Service")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка остановки приложения: ${e.message}")
        }
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "=== Accessibility Service ПРЕРВАН ===")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "=== Accessibility Service УНИЧТОЖЕН ===")
        
        // Отменяем отложенную проверку (если есть)
        pendingVolumeCheck?.let {
            handler.removeCallbacks(it)
            pendingVolumeCheck = null
        }
        isCheckingVolume = false
        
        // Показываем уведомление
        android.widget.Toast.makeText(
            this,
            "⚠ SOS Taxi: Bluetooth-кнопка отключена",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

