import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.taocrypt.micmask"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.taocrypt.micmask"
        minSdk = 26
        targetSdk = 34
        versionCode = 158
        versionName = "2.1.1-alpha.8"
        // Beacon 后台（公告 / 推荐位 / 匿名统计 / 反馈）：CI 从 Secrets 注入，源码与本地构建为空 → SDK 关闭
        fun env(k: String) = (System.getenv(k) ?: "").replace("\\", "").replace("\"", "")
        buildConfigField("String", "BEACON_URL", "\"${env("BEACON_URL")}\"")
        buildConfigField("String", "BEACON_APP_ID", "\"${env("BEACON_APP_ID")}\"")
        buildConfigField("String", "BEACON_KEY", "\"${env("BEACON_KEY")}\"")
        ndk {
            // 只出 arm64-v8a（ONNX Runtime 与原生声码器 libmicmask_vocoder.so）
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // libc++ 静态链进 libmicmask_vocoder.so，不额外带 libc++_shared.so
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    // 原生 NEON fp16 声码器（app/src/main/cpp，内核移植自 nasuiyile/mobile-rvc，MIT）
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // 签名：读 -PsigningProps=xxx.properties（storeFile/storePassword/keyAlias/keyPassword）
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

    lint {
        // CI 发版不因 lint 中断
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
        // 调试通路（AUTORUN 自动跑）依赖 BuildConfig.DEBUG
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
        jniLibs {
            // 全量包体积已经很大，原生库保持压缩（解包到 lib 目录，不影响加载）
            useLegacyPackaging = true
        }
    }

    // 全量包：把 model_assets/{models,tts} 一起打进 APK，用户装完首次启动自动导入。
    // -PbundleModels=true   → 全量包（约 950 MB，懒人一键）
    // 不加参数              → 轻量包（约 63 MB，开发调试用，模型自己 adb push）
    val bundleModels = (project.findProperty("bundleModels") as String?)?.toBoolean() ?: false
    androidResources {
        // 模型是已压缩过的二进制，再压一遍纯属浪费时间，还会让 openFd() 拿不到长度
        noCompress += listOf("onnx", "bin", "data", "refbin")
    }
    if (bundleModels) {
        sourceSets["main"].assets.srcDir("${rootProject.projectDir}/model_assets")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":beacon"))

    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    // 录音 / 合成两页要用到 Mic、Stop、GraphicEq 这些不在 icons-core 里的图标
    implementation("androidx.compose.material:material-icons-extended")

    // Shizuku：以 shell 身份跑「麦克风注入」服务（AudioPolicy MIX_ROLE_INJECTOR）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ONNX Runtime Android（带 QNN EP 的版本，只有 arm64-v8a）。
    // 高通 QNN 运行库（com.qualcomm.qti:qnn-runtime）不随 App 分发：其许可不允许单独分发，且与 GPL 合并分发有冲突；
    // 用户需要 NPU 时在 App 内同意许可后从官方 Maven 仓库按需下载（QnnPack.kt）。
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0") { exclude(group = "com.qualcomm.qti") }

    // 软件回声消除（WebRTC AEC3，可选功能默认关）：Kotlin/JNI 绑定，钉死非滚动版本。
    // 注意 README 写的 cn.enaium.webrtc:webrtc-aec3-kmp 坐标不存在；AAR 带 4 个 ABI 的 .so，上面 abiFilters 只留 arm64-v8a，
    // 发布的 .so 带调试段（约 12 MB），靠 CI 装了 NDK 时 AGP 的 strip 降到约 1.5 MB。许可见 NOTICE.md
    implementation("cn.enaium.webrtc.aec3:webrtc-aec3-kmp-android:1.0.3")

    // 声纹聚焦（Alkove Focus，可选功能默认关，Apache-2.0）：钉 0.5.0。AAR 带 arm64-v8a / x86_64 两个 .so，abiFilters 只留 arm64-v8a。
    // 模型（.alk）不打包，运行时按 AlkoveStore 里钉的 revision 下载；升级 SDK 时同步更新 AlkoveStore.MODELS。许可见 NOTICE.md
    implementation("ai.alkove:alkove:0.5.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// 使用教程：构建时把 docs/manual.md 复制成生成的 assets/manual.md（只有一份源文件，App 内置副本永远与仓库一致）
abstract class CopyManual : DefaultTask() {
    @get:InputFile abstract val src: RegularFileProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty
    @TaskAction fun run() {
        val d = outDir.get().asFile
        d.mkdirs()
        src.get().asFile.copyTo(File(d, "manual.md"), overwrite = true)
    }
}
val copyManual = tasks.register<CopyManual>("copyManual") {
    src.set(rootProject.layout.projectDirectory.file("docs/manual.md"))
}
androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(copyManual, CopyManual::outDir) }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
