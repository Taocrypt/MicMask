import java.util.Properties
import java.io.FileInputStream

// QNN（骁龙 NPU/GPU）可行性测试包：独立包名，不影响正式版。
// 在 App 进程和 Shizuku shell 进程里分别跑 CPU / QNN-GPU / QNN-HTP(NPU) 三档，测加载与每步耗时。
plugins {
    id("com.android.application")
}

android {
    namespace = "com.taocrypt.micmask.qnntest"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.taocrypt.micmask.qnntest"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "qnntest-3"
        ndk { abiFilters += listOf("arm64-v8a") }
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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // QNN 的 skel 库要以独立文件存在（DSP 侧按路径加载），必须解包到 lib 目录
        jniLibs { useLegacyPackaging = true }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.22.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
