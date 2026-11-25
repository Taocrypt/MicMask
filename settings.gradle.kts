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
    }
}

rootProject.name = "MicMask"
include(":core")
include(":cli")
include(":app")
include(":beacon")
include(":qnntest")
include(":audiodiag")
