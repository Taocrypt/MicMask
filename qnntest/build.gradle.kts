import java.util.Properties
import java.io.FileInputStream

// QNN（骁龙 NPU/GPU）可行性测试包：独立包名，不影响正式版。
// 在 App 进程和 Shizuku shell 进程里分别跑 CPU / QNN-GPU / QNN-HTP(NPU) 三档，测加载与每步耗时。
plugins {
    id("com.android.application")
}

// qnntest-4：默认 ORT 1.22.0（与 qnntest-3 相同）；-PqnntestOrt=1.29.0 构建对照包（mobile-rvc 用的版本）
val qnntestOrt = (project.findProperty("qnntestOrt") as String?) ?: "1.29.0"
// qnntest-6：默认不打包 libQnn*.so（按需导入/下载）；-PqnntestBundled=true 恢复打包
// qnntest-7：-PqnntestWebgpu=true 改用通用 onnxruntime-android 1.32.0（带 WebGPU EP，不带 QNN）
val qnntestWebgpu = (project.findProperty("qnntestWebgpu") as String?) == "true"
val qnntestBundled = (project.findProperty("qnntestBundled") as String?) == "true"

android {
    namespace = "com.taocrypt.micmask.qnntest"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.taocrypt.micmask.qnntest"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "qnntest-8" + (if (qnntestWebgpu) "-webgpu" else "") + (if (qnntestOrt != "1.29.0") "-ort$qnntestOrt" else "") + (if (qnntestBundled) "-bundled" else "")
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
        jniLibs { useLegacyPackaging = true; if (!qnntestBundled) excludes += "**/libQnn*.so" }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    if (qnntestWebgpu) implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    else implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:$qnntestOrt")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
