#!/bin/bash

echo "🔧 Проверка исправления ошибки SOSTaxiTileService"
echo "================================================="

echo "1. Проверяем запуск основного приложения:"
adb shell am start -n com.example.sostaxi/.MainActivity
sleep 3

echo ""
echo "2. Проверяем логи на наличие ClassNotFoundException:"
adb logcat -d | grep -i "classnotfound\|SOSTaxiTileService" || echo "✅ Ошибок ClassNotFoundException не найдено"

echo ""
echo "3. Проверяем запуск тайла видеосегментов:"
adb shell am start -n com.example.sostaxi/.VideoSegmentsTileService
sleep 2

echo ""
echo "4. Проверяем запуск тайла стриминга:"
adb shell am start -n com.example.sostaxi/.StreamingTileService
sleep 2

echo ""
echo "5. Финальная проверка логов:"
adb logcat -d -t 50 | grep -E "(MainActivity|TileService|ERROR|FATAL)" | tail -10

echo ""
echo "✅ Проверка завершена!"
echo "Если нет ошибок ClassNotFoundException, то проблема решена." 