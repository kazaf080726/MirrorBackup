pluginManagement {
    repositories {
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
        maven {
            url = uri("file:///C:/local-repo")
        }
    }
}

rootProject.name = "MirrorBackup"
include(":app")
// 电脑端恢复流程模拟测试（不是 Android 模块，只在 PC 上跑）
include(":restore-harness")
