#!/bin/bash

# Скрипт для автоматического нажатия кнопки "Старт" в приложении SOSTaxi
# Координаты кнопки: [432,1984][647,2101]
# Центр кнопки: X=540, Y=2043

echo "🎯 Нажимаю кнопку 'Старт' в приложении SOSTaxi..."

# Проверяем, что устройство подключено
if ! adb devices | grep -q "device$"; then
    echo "❌ Устройство не подключено!"
    exit 1
fi

# Проверяем, что приложение открыто
if ! adb shell dumpsys window | grep -q "com.example.sostaxi/.MainActivity"; then
    echo "📱 Запускаю приложение SOSTaxi..."
    adb shell am start -n com.example.sostaxi/.MainActivity
    sleep 3
fi

# Нажимаем кнопку "Старт"
echo "👆 Нажимаю кнопку 'Старт' (координаты: 540, 2043)..."
adb shell input tap 540 2043

echo "✅ Кнопка нажата! Проверяю логи..."

# Показываем логи
sleep 2
adb logcat -s "MainActivity:*" "RtmpClient:*" "Camera2ApiManager:*" -d | tail -10

echo "🎉 Готово!" 