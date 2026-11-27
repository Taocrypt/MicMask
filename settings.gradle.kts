pluginManagement {
    repositories {
        // 本机访问不到 dl.google.com / mavenCentral，全部走阿里云镜像
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Alkove 端侧声纹聚焦 SDK（Apache-2.0）：只从这里取 ai.alkove，其它依赖不查这个仓库
        maven("https://maven.alkove.ai") { content { includeGroup("ai.alkove") } }
    }
}

rootProject.name = "MicMask"
include(":core")
include(":cli")
include(":app")
include(":beacon")
include(":qnntest")
include(":audiodiag")
