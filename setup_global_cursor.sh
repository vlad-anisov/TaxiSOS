#!/bin/bash

# 🌍 Скрипт установки глобальных настроек Cursor
# Автоматически включает все источники контекста для любого проекта

echo "🚀 Установка глобальных настроек Cursor..."
echo "🚀 Setting up global Cursor settings..."

# Проверяем что Cursor установлен
CURSOR_USER_DIR="$HOME/Library/Application Support/Cursor/User"

if [ ! -d "$CURSOR_USER_DIR" ]; then
    echo "❌ Cursor не найден! Установите Cursor сначала."
    echo "❌ Cursor not found! Please install Cursor first."
    exit 1
fi

echo "✅ Cursor найден в: $CURSOR_USER_DIR"
echo "✅ Cursor found at: $CURSOR_USER_DIR"

# Создаем резервную копию текущих настроек
SETTINGS_FILE="$CURSOR_USER_DIR/settings.json"
BACKUP_FILE="$CURSOR_USER_DIR/settings.json.backup.$(date +%Y%m%d_%H%M%S)"

if [ -f "$SETTINGS_FILE" ]; then
    echo "💾 Создаю резервную копию: $BACKUP_FILE"
    echo "💾 Creating backup: $BACKUP_FILE"
    cp "$SETTINGS_FILE" "$BACKUP_FILE"
fi

# Применяем новые настройки
echo "🔧 Применяю глобальные настройки..."
echo "🔧 Applying global settings..."

cp "cursor_global_settings.json" "$SETTINGS_FILE"

if [ $? -eq 0 ]; then
    echo "✅ Глобальные настройки успешно установлены!"
    echo "✅ Global settings installed successfully!"
    echo ""
    echo "📋 Что настроено автоматически:"
    echo "📋 What's configured automatically:"
    echo "   🌐 Web контекст (поиск в интернете)"
    echo "   📁 Codebase контекст (анализ кода)"
    echo "   💻 Terminal контекст (доступ к терминалу)"
    echo "   ⚠️  Problems контекст (ошибки и предупреждения)"
    echo "   💾 Сохранение контекста между сессиями"
    echo "   🧠 Память AI включена"
    echo "   💡 Автодополнение для всех языков"
    echo "   ⚡ YOLO Mode включен"
    echo ""
    echo "🎯 Теперь просто:"
    echo "🎯 Now simply:"
    echo "1. Закройте Cursor (Cmd + Q)"
    echo "2. Откройте Cursor заново"
    echo "3. Откройте любой проект"
    echo "4. Все настройки контекста уже включены! ✨"
    echo ""
    echo "🎉 Готово! Больше никаких ручных настроек!"
    echo "🎉 Done! No more manual setup needed!"
else
    echo "❌ Ошибка при установке настроек"
    echo "❌ Error installing settings"
    echo "💡 Попробуйте восстановить из резервной копии:"
    echo "💡 Try restoring from backup:"
    echo "   cp '$BACKUP_FILE' '$SETTINGS_FILE'"
    exit 1
fi

# Проверяем валидность JSON
if command -v python3 &> /dev/null; then
    echo "🔍 Проверяю валидность JSON..."
    echo "🔍 Validating JSON..."
    if python3 -m json.tool "$SETTINGS_FILE" > /dev/null; then
        echo "✅ JSON файл валиден"
        echo "✅ JSON file is valid"
    else
        echo "⚠️  Предупреждение: JSON может быть невалидным"
        echo "⚠️  Warning: JSON might be invalid"
    fi
fi

echo ""
echo "📖 Читайте GLOBAL_CURSOR_SETUP.md для деталей"
echo "📖 Read GLOBAL_CURSOR_SETUP.md for details" 