# ── DYLANDOS IPTV ULTIMATE — ProGuard / R8 Rules ───────────────────
# v5.0.0 — FULL_MODE_COMPAT: all keep rules required by R8 full mode.
#
# NOTICE: `android:enableR8.fullMode=true` is set in gradle.properties.
# R8 full mode is more aggressive than ProGuard's default — it strips
# classes/methods it deems "unused" even when they're loaded reflectively
# by Dagger/Hilt, ServiceLoader, Navigation, or JNI. Every rule below
# is grounded in a real runtime crash we have diagnosed.
# ──────────────────────────────────────────────────────────────────────

# ══════════════════════════════════════════════════════════════════════
# HILT / DAGGER — CRITICAL: startup crash if stripped
# ══════════════════════════════════════════════════════════════════════
# Hilt generates classes in the app package (not dagger.*):
#   - com.dylandos.iptv.ultimate.DylandosApp_HiltComponents*
#   - com.dylandos.iptv.ultimate.ui.MainActivity_HiltComponents*
#   - com.dylandos.iptv.ultimate.Hilt_*  (Application/Activity base classes)
#   - com.dylandos.iptv.ultimate.*_HiltModules (ViewModel modules)
#   - com.dylandos.iptv.ultimate.*_HiltModules_KeyModule
# With R8 full mode, these are treated as "unused" and stripped unless
# explicitly kept. Without them: ClassNotFoundException on cold start.
-keep class com.dylandos.iptv.ultimate.** { *; }

# Hilt internal framework classes (dagger.hilt.*)
-keep class dagger.hilt.** { *; }
-keep class dagger.hilt.android.internal.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-dontwarn dagger.hilt.**
-dontwarn hilt_aggregated_deps.**

# Dagger framework
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.**

# Hilt-generated component holders (package-level, often missed)
-keep class *._HiltComponents { *; }
-keep class *._HiltComponents$* { *; }
-keep class *.Hilt_* { *; }

# ══════════════════════════════════════════════════════════════════════
# COMPOSE — needed for navigation + runtime
# ══════════════════════════════════════════════════════════════════════
-keep class androidx.compose.** { *; }
-keep interface androidx.compose.** { *; }
-dontwarn androidx.compose.**
# Navigation Compose — reflective route/serializer lookups
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# ══════════════════════════════════════════════════════════════════════
# ANDROIDX / MATERIAL3
# ══════════════════════════════════════════════════════════════════════
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# ══════════════════════════════════════════════════════════════════════
# KOTLIN METADATA & SERIALIZATION
# ══════════════════════════════════════════════════════════════════════
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.serialization.* { *; }
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions

# Keep @Serializable data classes (kotlinx.serialization)
-keepclassmembers class com.dylandos.iptv.ultimate.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.dylandos.iptv.ultimate.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dylandos.iptv.ultimate.data.model.**$$serializer { *; }
-keepclassmembers class com.dylandos.iptv.ultimate.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.dylandos.iptv.ultimate.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ══════════════════════════════════════════════════════════════════════
# ROOM DATABASE
# ══════════════════════════════════════════════════════════════════════
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keep @androidx.room.Database class *
-dontwarn androidx.room.**

# ══════════════════════════════════════════════════════════════════════
# RETROFIT & OKHTTP
# ══════════════════════════════════════════════════════════════════════
-keepattributes Signature, Exceptions
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**

# ══════════════════════════════════════════════════════════════════════
# GSON — data model deserialization
# ══════════════════════════════════════════════════════════════════════
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Keep ALL network response models (Gson-reflective)
-keep class com.dylandos.iptv.ultimate.data.remote.dto.** { *; }

# ══════════════════════════════════════════════════════════════════════
# MEDIA3 / EXOPLAYER
# ══════════════════════════════════════════════════════════════════════
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ══════════════════════════════════════════════════════════════════════
# LIBVLC (JNI bridge — native C calls Java by exact name at runtime)
# ══════════════════════════════════════════════════════════════════════
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# ══════════════════════════════════════════════════════════════════════
# MPV JNI BINDINGS (libmpv / libplayer)
# ══════════════════════════════════════════════════════════════════════
-keep class is.xyz.mpv.** { *; }
-keepclassmembers class is.xyz.mpv.MPVLib { *; }
-keep class is.xyz.mpv.MPVLib$* { *; }
-dontwarn is.xyz.mpv.**

# ══════════════════════════════════════════════════════════════════════
# COIL 2.x — IMAGE LOADER (ServiceLoader discovery)
# ══════════════════════════════════════════════════════════════════════
-keep class io.coil.kt.** { *; }
-keep interface io.coil.kt.** { *; }
-dontwarn io.coil.kt.**
-keep class coil.** { *; }
-keep interface coil.** { *; }
-dontwarn coil.**
-keepnames class coil.decode.* { *; }
-keepnames class coil.fetch.* { *; }
-keepnames class coil.intercept.* { *; }
-keepnames class coil.map.* { *; }
# ServiceLoader META-INF entries — CRITICAL: without these Coil's
# Fetcher/Decoder factories are never registered and ALL images fail.
-keepnames class coil.decode.Decoder$Factory { *; }
-keepnames class coil.fetch.Fetcher$Factory { *; }
-keepnames class coil.intercept.Interceptor { *; }
-keepnames class coil.map.Mapper { *; }

# ══════════════════════════════════════════════════════════════════════
# COROUTINES — android-specific dispatcher
# ══════════════════════════════════════════════════════════════════════
-keep class kotlinx.coroutines.android.** { *; }
-dontwarn kotlinx.coroutines.**

# ══════════════════════════════════════════════════════════════════════
# APPLICATION MODELS (data classes, entities)
# ══════════════════════════════════════════════════════════════════════
-keep class com.dylandos.iptv.ultimate.data.model.** { *; }
-keep class com.dylandos.iptv.ultimate.data.db.entity.** { *; }

# ══════════════════════════════════════════════════════════════════════
# SENTRY CRASH REPORTING (optional — kept for when SENTRY_DSN is set)
# ══════════════════════════════════════════════════════════════════════
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# ══════════════════════════════════════════════════════════════════════
# PARCELABLE & SERIALIZABLE (reflective Android serialization)
# ══════════════════════════════════════════════════════════════════════
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ══════════════════════════════════════════════════════════════════════
# COMPOSE VIEWMODEL — hiltViewModel() reflective lookup
# ══════════════════════════════════════════════════════════════════════
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# ══════════════════════════════════════════════════════════════════════
# REMOVE LOGGING IN RELEASE (safe — only removes calls, not classes)
# ══════════════════════════════════════════════════════════════════════
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}
-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
}
