#!/bin/bash

echo "📱 Тестирование Quick Settings Tiles с исправленной логикой записи видеосегментов"
echo "==========================================================================="

# Функция для мониторинга логов
monitor_logs() {
    echo "📊 Мониторинг логов (для остановки нажмите Ctrl+C):"
    adb logcat -s MainActivity:D TelegramAuthHelper:D VideoEncoder:D AudioEncoder:D Camera2ApiManager:D | grep -E "(сегмент|segment|Encoder|Camera|готов|prepared|VideoEncoder not prepared|ошибка|error)"
}

# Функция для запуска тайла видеосегментов
start_video_segments() {
    echo "🎥 Запуск тайла записи видеосегментов..."
    adb shell am start -n com.example.sostaxi/.VideoSegmentsTileService
    echo "✅ Тайл видеосегментов запущен"
}

# Функция для остановки тайла
stop_tile() {
    echo "⏹️  Остановка тайла..."
    adb shell am force-stop com.example.sostaxi
    echo "✅ Приложение остановлено"
}

# Функция для получения статистики файлов
get_file_stats() {
    echo "📁 Статистика видеофайлов:"
    adb shell ls -la /storage/emulated/0/Android/data/com.example.sostaxi/files/ | grep -E "(segment|taxi_sos)" | head -10
}

# Меню
while true; do
    echo ""
    echo "🔧 Выберите действие:"
    echo "1. Запустить запись видеосегментов"
    echo "2. Остановить приложение"
    echo "3. Мониторинг логов"
    echo "4. Статистика файлов"
    echo "5. Выход"
    echo ""
    read -p "Ваш выбор (1-5): " choice
    
    case $choice in
        1)
            start_video_segments
            ;;
        2)
            stop_tile
            ;;
        3)
            monitor_logs
            ;;
        4)
            get_file_stats
            ;;
        5)
            echo "👋 Завершение тестирования"
            exit 0
            ;;
        *)
            echo "❌ Неверный выбор, попробуйте снова"
            ;;
    esac
done 