plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.sostaxi"
    compileSdk = 35   // обновлено с 33 до 35

    defaultConfig {
        applicationId = "com.example.sostaxi"
        minSdk = 30
        targetSdk = 35   // можно обновить, если требуется, но не обязательно
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.7.0" // или последнюю стабильную версию
    }
}

dependencies {

    implementation("androidx.compose.ui:ui:1.4.3")
    implementation("androidx.compose.material3:material3:1.1.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.4.3")
    implementation(libs.androidx.activity)
    debugImplementation("androidx.compose.ui:ui-tooling:1.4.3")
    testImplementation("junit:junit:4.13.2")
    // Для instrumented-тестов (src/androidTest)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // Основные библиотеки AndroidX
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
//    implementation(libs.androidx.ui.graphics.android)
//    implementation(libs.androidx.foundation.android)
//    implementation(libs.androidx.material3.android)

    // CameraX – для работы с камерой и видео
    val camerax_version = "1.3.0"
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-video:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")


    // OkHttp – для отправки HTTP-запросов
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.11")

    // Google Play Services Location – для получения геолокации
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // Lifecycle Service для Foreground Service
    implementation("androidx.lifecycle:lifecycle-service:2.5.1")
    
    // Добавляем Guava для решения конфликта с ListenableFuture
    implementation("com.google.guava:guava:31.1-android")
}