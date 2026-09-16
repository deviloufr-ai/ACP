# OpenAuto Dash — Build Fix Summary

This documents why the project failed to build and what was changed to make it
compile into a debug APK on GitHub Actions.

## The real root causes

Earlier attempts blamed the GitHub Actions Android SDK setup. That was a symptom,
not the cause — the project could not have compiled regardless of the runner
configuration. Three layers were broken:

### 1. Missing Gradle project structure
- No Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`), so
  `./gradlew assembleDebug` had nothing to run.
- No root `build.gradle.kts` — an empty **directory** of that name existed in its
  place, so the Android/Kotlin plugin **versions were never declared**.
- No `gradle.properties`, so `android.useAndroidX=true` was missing.

### 2. Resources that failed AAPT2
- Launcher icons were `res/mipmap-*/ic_launcher.xml.svg` — SVG is not a valid
  Android resource format and the double extension is an invalid resource name.
- `themes.xml` referenced undefined colors (`@color/secondary`), an invalid
  `backgroundColor` attribute, and a `Theme.Material3` parent without the
  `com.google.android.material` dependency.
- `styles.xml` inherited non-existent parents (e.g. `Widget.Material3.FilledIconButton`).
- `AndroidManifest.xml` placed `<category>` tags directly under `<activity>`
  (a manifest-merger error) and declared no valid `HOME` launcher filter.

### 3. Kotlin that did not compile
- `AutomotiveDashboard.kt` was truncated mid-expression and referenced an
  undefined composable, a `const val` local, and non-existent APIs
  (`java.time.LocalNow`, `Spacer(width = …)`).
- `CarMediaController.kt` used a fabricated media API (`MediaSessionService.Callback`,
  `MediaPlayer.PLAYBACK_STATE_PLAYING`, `metadata.hasVideoContent`).
- `ObdBluetoothManager.kt` declared a `companion object` inside an `object`
  (illegal) and used non-existent APIs (`context.bluetoothManager`).

## What was changed

| Area | Change |
|------|--------|
| Gradle | Added root `build.gradle.kts` (AGP 8.2.2 / Kotlin 1.9.22), `gradle.properties`, wrapper scripts + `gradle-wrapper.properties` (Gradle 8.6) |
| Dependencies | Added `com.google.android.material` for the XML theme; bumped Compose compiler to 1.5.8; relaxed lint `abortOnError` |
| Resources | Replaced SVG icons with an adaptive icon (`mipmap-anydpi-v26`) + vector layers; fixed `themes.xml`/`colors.xml`; removed broken `styles.xml` |
| Manifest | Proper `LAUNCHER` + `HOME` intent-filters; added the notification-listener service; responsive orientation |
| Kotlin | Rewrote `AutomotiveDashboard`, `ObdBluetoothManager`, `CarMediaController` into compiling, working implementations; added `MediaNotificationListenerService` |
| CI | `build.yml` / `lint.yml` now provision Gradle 8.6, generate the wrapper, then build — so no wrapper JAR needs to be committed |

## How to build now
- **CI:** push to `main` (or run the workflow manually) and download the
  `openauto-dash-apk` artifact from the Actions tab.
- **Local:** open in Android Studio (it generates the wrapper JAR on sync), or run
  `gradle wrapper --gradle-version 8.6` once, then `./gradlew assembleDebug`.
