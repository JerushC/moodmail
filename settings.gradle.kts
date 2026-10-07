pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        ivy {
            name = "ZeticMLangeAndroidGitHubRelease"
            url = uri("https://github.com/zetic-ai/ZeticMLangeAndroid/releases/download")
            patternLayout {
                artifact("[revision]/[artifact]-[revision](-[classifier]).[ext]")
            }
            metadataSources {
                gradleMetadata()
            }
            content {
                includeGroup("com.zeticai.mlange")
                includeGroup("com.zeticai.mlange.backend")
            }
        }
    }
}

rootProject.name = "MoodMail"
include(":app")
