// MicMask 电脑版（Windows）：Compose Desktop + Kotlin/JVM，界面用 miuix（官方支持 Desktop，与手机版同一套视觉）。
// 打包：./gradlew packageReleaseMsi packageReleaseExe（ProGuard + 精简 JDK；jpackage 需在 Windows 上跑；CI 见 .github/workflows/desktop.yml）。
// 自测：装好后 MicMask.exe --smoke（或 ./gradlew runReleaseDistributable --args=--smoke）。
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

val appVersion = (findProperty("appVersion") as String?)?.removePrefix("win-v")?.removePrefix("v") ?: "0.1.0-dev"
/** MSI/EXE 只认 MAJOR.MINOR.BUILD（0–255.0–255.0–65535）：取版本号的数字部分 */
val pkgVersion = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(appVersion)?.value ?: "0.1.0"

// JDK 21：miuix 0.9.x 的 class 文件是 Java 21（版本 65），用 17 运行会在加载主题时 UnsupportedClassVersionError。
// 打包用的 jlink/jpackage 也取这个工具链的 JDK（CI 里 setup-java 21）。
kotlin { jvmToolchain(21) }

// ---------------------------------------------------------------- 与手机端共享的传音协议源码
// 这些文件是纯 Kotlin（不依赖 Android），原件在 app/src/main/kotlin/com/taocrypt/micmask/link/，构建时原样复制过来编译；
// 改协议只改 app/ 里的原件。后续 KMP 改造时搬进 shared 模块。
val linkShared = listOf("MicYouWire.kt", "LinkNet.kt", "MmLinkCrypto.kt", "MmLinkProto.kt", "MmLinkOpus.kt", "MmLinkNet.kt")
val linkSharedDir = layout.buildDirectory.dir("generated/linkShared/kotlin")
val syncLinkShared = tasks.register<Sync>("syncLinkShared") {
    from(rootDir.resolve("../app/src/main/kotlin/com/taocrypt/micmask/link")) { include(linkShared) }
    into(linkSharedDir.map { it.dir("com/taocrypt/micmask/link") })
}

// ---------------------------------------------------------------- 构建信息（版本号、Beacon 配置）
// Beacon 地址/密钥只从环境变量读（CI secrets），为空时电脑版的统计/公告/赞赏墙整体关闭；开源仓库里不放密钥。
val buildInfoDir = layout.buildDirectory.dir("generated/buildInfo/kotlin")
val genBuildInfo = tasks.register("genBuildInfo") {
    val out = buildInfoDir
    val v = appVersion
    val env = { k: String -> (System.getenv(k) ?: "").replace("\"", "") }
    val bUrl = env("BEACON_URL"); val bApp = env("BEACON_APP_ID"); val bKey = env("BEACON_KEY")
    inputs.property("v", v); inputs.property("b", "$bUrl|$bApp|${bKey.length}")
    outputs.dir(out)
    doLast {
        val f = out.get().asFile.resolve("com/taocrypt/micmask/desktop/BuildInfo.kt")
        f.parentFile.mkdirs()
        f.writeText("""
            |package com.taocrypt.micmask.desktop
            |
            |/** 构建时生成，勿手改 */
            |object BuildInfo {
            |    const val VERSION = "$v"
            |    const val BEACON_URL = "$bUrl"
            |    const val BEACON_APP_ID = "$bApp"
            |    const val BEACON_KEY = "$bKey"
            |}
            |""".trimMargin())
    }
}

sourceSets.main {
    kotlin.srcDir(linkSharedDir)
    kotlin.srcDir(buildInfoDir)
}
tasks.named("compileKotlin") { dependsOn(syncLinkShared, genBuildInfo) }

dependencies {
    // Skia 本地库只带一种：在 Windows 上构建（CI）固定 x64，不随 arm64 等其它平台打包；其它系统（本地开发/自测）用当前平台
    implementation(if (System.getProperty("os.name").startsWith("Windows")) "org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.12.2" else compose.desktop.currentOs)
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    // Opus（与手机端同一版本，BSD-3-Clause）
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    // mDNS 发现/广播 _micmask._tcp（Apache-2.0）
    implementation("org.jmdns:jmdns:3.6.5")
    // JSON（GitHub Release / Beacon 解析；Public Domain）
    implementation("org.json:json:20260814")
}

compose.desktop {
    application {
        mainClass = "com.taocrypt.micmask.desktop.MainKt"
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Xmx512m")
        // release 构建（packageReleaseMsi/Exe）开 ProGuard：只摇树、不混淆（日志/崩溃栈可读，反射安全），规则见 proguard-rules.pro
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(true)
            joinOutputJars.set(true)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MicMask"
            packageVersion = pkgVersion
            // MSI 元数据只用 ASCII：JDK 21 的 jpackage 用 en-us（代码页 1252）跑 WiX light，中文会报 LGHT0311 打包失败
            description = "MicMask for Windows - send and receive audio with the MicMask phone app"
            vendor = "Taocrypt"
            copyright = "Copyright (C) 2026 Taocrypt. GPL-3.0"
            licenseFile.set(rootDir.resolve("../LICENSE"))
            // 打进安装目录的附带文件：windows/vbcable/VBCABLE_Driver_Pack46.zip（CI 从 vb-audio.com 官方下载、校验 sha256 后原样放入）
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
            // jlink 只带需要的 JDK 模块（jdeps 结果 + 反射/服务加载/TLS 需要的）。改依赖或用到新 JDK API 时，
            // 先跑 ./gradlew suggestRuntimeModules，再在装好的程序上跑 MicMask.exe --smoke（CI 每次都跑）确认。
            //   java.desktop     AWT/Swing（Compose 窗口）、javax.sound（声卡）、ImageIO（图标）
            //   jdk.unsupported  sun.misc.Unsafe（coroutines、Skiko、Compose 用）
            //   jdk.crypto.ec    SunEC：HTTPS（更新检查、Beacon）的 ECDHE/X25519 握手和 ECDSA 证书；缺了 TLS 握手失败
            //   jdk.charsets     GBK/GB18030 等扩展字符集（中文 Windows 的 native.encoding、安装器输出）
            //   java.instrument / java.management  coroutines 调试探针、Compose/Skiko 可选引用，体积很小，留着防 NoClassDefFoundError
            //   java.naming / java.logging          JmDNS / slf4j 兜底（体积很小）
            //   jdk.zipfs        ZipFileSystem（VB-CABLE 包解压兜底）
            //   jdk.accessibility  Windows 读屏桥（无障碍）
            // 没带 jdk.localedata：界面文字由 Skia 排版、时间只用 LocalTime.toString()，不依赖 CLDR 本地化数据。
            modules(
                "java.desktop", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets",
                "java.instrument", "java.management", "java.naming", "java.logging",
                "jdk.zipfs", "jdk.accessibility",
            )
            windows {
                iconFile.set(layout.projectDirectory.file("packaging/micmask.ico"))
                menuGroup = "MicMask"
                shortcut = true
                dirChooser = true
                perUserInstall = false
                // 固定 upgradeUuid：新版 MSI 覆盖升级旧版，不会装出两份
                upgradeUuid = "6f1b8f3e-4a52-4c1e-9d77-3b2a7d0c5e11"
            }
        }
    }
}
