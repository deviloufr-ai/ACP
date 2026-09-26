# Dashwheel — performance & driving-UX review of the core dashboard

Scope: the launcher as it runs on the head unit (Android 10, 1024×600 / 1280×720, a weak SoC,
always powered) and as a phone driving app. The earlier review (`REVUE_UI_UX.md`) covered
legibility, the drive lock, targets and day/night, and all of it has shipped. This pass looks one
level down: **what costs frames or start-up time**, and **what is still reachable or misleading
while the car moves**. Every finding was verified line by line in the source (Kotlin 2.1, Compose
BOM 2024.12.01, MapLibre 11.11).

Each item says whether it is **fixed on this branch** or left as a **follow-up**. Effort: S = an
hour or two, M = a day, L = a project.

## What was already good

- Live readings travel as `State<ObdData>` and are read where they are drawn, so an OBD sample every
  500 ms does not recompose the page (`AutomotiveDashboard.kt`, `Skins.kt`, `DriveLock.kt`).
- Tiles are keyed by what they are (kind + occurrence), so moving or resizing one keeps its state
  (`DashboardGrid.kt`).
- Ambient motion runs on one shared, frame-gated ticker that honours the Effects setting
  (`SkinKit.kt`); a blurred text glow is only drawn at full effects.
- All Bluetooth I/O is on `Dispatchers.IO`, with a sensible PID rotation and reconnect back-off.
- The drive lock has hysteresis (8 / 3 km/h, 2 s hold) and closes the launcher's dialogs when it engages.
- Heavy widgets are replaced by placeholders while arranging.

## P1 — fixed on this branch

| # | Finding | Where | Effect in the car | Fix |
|---|---|---|---|---|
| P1.1 | **3D car widget**: the 21 MB `car.glb` was read and parsed again on every visit to its page, a Filament engine was rebuilt each time, and the baked `WheelSpin` clip kept it rendering at 30 fps whenever the tile was composed, including under Settings or the app drawer and while parked. Filament also shipped native code in every OTA. | `Car3DPanel.kt`, `build.gradle.kts`, `assets/car.glb` | Seconds of blank tile after each swipe, constant GPU load competing with the map and gauges, 21 MB per update. | **Removed** (widget, model, Filament, ProGuard rules, catalogue entry, template pages, strings in 8 locales, the `glb-edit` skill folders). A saved layout that still holds the tile drops it (`DashboardStore.DROPPED_KINDS`, tested). |
| P1.2 | **Map rebuilt on every page visit.** Both pagers used the default `beyondViewportPageCount` (0): leaving the page destroyed the `MapView`, coming back re-created the GL surface and reloaded the style. | `AutomotiveDashboard.kt` pagers, `MapLibrePanel.kt` | A blank map for about a second after every swipe back to it. | `beyondViewportPageCount = 1` on both pagers; neighbouring pages stay alive. |
| P1.3 | **The whole dashboard recomposed on every frame of a swipe's afterglow.** The page-indicator fade was read in the root scope; `TopBarModel` was a plain class rebuilt each time (so the bar always recomposed); `dashBackground()` returned a fresh `drawWithCache` (gradients rebuilt); the dock divider's fraction was read at the root, so a drag recomposed everything per frame. | `AutomotiveDashboard.kt`, `TopBar.kt`, `DashComponents.kt` | Jank exactly when the pager settles, the most common gesture in the app. | `FadingPageIndicator` owns the fade in its own scope; `TopBarModel` and `ThemeState` are data classes (equal inputs skip the bar); the background modifier is remembered per palette; the pages sit in a `WeightedPane` that reads the dock fraction itself. |
| P1.4 | **Drive lock did not reach the tiles' own dialogs.** Openable at any speed: the Maintenance editor (whole-tile tap, text fields), My Car settings (text fields), the range and fuel finders, speed correction, the map's search field, the layout picker, the split-screen picker, the OBD adapter picker, the update install. | `MaintenanceTiles.kt`, `CarCareTiles.kt`, `TelemetryTiles.kt`, `MapLibrePanel.kt`, `TopBar.kt`, `AutomotiveDashboard.kt` | Text entry and multi-step dialogs while moving, exactly what the lock exists to prevent. | `LocalDriveLock` (a composition local carrying `moving` and `whenParked`) provided by the dashboard; `ParkedOnly(onDismiss)` in every such dialog closes it with the notice as soon as the car moves and refuses to open while it does; openers gated; the map search field is disabled while moving. Quick replies and dictation for phone messages stay, as on Android Auto. |
| P1.5 | **GPS speed blinked to 0.** Network (Wi-Fi / cell) fixes every 5 s carry no speed; `publishSpeed` used `speed` without checking `hasSpeed()`, so the fresh speed dropped to 0 until the next GPS fix. | `LiveFeeds.kt` | The speed HUD flashed "0" every few seconds without OBD. | A fix without a speed leaves the readout alone (`speedReading`, pure and unit-tested in `LiveFeedsTest`). |
| P1.6 | **Layout saves parsed the previous layout on the main thread** to validate it as the backup, plus a copy of the retained-tiles map, on every drop, undo, zoom and design change. | `DashboardModel.kt` | A hitch on every edit action on the head unit. | The last layout known to parse (what `load` read or `save` wrote) is kept in memory; the backup is written from it without re-parsing. |

## P2 — fixed on this branch

| # | Finding | Where | Fix |
|---|---|---|---|
| P2.1 | Drag and resize recomposed and re-laid out the tile every frame (`Modifier.offset(Dp)` computed in composition), and `canMove` ran the full collision resolver (lists, occupancy arrays) every frame. | `DashboardGrid.kt` | Offsets and size read in the layout phase (`offset {}` and a `layout` modifier); the ghost and its collision check only when the finger crosses into another cell. |
| P2.2 | Per-draw allocations in live faces at 15–30 Hz: the compass rebuilt 72 tick lines and measured 4 labels per draw and recomposed the whole card per GPS fix; the g-meter allocated lists, strokes and brushes per sample; the Radar face ticked at 30 Hz regardless of Effects and built a sweep gradient per frame; the Liquid face allocated two hundred-point paths per step; the analog gauge and the media progress bar built strokes and brushes per draw. | `DriveTiles.kt`, `WidgetSignatures.kt`, `WidgetFacesModern.kt`, `TelemetryTiles.kt`, `MediaTile.kt` | Geometry, strokes and brushes built once per size in `drawWithCache`; the compass and the footer read the location in their own scopes; the Radar follows the ambient ticker; the Liquid face reuses its two paths. |
| P2.3 | The over-limit speed drew a blurred shadow (blur = half the digit height) even with effects off. | `DriveTiles.kt` | Goes through `softTextShadow`, like every other glow. |
| P2.4 | Targets under 48 dp reachable while driving: alert chip (36), Trip / g-force / parking Reset (36), Agenda, Dial and Notifications buttons (36), weather refresh (40), audio ±/mute (44), Sound / Bluetooth pills (~32), widget-face action buttons (32–42), fuel-finder ± (44). | `TopBar.kt`, `DriveTiles.kt`, `InfoTiles.kt`, `WidgetFaces.kt`, `TelemetryTiles.kt` | `DashSize.Touch` (48 dp) and `DashSize.TouchPrimary` (56 dp) tokens in `DashTokens.kt`, applied. |
| P2.5 | A single tap anywhere on the map cleared the route and started a new one. | `MapLibrePanel.kt` | A destination is set by a long press, and never while moving. |
| P2.6 | Media artwork: a full pixel-by-pixel `Bitmap.sameAs` on the main thread on every session callback (players report state every few seconds), and covers kept at source size (often 1024², 4 MB). | `CarMediaController.kt` | A 64-sample signature decides "same cover"; covers over 512 px on a side are shrunk once. |
| P2.7 | Start-up: the phone link, the call overlay and the post-update compiler were started in `onCreate` before the first frame (preferences, package info, a network callback); no baseline profile, so a cold start ran interpreted; nothing reported "fully drawn". | `MainActivity.kt`, `AutomotiveDashboard.kt`, `build.gradle.kts` | Those three start after the first frame; `androidx.profileinstaller` plus a hand-written `baseline-prof.txt` (the launcher's own code and coroutines, Android 10 needs the installer); `ReportDrawn()` once the dashboard composes. |

## P3 — follow-ups (not changed here)

- **Background work while another app is in front.** GPS (1 Hz on the main looper) and the
  wall-clock tickers keep running because the dashboard composition stays alive. Irrelevant on a
  powered head unit; on a phone, use `repeatOnLifecycle(STARTED)` in `UseLocationFeed` and
  frame-gate `rememberWallClock` (`LiveFeeds.kt`, `SkinKit.kt`). S.
- **Theme fade.** For 400 ms every `DashColors` reader recomposes per frame and the Material colour
  scheme is rebuilt per frame; `rememberSunUp` re-keys on the raw latitude/longitude of every fix
  (`DashTheme.kt`, `MainActivity.kt`, `DayNight.kt`). One-off; round the position to 0.01° and
  remember the scheme per palette. S.
- **Compose stability.** No compiler stability configuration; `DashboardItem` and `GridPreview`
  carry no `@Immutable`. Strong skipping (Kotlin 2.1 default) covers most of it. S.
- **Text under the 14 sp floor.** TapeDeck labels at 10–12 sp (`TapeDeckSkin.kt`). S.
- **Accessibility.** Launch-bar icons unlabelled under 110 dp (`AppTiles.kt`), Canvas-only values in
  the widget dial, no progress semantics on bars. S–M.
- **Drive lock without a speed source** counts as parked (`DriveLock.kt`); by design, worth one line
  in Settings → Advanced.
- **Media artwork off the main thread.** The shrink for oversize covers still runs on the callback's
  thread (rare, a few ms); a coroutine on `Dispatchers.Default` would make it free. S.
- **Recorded baseline profile.** The hand-written profile compiles the whole app; a profile recorded
  on the head unit with a macrobenchmark would be smaller and sharper. M.

## Verification

- Unit tests: `./gradlew testDebugUnitTest :link:test` (what `lint.yml` runs). New: `LiveFeedsTest`
  (a fix without a speed leaves the readout alone; stale and missing fixes clear it) and
  `DashboardStoreTest.droppedKindsLeaveTheLayoutInsteadOfBeingRetained`.
- Lint: `./gradlew lintDebug`.
- This branch was written in an environment without access to the Android SDK or Google's Maven
  repository, so it was **not compiled here**; CI builds it on push. The changes were reviewed
  against the source twice, but a compile error in CI is possible and should be fixed there.
- On the head unit: Layout Inspector recomposition counts on `AutomotiveDashboard` during a swipe
  (expect none in the root scope after P1.3, where every fade frame recomposed it before);
  `adb shell dumpsys gfxinfo com.openauto.dash` janky-frame share before and after a swipe to the map
  page; the speed HUD holds steady when driving on GPS alone; a drag in edit mode shows the ghost
  only when the tile crosses into another cell.
