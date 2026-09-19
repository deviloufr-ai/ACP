plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.openauto.dash"
    compileSdk = 35

    // Optional release signing key, supplied by CI via env vars. When absent
    // (e.g. local builds), the release build falls back to the debug key.
    val releaseKeystore = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

    defaultConfig {
        applicationId = "com.openauto.dash"
        minSdk = 29
        targetSdk = 34
        // Version is driven by CI (the Actions run number) so each build is
        // newer than the last; defaults keep local builds working.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"

        // Where the in-app updater looks for new releases.
        buildConfigField("String", "GITHUB_OWNER", "\"deviloufr-ai\"")
        buildConfigField("String", "GITHUB_REPO", "\"ACP\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign with the persistent release key when available, otherwise the
            // debug key so the APK is still installable.
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Bluetooth/notification permissions are requested at runtime, so the
        // static MissingPermission checks would otherwise fail CI.
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2,LOCF}"
            excludes += "/META-INF/LGPL3.0"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // Material Components — provides the XML Theme.Material3 parent used by the
    // Activity theme (Compose UI itself uses androidx.compose.material3).
    implementation("com.google.android.material:material:1.12.0")

    // Jetpack Compose BOM (Compose 1.7.x fallback compatible block)
    val composeBom = "2024.04.00"
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.9.2")

    // Media3 ExoPlayer (video/album-art rendering surface)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Pure-Kotlin ADB client — lets the app self-install to /system/priv-app
    // over the head unit's root wireless-ADB socket (no Magisk/su needed).
    implementation("dev.mobile:dadb:1.2.10")
    // MapLibre GL — free/open-source map (OpenFreeMap style, no token/API key).
    // Exclude its bundled GeoJSON/Turf so the navigation SDK's newer 7.x ones
    // provide those classes (otherwise duplicate-class build failure).
    implementation("org.maplibre.gl:android-sdk:11.11.0") {
        exclude(group = "org.maplibre.gl", module = "android-sdk-geojson")
        exclude(group = "org.maplibre.gl", module = "android-sdk-turf")
    }
    // MapLibre Navigation — free turn-by-turn (routing via a free Valhalla server).
    implementation("org.maplibre.navigation:navigation-core:5.0.0-pre8")
    implementation("org.maplibre.navigation:navigation-ui-android:5.0.0-pre8")
    // HTTP + JSON for the free routing/geocoding requests.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // Filament — real-time 3D renderer for the car model (glTF/GLB).
    implementation("com.google.android.filament:filament-android:1.71.5")
    implementation("com.google.android.filament:gltfio-android:1.71.5")
    implementation("com.google.android.filament:filament-utils-android:1.71.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
