import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
    id("com.google.devtools.ksp")  // KSP replaces KAPT — ~40% faster annotation processing
}

android {
    namespace = "com.dylandos.iptv.ultimate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dylandos.iptv.ultimate"
        minSdk = 21  // Android 5.0 - Wide device compatibility
        targetSdk = 35
        // v5.2.8: World-Class Performance, Replay 7-day Upgrade, Closed Captions (all 3 engines), Touch Support
        // OTA only offers a Gist payload when its versionCode is strictly greater.
        versionCode = 101
        versionName = "5.3.0"

        // v5.1 release metadata (see RELEASE_PLAYBOOK.md):
        //   versionCode 90 / 5.1.0 — Smart EPG show-aware DVR naming, NFO sidecars,
        //   decoupled Timeshift/DVR storage, HLS.js CEA-608/708 captions, and Fire TV remote mappings.

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // OTA update check URL — GitHub Gist API endpoint (works without knowing the GitHub username).
        // To ship an update:
        //   1. Upload the new APK to Dropbox and copy the direct download link.
        //   2. Open GistPad in VS Code → edit update.json:
        //        bump "versionCode" and "version", set flavors.firestick.apkUrl + flavors.premium.apkUrl.
        //   3. The app detects versionCode > current on next launch and prompts the user.
        buildConfigField(
            "String",
            "GIST_UPDATE_URL",
            "\"https://api.github.com/gists/c3eda014bd60ef939da246f1896066e4\""
        )
        // B3: crash reporting — blank DSN (default) keeps Sentry fully disabled (no network,
        // no SDK init). Set SENTRY_DSN in ~/.gradle/gradle.properties or CI secrets to enable.
        buildConfigField(
            "String",
            "SENTRY_DSN",
            "\"${providers.gradleProperty("SENTRY_DSN").orNull ?: ""}\""
        )

        // ABI filters belong to each flavor. Defaults merge additively with
        // flavor filters and previously bundled both ARM architectures in each APK.
    }

    // ── Build Variants: Firestick vs Premium ─────────────────────────
    flavorDimensions += "deviceType"
    productFlavors {
        create("firestick") {
            dimension = "deviceType"
            applicationIdSuffix = ".firestick"
            versionNameSuffix = "-FIRESTICK"
            // Optimize for Fire TV Stick 4K (ARMv7, 2GB RAM)
            ndk {
                abiFilters.clear()
                abiFilters += "armeabi-v7a"
            }
            // Broad resource config for Fire TV (hdpi=720p, xhdpi=1080p, xxhdpi=1440p).
    // Don't restrict — let Android's runtime pick the best match.
    resourceConfigurations += listOf("en")
        }
        create("premium") {
            dimension = "deviceType"
            applicationIdSuffix = ".premium"
            versionNameSuffix = "-PREMIUM"
            // High-end Android devices (ARM64, 4GB+ RAM)
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
    }

    signingConfigs {
        create("release") {
            val localProperties = Properties().apply {
                val propertiesFile = rootProject.file("local.properties")
                if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
            }
            fun releaseSecret(name: String): String =
                providers.environmentVariable(name).orNull
                    ?: localProperties.getProperty(name)
                    ?: error("Missing $name in the environment or untracked local.properties")
            storeFile = file("dylandos-release.jks")
            storePassword = releaseSecret("DYLANDOS_RELEASE_STORE_PASSWORD")
            keyAlias = "dylandos"
            keyPassword = releaseSecret("DYLANDOS_RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            // ── LDPlayer / Emulator Compatibility ────────────────────────────
            // LDPlayer 9 is x86_64. Release flavors strip to armeabi-v7a or arm64-v8a,
            // which breaks LibVLC JNI on x86 hosts (UnsatisfiedLinkError = app won't start).
            // Override to include ALL ABIs so libvlc-all's x86_64 .so is packaged.
            ndk {
                abiFilters.clear()
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "*/armeabi-v7a/libvlc*.so",
                "*/arm64-v8a/libvlc*.so",
                "*/armeabi-v7a/libmpv.so",
                "*/arm64-v8a/libmpv.so",
                "*/armeabi-v7a/libplayer.so",
                "*/arm64-v8a/libplayer.so",
            )
            // libvlc-all may ship libc++_shared.so across ABIs — pick first.
            pickFirsts += setOf(
                "lib/armeabi-v7a/libc++_shared.so",
                "lib/arm64-v8a/libc++_shared.so",
                "lib/x86/libc++_shared.so",
                "lib/x86_64/libc++_shared.so",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    // ── Core Android ──────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // ── Jetpack Compose ──────────────────────────────────────────────
    // Compose UI 1.11 (from the 2026 BOM) raises its minSdk to 23.  Fire OS 5
    // devices are still an explicit supported target of this app (minSdk 21), so
    // keep the Firestick-compatible 1.7 Compose line until the product minSdk is
    // deliberately raised.
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ── TV/Leanback Support ──────────────────────────────────────────
    implementation("androidx.leanback:leanback:1.0.0")

    // ── SAF / DocumentFile (for OTG USB drive recording) ────────────
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ── Navigation ───────────────────────────────────────────────────
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ── Hilt Dependency Injection ────────────────────────────────────
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // ── Room Database ────────────────────────────────────────────────
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ── Paging 3 — paginated VOD grid display (OOM fix for large providers) ──
    val pagingVersion = "3.3.5"
    implementation("androidx.paging:paging-runtime-ktx:$pagingVersion")
    implementation("androidx.paging:paging-compose:$pagingVersion")

    // ── DataStore ────────────────────────────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── Palette (dynamic channel logo color theming) ─────────────────
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ── Networking ───────────────────────────────────────────────────
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    // CRITICAL: OkHttp pinned at 4.12.0 — Coil 2.7.0 was compiled against OkHttp 4.x.
    // Upgrading to OkHttp 5.x alpha causes Coil's HttpUriFetcher to throw NoSuchMethodError
    // at runtime (binary incompatibility) resulting in 100% image load failure across the app.
    // OkHttp 4.12.0 is stable, fully featured (HTTP/2, connection pools, caching) and
    // compatible with Retrofit 2.x, Coil 2.7.0, and all other dependencies.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ── Serialization ────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.code.gson:gson:2.11.0")

    // ── Coroutines ───────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // ── Image Loading ────────────────────────────────────────────────
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ── Media3 / ExoPlayer ───────────────────────────────────────────
    val media3Version = "1.5.0"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-dash:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // ── LibVLC (Live + VOD backup) + Media3 (last resort / USB timeshift) ──
    // Pre-built ARM + ARM64 native .so included — no NDK compile needed
    implementation("org.videolan.android:libvlc-all:3.6.0")
    // MPV natives are packaged from app/src/main/jniLibs (extracted from mpv-android APKs).
    // VOD/Series primary engine: MPV → LibVLC → Media3.

    // ── WorkManager ──────────────────────────────────────────────────
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // ── Logging ──────────────────────────────────────────────────────
    implementation("com.jakewharton.timber:timber:5.0.1")

    // ── Crash reporting (opt-in via SENTRY_DSN; disabled when DSN is blank) ──
    implementation("io.sentry:sentry-android:8.47.0")

    // ── Testing ──────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
