#!/bin/bash

# Скрипт для открытия проекта SOSTaxi в Cursor с автоматическими настройками
# Script to open SOSTaxi project in Cursor with automatic settings

echo "🚀 Запускаю проект SOSTaxi в Cursor..."
echo "🚀 Starting SOSTaxi project in Cursor..."

# Переходим в директорию проекта
PROJECT_DIR="/Users/vlad/AndroidStudioProjects/SOSTaxi"
cd "$PROJECT_DIR" || exit 1

# Проверяем наличие Cursor
if ! command -v cursor &> /dev/null; then
    echo "❌ Cursor не найден. Устанавливаем..."
    echo "❌ Cursor not found. Installing..."
    
    # Попытка найти Cursor в стандартных местах
    if [ -f "/Applications/Cursor.app/Contents/Resources/app/bin/cursor" ]; then
        CURSOR_PATH="/Applications/Cursor.app/Contents/Resources/app/bin/cursor"
    elif [ -f "/usr/local/bin/cursor" ]; then
        CURSOR_PATH="/usr/local/bin/cursor"
    else
        echo "❌ Cursor не найден. Установите Cursor и добавьте его в PATH"
        echo "❌ Cursor not found. Please install Cursor and add it to PATH"
        exit 1
    fi
else
    CURSOR_PATH="cursor"
fi

echo "✅ Cursor найден: $CURSOR_PATH"
echo "✅ Cursor found: $CURSOR_PATH"

# Создаем .vscode директорию если её нет
if [ ! -d ".vscode" ]; then
    mkdir -p .vscode
    echo "📁 Создана директория .vscode"
    echo "📁 Created .vscode directory"
fi

# Проверяем настройки
if [ -f ".vscode/settings.json" ]; then
    echo "✅ Настройки workspace найдены"
    echo "✅ Workspace settings found"
else
    echo "⚠️  Настройки workspace не найдены"
    echo "⚠️  Workspace settings not found"
fi

# Экспортируем переменные из .cursorrc если он существует
if [ -f ".cursorrc" ]; then
    echo "📝 Загружаю настройки из .cursorrc"
    echo "📝 Loading settings from .cursorrc"
    source .cursorrc
fi

# Открываем проект через workspace файл если он существует
if [ -f "SOSTaxi.code-workspace" ]; then
    echo "🎯 Открываю workspace: SOSTaxi.code-workspace"
    echo "🎯 Opening workspace: SOSTaxi.code-workspace"
    "$CURSOR_PATH" SOSTaxi.code-workspace
else
    echo "📂 Открываю папку проекта"
    echo "📂 Opening project folder"
    "$CURSOR_PATH" .
fi

echo "✨ Проект открыт! Контекст AI должен автоматически настроиться."
echo "✨ Project opened! AI context should be automatically configured."
echo ""
echo "📋 Автоматически включены:"
echo "📋 Automatically enabled:"
echo "   🌐 Web контекст"
echo "   📁 Codebase контекст"
echo "   💻 Terminal контекст" 
echo "   ⚠️  Problems контекст"
echo ""
echo "🎉 Готово! Теперь контекст будет сохраняться между сессиями."
echo "🎉 Done! Context will now persist between sessions." 