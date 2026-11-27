// MicMask 电脑版（Windows）：Compose Desktop + Kotlin/JVM，界面用 miuix（官方支持 Desktop，与手机版同一套视觉）。
// 打包：./gradlew packageMsi packageExe（jpackage，需在 Windows 上跑；CI 见 .github/workflows/desktop.yml）。
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

val appVersion = (findProperty("appVersion") as String?)?.removePrefix("win-v")?.removePrefix("v") ?: "0.1.0-dev"
/** MSI/EXE 只认 MAJOR.MINOR.BUILD（0–255.0–255.0–65535）：取版本号的数字部分 */
val pkgVersion = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(appVersion)?.value ?: "0.1.0"

kotlin { jvmToolchain(17) }

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
    implementation(compose.desktop.currentOs)
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
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Xmx512m")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MicMask"
            packageVersion = pkgVersion
            description = "MicMask 电脑版：与手机 MicMask 互传声音"
            vendor = "Taocrypt"
            copyright = "Copyright (C) 2026 Taocrypt. GPL-3.0"
            licenseFile.set(rootDir.resolve("../LICENSE"))
            // 打进安装目录的附带文件：windows/vbcable/VBCABLE_Driver_Pack46.zip（CI 从 vb-audio.com 官方下载、校验 sha256 后原样放入）
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))
            // 首版先带全部 JDK 模块，避免漏模块运行时报错；体积优化放到后续（jlink 精简）
            includeAllModules = true
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
