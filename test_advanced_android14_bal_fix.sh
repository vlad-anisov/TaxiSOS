#!/bin/bash

# Тест расширенных исправлений Background Activity Launch для Android 14+
# Test script for advanced Android 14+ Background Activity Launch fixes

echo "=== Тест расширенных исправлений BAL для Android 14+ ==="
echo "=== Testing Advanced Android 14+ BAL Fixes ==="
echo ""

# Проверяем подключение устройства
echo "1. Проверка подключенных устройств..."
adb devices | grep -E "(device|emulator)"
if [ $? -ne 0 ]; then
    echo "❌ Нет подключенных устройств Android"
    echo "❌ No Android devices connected"
    exit 1
fi

# Получаем информацию об устройстве
echo ""
echo "2. Информация об устройстве:"
DEVICE_MODEL=$(adb shell getprop ro.product.model)
ANDROID_VERSION=$(adb shell getprop ro.build.version.release)
API_LEVEL=$(adb shell getprop ro.build.version.sdk)
echo "   Модель: $DEVICE_MODEL"
echo "   Android: $ANDROID_VERSION (API $API_LEVEL)"

if [ "$API_LEVEL" -lt 34 ]; then
    echo "⚠️  Устройство имеет API уровень $API_LEVEL (< 34). BAL ограничения менее строгие."
    echo "⚠️  Device has API level $API_LEVEL (< 34). BAL restrictions are less strict."
fi

# Сборка приложения
echo ""
echo "3. Сборка приложения с расширенными исправлениями BAL..."
echo "   Building app with advanced BAL fixes..."
./gradlew assembleDebug
if [ $? -ne 0 ]; then
    echo "❌ Ошибка сборки приложения"
    echo "❌ App build failed"
    exit 1
fi

# Установка приложения
echo ""
echo "4. Установка приложения..."
echo "   Installing app..."
adb install -r app/build/outputs/apk/debug/app-debug.apk
if [ $? -ne 0 ]; then
    echo "❌ Ошибка установки приложения"
    echo "❌ App installation failed"
    exit 1
fi

# Очистка логов
echo ""
echo "5. Очистка логов системы..."
echo "   Clearing system logs..."
adb logcat -c

echo ""
echo "6. Тестирование Quick Settings Tiles..."
echo "   Testing Quick Settings Tiles..."
echo ""

# Функция для проверки BAL ошибок
check_bal_errors() {
    local tile_name=$1
    local timeout=10
    
    echo "   Проверка BAL ошибок для $tile_name..."
    echo "   Checking BAL errors for $tile_name..."
    
    # Ждем немного для обработки
    sleep 2
    
    # Проверяем логи на BAL ошибки
    BAL_ERRORS=$(adb logcat -d | grep -i "background activity launch blocked" | grep "com.example.sostaxi" | tail -5)
    
    if [ -n "$BAL_ERRORS" ]; then
        echo "   ❌ Обнаружены BAL ошибки:"
        echo "   ❌ BAL errors detected:"
        echo "$BAL_ERRORS"
        return 1
    else
        echo "   ✅ BAL ошибки отсутствуют"
        echo "   ✅ No BAL errors detected"
        return 0
    fi
}

# Функция для проверки успешного запуска
check_app_launch() {
    local timeout=5
    local count=0
    
    echo "   Проверка запуска приложения..."
    echo "   Checking app launch..."
    
    while [ $count -lt $timeout ]; do
        ACTIVITY_STATUS=$(adb shell dumpsys activity activities | grep "com.example.sostaxi/.MainActivity")
        if [ -n "$ACTIVITY_STATUS" ]; then
            echo "   ✅ Приложение успешно запущено"
            echo "   ✅ App launched successfully"
            return 0
        fi
        sleep 1
        count=$((count + 1))
    done
    
    echo "   ❌ Приложение не запустилось в течение $timeout секунд"
    echo "   ❌ App did not launch within $timeout seconds"
    return 1
}

# Тест 1: Streaming Tile
echo "=== Тест 1: Streaming Tile ==="
echo "=== Test 1: Streaming Tile ==="
echo "Инструкция: Откройте Quick Settings и нажмите на плитку 'Streaming'"
echo "Instructions: Open Quick Settings and tap the 'Streaming' tile"
echo "Нажмите Enter когда будете готовы..."
read -p "Press Enter when ready..."

echo "Мониторинг логов в течение 15 секунд..."
echo "Monitoring logs for 15 seconds..."

# Запускаем мониторинг логов в фоне
adb logcat | grep -E "(StreamingTileService|ActivityTaskManager.*background activity launch|com.example.sostaxi)" &
LOGCAT_PID=$!

sleep 15

# Останавливаем мониторинг
kill $LOGCAT_PID 2>/dev/null

check_bal_errors "Streaming Tile"
STREAMING_RESULT=$?

if [ $STREAMING_RESULT -eq 0 ]; then
    check_app_launch
    APP_LAUNCH_RESULT=$?
else
    APP_LAUNCH_RESULT=1
fi

# Закрываем приложение
echo "Закрытие приложения..."
echo "Closing app..."
adb shell am force-stop com.example.sostaxi
sleep 2

echo ""

# Тест 2: Video Segments Tile
echo "=== Тест 2: Video Segments Tile ==="
echo "=== Test 2: Video Segments Tile ==="
echo "Инструкция: Откройте Quick Settings и нажмите на плитку 'Video Segments'"
echo "Instructions: Open Quick Settings and tap the 'Video Segments' tile"
echo "Нажмите Enter когда будете готовы..."
read -p "Press Enter when ready..."

echo "Мониторинг логов в течение 15 секунд..."
echo "Monitoring logs for 15 seconds..."

# Очищаем логи
adb logcat -c

# Запускаем мониторинг логов в фоне
adb logcat | grep -E "(VideoSegmentsTileService|ActivityTaskManager.*background activity launch|com.example.sostaxi)" &
LOGCAT_PID=$!

sleep 15

# Останавливаем мониторинг
kill $LOGCAT_PID 2>/dev/null

check_bal_errors "Video Segments Tile"
VIDEO_RESULT=$?

if [ $VIDEO_RESULT -eq 0 ]; then
    check_app_launch
    VIDEO_APP_LAUNCH_RESULT=$?
else
    VIDEO_APP_LAUNCH_RESULT=1
fi

# Закрываем приложение
echo "Закрытие приложения..."
echo "Closing app..."
adb shell am force-stop com.example.sostaxi

echo ""
echo "=== Результаты тестирования ==="
echo "=== Test Results ==="
echo ""

if [ $STREAMING_RESULT -eq 0 ] && [ $APP_LAUNCH_RESULT -eq 0 ]; then
    echo "✅ Streaming Tile: BAL исправления работают, приложение запускается"
    echo "✅ Streaming Tile: BAL fixes working, app launches"
else
    echo "❌ Streaming Tile: Проблемы с BAL или запуском приложения"
    echo "❌ Streaming Tile: Issues with BAL or app launch"
fi

if [ $VIDEO_RESULT -eq 0 ] && [ $VIDEO_APP_LAUNCH_RESULT -eq 0 ]; then
    echo "✅ Video Segments Tile: BAL исправления работают, приложение запускается"
    echo "✅ Video Segments Tile: BAL fixes working, app launches"
else
    echo "❌ Video Segments Tile: Проблемы с BAL или запуском приложения"
    echo "❌ Video Segments Tile: Issues with BAL or app launch"
fi

echo ""

# Показываем любые оставшиеся BAL ошибки
echo "=== Анализ оставшихся BAL ошибок ==="
echo "=== Analysis of Remaining BAL Errors ==="
FINAL_BAL_CHECK=$(adb logcat -d | grep -i "background activity launch blocked" | grep "com.example.sostaxi" | tail -10)

if [ -n "$FINAL_BAL_CHECK" ]; then
    echo "⚠️  Обнаружены BAL ошибки в логах:"
    echo "⚠️  BAL errors found in logs:"
    echo "$FINAL_BAL_CHECK"
    echo ""
    echo "Ключевые параметры для анализа:"
    echo "Key parameters for analysis:"
    echo "- balRequireOptInByPendingIntentCreator: должно быть true"
    echo "- realCallerStartMode: проверьте значение"
    echo "- balAllowedByPiCreator: должно быть BSP.ALLOW_BAL"
else
    echo "✅ BAL ошибки отсутствуют в финальных логах"
    echo "✅ No BAL errors in final logs"
fi

echo ""
echo "=== Дополнительная информация ==="
echo "=== Additional Information ==="
echo ""
echo "Если BAL ошибки все еще возникают на Android 14+:"
echo "If BAL errors still occur on Android 14+:"
echo "1. Это может быть связано с очень строгими настройками системы"
echo "1. This may be due to very strict system settings"
echo "2. Попробуйте отключить оптимизацию батареи для приложения"
echo "2. Try disabling battery optimization for the app"
echo "3. Проверьте разрешения приложения в настройках системы"
echo "3. Check app permissions in system settings"
echo ""

# Проверяем общий статус
if [ $STREAMING_RESULT -eq 0 ] && [ $VIDEO_RESULT -eq 0 ] && [ $APP_LAUNCH_RESULT -eq 0 ] && [ $VIDEO_APP_LAUNCH_RESULT -eq 0 ]; then
    echo "🎉 ВСЕ ТЕСТЫ ПРОЙДЕНЫ УСПЕШНО!"
    echo "🎉 ALL TESTS PASSED SUCCESSFULLY!"
    exit 0
else
    echo "⚠️  НЕКОТОРЫЕ ТЕСТЫ НЕ ПРОЙДЕНЫ"
    echo "⚠️  SOME TESTS FAILED"
    exit 1
fi 