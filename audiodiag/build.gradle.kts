import java.util.Properties
import java.io.FileInputStream

// 音频注入诊断包：独立包名，不影响正式版，CI 不构建（build.yml 只跑 :app:assembleRelease）。
// 在 Shizuku shell 进程里批量测 AudioPolicy 注入轨道的各种组合，出一份可分享的中文报告。
plugins {
    id("com.android.application")
}

android {
    namespace = "com.taocrypt.micmask.audiodiag"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.taocrypt.micmask.audiodiag"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "audiodiag-2"
    }
    val signingProps = (project.findProperty("signingProps") as String?)?.let { path ->
        Properties().apply { FileInputStream(path).use { load(it) } }
    }
    signingConfigs {
        if (signingProps != null) create("taocrypt") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
            storeType = "pkcs12"
        }
    }
    buildTypes {
        if (signingProps != null) {
            getByName("debug") { signingConfig = signingConfigs.getByName("taocrypt") }
            getByName("release") { signingConfig = signingConfigs.getByName("taocrypt") }
        }
        // 大量反射调用隐藏 API，不混淆
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
