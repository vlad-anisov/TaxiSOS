# Компиляция TDLib для Android

## 🎯 Цель
Самостоятельная компиляция актуальной версии TDLib для решения проблемы UPDATE_APP_TO_LOGIN.

## 📋 Требования

### Системные требования
- macOS, Linux или Windows с WSL2
- Минимум 8 GB RAM (рекомендуется 16 GB)
- 10+ GB свободного места на диске
- Стабильное интернет-соединение

### Необходимые инструменты
```bash
# macOS (через Homebrew)
brew install cmake ninja gperf openssl zlib

# Ubuntu/Debian
sudo apt-get update
sudo apt-get install cmake ninja-build gperf libssl-dev zlib1g-dev

# Arch Linux
sudo pacman -S cmake ninja gperf openssl zlib
```

### Android NDK
- Android Studio с установленным NDK
- Или скачать NDK отдельно: https://developer.android.com/ndk/downloads

## 🛠️ Пошаговая инструкция

### Шаг 1: Клонирование репозитория
```bash
git clone https://github.com/tdlib/td.git
cd td
```

### Шаг 2: Настройка переменных окружения
```bash
# Путь к Android NDK (замените на ваш путь)
export ANDROID_NDK_ROOT="/Users/vlad/Library/Android/sdk/ndk/25.1.8937393"

# Или для Android Studio
export ANDROID_NDK_ROOT="$HOME/Library/Android/sdk/ndk/25.1.8937393"
```

### Шаг 3: Компиляция для Android
```bash
# Переход в папку с примером для Android
cd example/android

# Запуск скрипта сборки
./build-tdlib.sh

# Или ручная сборка
mkdir -p build
cd build

# Конфигурация CMake для Android
cmake -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-21 \
      -DCMAKE_BUILD_TYPE=Release \
      -DTD_ENABLE_JNI=ON \
      ..

# Компиляция
cmake --build . --target tdjson
```

### Шаг 4: Архитектуры для сборки
Повторите сборку для всех необходимых архитектур:
- `arm64-v8a` (64-bit ARM)
- `armeabi-v7a` (32-bit ARM)
- `x86_64` (64-bit x86)
- `x86` (32-bit x86)

```bash
# Пример для armeabi-v7a
cmake -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=armeabi-v7a \
      -DANDROID_PLATFORM=android-21 \
      -DCMAKE_BUILD_TYPE=Release \
      -DTD_ENABLE_JNI=ON \
      ..
```

## 📁 Интеграция в проект

### Шаг 1: Копирование файлов
После успешной компиляции скопируйте файлы:

```bash
# Создайте папки в вашем проекте
mkdir -p app/src/main/jniLibs/arm64-v8a
mkdir -p app/src/main/jniLibs/armeabi-v7a
mkdir -p app/src/main/jniLibs/x86_64
mkdir -p app/src/main/jniLibs/x86

# Скопируйте .so файлы
cp build-arm64-v8a/libtdjson.so app/src/main/jniLibs/arm64-v8a/
cp build-armeabi-v7a/libtdjson.so app/src/main/jniLibs/armeabi-v7a/
cp build-x86_64/libtdjson.so app/src/main/jniLibs/x86_64/
cp build-x86/libtdjson.so app/src/main/jniLibs/x86/
```

### Шаг 2: Обновление build.gradle
```gradle
dependencies {
    // Удалите старую зависимость
    // implementation 'com.github.tdlibx:td:1.6.0'
    
    // Добавьте зависимость для JSON интерфейса (если нужно)
    implementation 'org.json:json:20230227'
}
```

### Шаг 3: Обновление кода
Если используете JSON интерфейс, обновите код для работы с нативными библиотеками:

```kotlin
class TelegramAuthHelper {
    companion object {
        init {
            System.loadLibrary("tdjson")
        }
    }
    
    // Используйте JSON API вместо Java API
    external fun td_json_client_create(): Long
    external fun td_json_client_send(client: Long, request: String)
    external fun td_json_client_receive(client: Long, timeout: Double): String?
    external fun td_json_client_execute(client: Long, request: String): String?
    external fun td_json_client_destroy(client: Long)
}
```

## 🔧 Альтернативные решения

### Использование Docker
```bash
# Клонируйте репозиторий с Docker-файлом для сборки
git clone https://github.com/tdlib/td.git
cd td

# Соберите Docker образ
docker build -t tdlib-android .

# Запустите контейнер и скопируйте файлы
docker run --rm -v $(pwd)/output:/output tdlib-android
```

### Использование GitHub Actions
Создайте workflow для автоматической сборки:

```yaml
name: Build TDLib
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v2
      with:
        repository: tdlib/td
    - name: Setup NDK
      uses: nttld/setup-ndk@v1
      with:
        ndk-version: r25c
    - name: Build TDLib
      run: |
        cd example/android
        ./build-tdlib.sh
    - name: Upload artifacts
      uses: actions/upload-artifact@v2
      with:
        name: tdlib-android
        path: example/android/build/
```

## ⚠️ Возможные проблемы

### Ошибка "unable to make temporary file"
```bash
# Увеличьте размер swap или освободите место на диске
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
```

### Ошибки компиляции
```bash
# Очистите кэш и пересоберите
rm -rf build
mkdir build
cd build
# Повторите команды cmake
```

### Проблемы с NDK
```bash
# Убедитесь, что используете совместимую версию NDK
export ANDROID_NDK_ROOT="/path/to/ndk/25.1.8937393"
```

## 📚 Полезные ссылки

- [Официальная документация TDLib](https://core.telegram.org/tdlib/docs/)
- [Инструкции по сборке для Android](https://github.com/tdlib/td/blob/master/example/android/README.md)
- [TDLib GitHub Issues](https://github.com/tdlib/td/issues)
- [Android NDK Downloads](https://developer.android.com/ndk/downloads)

## 🎉 После успешной сборки

1. Обновите `useTestDc = false` в TelegramAuthHelper.kt
2. Протестируйте авторизацию с реальными номерами
3. Создайте резервную копию скомпилированных файлов
4. Обновите документацию проекта

**Время сборки:** 30-60 минут в зависимости от мощности системы. 