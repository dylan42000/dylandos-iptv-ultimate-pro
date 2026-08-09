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
        maven { url = uri("https://jitpack.io") }
        maven {
            url = uri("https://maven.videolan.org") // LibVLC for Android
            // Restrict to VideoLAN artifacts only — prevents Gradle from
            // routing androidx/Google deps through this unreliable host.
            content {
                includeGroup("org.videolan.android")
            }
        }
    }
}

rootProject.name = "DYLANDOS IPTV ULTIMATE"
include(":app")
