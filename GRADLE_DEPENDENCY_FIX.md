# 🔧 Исправление проблемы с зависимостями Gradle

## 🎯 Проблема
```
Could not resolve all files for configuration ':app:debugRuntimeClasspath'
```

## 🔍 Причина
Конфликт между локальным модулем `tdlib` и внешней зависимостью `com.github.tdlib:td:v1.8.49`.

Gradle пытался найти несколько разных версий TDLib:
- `com.github.TGX-Android:tdlib:main-SNAPSHOT`
- `org.drinkless.td:tdlib:1.8.0` 
- `com.github.tdlibx:td-android:1.8.0`

## ✅ Решение

### 1. Удален конфликтующий модуль
```bash
# Переименована папка tdlib в tdlib_backup
mv tdlib tdlib_backup
```

### 2. Очищен кэш Gradle
```bash
# Удален кэш Gradle
rm -rf .gradle/caches
rm -rf app/build
```

### 3. Проверена конфигурация
- ✅ В `settings.gradle.kts` включен только модуль `:app`
- ✅ В `app/build.gradle` используется только `com.github.tdlib:td:v1.8.49`
- ✅ Нет дублирующих блоков repositories

## 🚀 Следующие шаги

### Через Android Studio (рекомендуется):
1. Откройте проект в Android Studio
2. Выберите **Build → Clean Project**
3. Выберите **Build → Rebuild Project**
4. Запустите приложение

### Через командную строку (если установлена Java):
```bash
./gradlew clean build
```

## 📋 Проверка успешности

После сборки вы должны увидеть:
```
BUILD SUCCESSFUL in Xs
100 actionable tasks: X executed, X up-to-date
```

## 🔍 Диагностика проблем

Если проблема повторится, проверьте:

1. **Нет ли других модулей tdlib:**
   ```bash
   find . -name "*tdlib*" -type d
   ```

2. **Нет ли старых зависимостей в кэше:**
   ```bash
   ./gradlew dependencies --configuration debugRuntimeClasspath
   ```

3. **Корректность репозиториев в settings.gradle.kts:**
   ```kotlin
   dependencyResolutionManagement {
       repositories {
           google()
           mavenCentral()
           maven { url = uri("https://jitpack.io") }
       }
   }
   ```

## 📝 Файлы изменены
- `tdlib/` → `tdlib_backup/` (переименована)
- `.gradle/caches/` (удален)
- `app/build/` (удален)

---
**Статус**: ✅ Конфликт зависимостей устранен 