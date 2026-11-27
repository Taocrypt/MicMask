// MicMask 电脑版（Windows）—— 独立 Gradle 构建，不参与安卓根工程（根 settings.gradle.kts 不 include 它），
// 所以安卓 build.yml 与 bt 编译不受影响。传音协议的纯 Kotlin 源码从 ../app 复制共享，见 build.gradle.kts 的 syncLinkShared。
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "MicMaskDesktop"
