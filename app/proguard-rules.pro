# OpenAuto Dash release keep rules.
#
# Most libraries ship their own consumer rules (Compose, MapLibre, Filament,
# OkHttp, coroutines). The entries below cover the ones that reach into native
# code or reflection and are not fully covered by consumer rules.

# Filament: JNI callbacks into Java by name.
-keep class com.google.android.filament.** { *; }
-dontwarn com.google.android.filament.**

# MapLibre GL + navigation: native bridge and Gson-mapped route models.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# dadb (pure-Kotlin ADB client used by the priv-app self-install).
-keep class dev.mobile.dadb.** { *; }
-dontwarn dev.mobile.dadb.**

# Keep the app's own JSON-facing enums by name: tile kinds and theme modes are
# persisted with Enum.name / valueOf, which R8 would otherwise rename.
-keepclassmembers enum com.openauto.dash.** { *; }

# Kotlin metadata / coroutines internals that R8 may warn about.
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
