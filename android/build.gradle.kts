// Top-level build file for DYLANDOS IPTV ULTIMATE Android
buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.10.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.57.2")
    }
}

plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("com.google.dagger.hilt.android") version "2.57.2" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false  // KSP for Hilt + Room
}

// ── OkHttp version lock ────────────────────────────────────────────────────
// Coil 2.7.0 was compiled against OkHttp 4.12.0. Any transitive dependency that
// pulls in OkHttp 5.x alpha causes binary-incompatible NoSuchMethodError failures
// in Coil's HttpUriFetcher, resulting in 100% image load failure across the app.
// This force-resolution pins ALL okhttp3 artifacts project-wide to 4.12.0.
subprojects {
    configurations.all {
        resolutionStrategy {
            force("com.squareup.okhttp3:okhttp:4.12.0")
            force("com.squareup.okhttp3:logging-interceptor:4.12.0")
            force("com.squareup.okhttp3:okhttp-tls:4.12.0")
            force("com.squareup.okhttp3:mockwebserver:4.12.0")
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
