# ☕ Настройка Java для сборки проекта

## 🎯 Проблема
При сборке проекта через терминал может возникать ошибка:
```
The operation couldn't be completed. Unable to locate a Java Runtime.
```

## ✅ Решение

### Вариант 1: Использование Java из Android Studio (рекомендуется)
```bash
# Установить JAVA_HOME для текущей сессии
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Собрать проект
./gradlew clean build
```

### Вариант 2: Постоянная настройка в .zshrc
```bash
# Добавить в ~/.zshrc
echo 'export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"' >> ~/.zshrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc

# Перезагрузить настройки
source ~/.zshrc
```

### Вариант 3: Установка Oracle JDK
```bash
# Скачать с https://www.oracle.com/java/technologies/downloads/
# Или через Homebrew
brew install openjdk@17
```

## 🔍 Проверка установки
```bash
# Проверить версию Java
java -version

# Проверить JAVA_HOME
echo $JAVA_HOME
```

## 📱 Сборка проекта
После настройки Java:
```bash
# Очистить кэш и собрать
./gradlew clean build

# Или только debug версию
./gradlew assembleDebug
```

## ✅ Результат
При успешной сборке вы увидите:
```
BUILD SUCCESSFUL in XXs
100 actionable tasks: XX executed, X up-to-date
```

---

**Примечание**: Android Studio использует встроенную Java, поэтому сборка в IDE работает без дополнительных настроек. 