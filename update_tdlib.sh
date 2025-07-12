#!/bin/bash

# Скрипт для обновления TDLib в проекте SOSTaxi
# Использование: ./update_tdlib.sh

echo "🔄 Обновление TDLib для проекта SOSTaxi..."

# Создаем временную директорию
TEMP_DIR="temp_tdlib"
mkdir -p $TEMP_DIR
cd $TEMP_DIR

echo "📥 Скачивание последней версии TDLib..."
# Скачиваем последнюю версию TDLib
curl -L -o tdlib.zip "https://core.telegram.org/tdlib/tdlib.zip"

if [ $? -eq 0 ]; then
    echo "✅ TDLib скачан успешно"
    
    # Распаковываем архив
    unzip -q tdlib.zip
    
    echo "📁 Содержимое архива:"
    ls -la
    
    echo "ℹ️  Для завершения обновления:"
    echo "1. Скопируйте файлы .so из архива в app/src/main/jniLibs/"
    echo "2. Обновите зависимости в build.gradle"
    echo "3. Проверьте совместимость API"
    
else
    echo "❌ Ошибка при скачивании TDLib"
    exit 1
fi

cd ..

echo "🎉 Скрипт завершен. Проверьте папку $TEMP_DIR"
echo "📖 Подробные инструкции в файле UPDATE_APP_TO_LOGIN_FIX.md" 