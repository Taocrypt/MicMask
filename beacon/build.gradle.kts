// Beacon 客户端 SDK（MIT，见 LICENSE），复制自 Taocrypt/beacon sdk/android/beacon。
// 按 MicMask 的 AGP 9（内置 Kotlin）/ compileSdk 调整，去掉了发布配置。
// 本副本改动：trackSync（崩溃时同步写入本地队列）、反馈错误带 HTTP 状态码。
plugins {
    id("com.android.library")
}

android {
    namespace = "com.taocrypt.beacon"
    compileSdk = 37
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// 零第三方依赖：只用 Android 自带的 HttpURLConnection、org.json、SharedPreferences
dependencies {}
