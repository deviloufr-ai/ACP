# Dashwheel - UX & Design Templates

## Executive Summary
Five distinct UX/design templates optimized for automotive environments (head units & smartphones). All follow safety-first principles: dark theme, high contrast, large touch targets, glanceable information.

---

## TEMPLATE 1: "DRIVER-FOCUSED MINIMALIST" 
**Philosophy:** Maximum speed info, minimal distraction, clean navigation.

### Visual Style
```kotlin
// Color Palette (Dark Theme for reduced glare)
PRIMARY_BACKGROUND = "#0F1115"      // Nearly black - reduces eye strain
SURFACE = "#1A1D24"                  // Slightly lighter containers
ACCENT_PRIMARY = "#3B82F6"           // Blue - primary actions
ACCENT_SUCCESS = "#22C55E"           // Green - safe status (OBD connected)
ACCENT_WARNING = "#F59E0B"           // Amber - warnings (>110 km/h, low coolant)
ACCENT_DANGER = "#EF4444"            // Red - critical alerts
TEXT_PRIMARY = "#FFFFFF"             // Pure white for primary text
TEXT_SECONDARY = "#9CA3AF"           // Gray for secondary info

// Typography
DISPLAY_SPEED = TextSpec(fontSize = 72.sp, typeface = Roboto.Bold)  // Speedometer
DISPLAY_RPM = TextSpec(fontSize = 56.sp, typeface = Roboto.Medium)   // RPM
LABEL = TextSpec(fontSize = 24.sp, typeface = Roboto.Regular)        // Labels
HINT = TextSpec(fontSize = 18.sp, typeface = Roboto.Light)           // Secondary info

// Touch Targets (Safety First!)
BUTTON_SIZE_LARGE = 64.dp   // Play/Pause, navigation
BUTTON_SIZE_MEDIUM = 52.dp  // Previous/Next
BUTTON_SIZE_SMALL = 40.dp   // Status indicators
MIN_TOUCH_TARGET = 48.dp    // Android accessibility minimum
```

### Layout (Landscape - Head Unit)
```
┌─────────────────────────────────────────────────────────┐
│  TOP STATUS BAR [6dp]                                    │
│  📶 OBD: Connected | ⚡ Battery: 85% | 🔥 Coolant: 92°C │
├───────────┬─────────────────────────────────────────────┤
│           │                                             │
│   NAV     │               MEDIA CENTER                  │
│   PANEL   │                                             │
│  (50%)    │          Album Art / Player                 │
│           │                                             │
│  Google   │          ┌───────────────────────────┐      │
│   Maps    │          │ ▶ SKIP NEXT              │      │
│   View    │          │                          │      │
│           │          │ 🎵 Song Title            │      │
│  Floating │          │ 👤 Artist Name          │      │
│   HUD     │          └───────────────────────────┘      │
│ Overlay:  │                                             │
│ • Speed: 87 km/h                                      │
│ • RPM: 1,850                                          │
│ • OBD Status Icon                                     │
├───────────┴─────────────────────────────────────────────┤
│                   [Expand Maps]                          │
└─────────────────────────────────────────────────────────┘
```

### Key UI Components

**Speedometer (Primary Gauge)**
```kotlin
@Composable
fun SpeedometerDisplay(speed: Int, unit: String = "km/h") {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = speed.toString(),
            style = DisplaySpeedSpec,
            color = if (speed > 110) Color.Red else Color.White
        )
        Text("km/h", style = Label, color = Color.Gray)
        
        // Warning indicator
        if (speed > 110) {
            Text(
                "⚠️ HIGH SPEED", 
                style = Hint, 
                color = Color.Red,
                fontSize = 20.sp
            )
        }
    }
}
```

**RPM Gauge**
```kotlin
@Composable
fun RPMDisplay(rpm: Int) {
    Text(
        text = rpm.toString(),
        style = DisplayRpmSpec,
        color = if (rpm > 6500) Color.Red else Color.White
    )
    // Redline warning at 6500 RPM
}
```

---

## TEMPLATE 2: "SPORT MODE" 
**Philosophy:** Aggressive styling for performance vehicles, animated gauges, real-time telemetry.

### Visual Style
```kotlin
// Sport Mode Colors (Higher contrast, more aggressive)
PRIMARY_BACKGROUND = "#000000"           // Pure black - maximum contrast
SURFACE = "#111111"                      // Dark gray containers
ACCENT_PRIMARY = "#FF0000"               // Red - sport accent
ACCENT_PRIMARY_ALT = "#FF4444"           // Lighter red for hierarchy
NEON_ACCENT = "#00FFCC"                  // Cyan for digital elements

// Animated Elements
RPM_BAR_COLOR = listOf(
    "#3B82F6",  // < 2000 RPM - Blue (safe)
    "#A3E635",  // 2000-4000 RPM - Green (optimal)
    "#FFA726",  // 4000-5500 RPM - Orange (shift soon)
    "#EF4444"   // > 5500 RPM - Red (redline danger!)
)

// Typography - Monospace for telemetry feel
TELEMETER_FONT = TextSpec(
    fontSize = 48.sp, 
    typeface = monospacedFont(),
    letterSpacing = 2.sp
)
```

### Sport Mode Layout Features

**Animated RPM Bar (Left Side)**
```
┌──────────────────┐
│   [RPM BAR]      │  ← Animated horizontal bar
│■■■■■▒▒▒▒▒▒▒▒▒▒   │  Changes color based on RPM zone
│                  │
│  SPEED: 145 km/h │  ← Digital readout
└──────────────────┘

**Gear Indicator (Digital)**
```kotlin
@Composable
fun GearIndicator(gear: Int) {
    val gearColors = listOf(
        "#8B5CF6", // D - Drive
        "#EC4899", // R - Reverse  
        "#3B82F6"  // N - Neutral
    )
    
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(gearColors[gear % gearColors.size], 
                       MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Text("$gear", style = SportFont, fontSize = 64.sp)
    }
}
```

**Real-time Telemetry Ticker (Bottom)**
```kotlin
@Composable
fun TelemetryTicker(obdManager: ObdBluetoothManager) {
    val speed = obdManager.observeSpeed()
    val rpm = obdManager.observeRpm()
    val temp = obdManager.observeTemperature()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TelemetryItem("SPD", speed, unit = "km/h")
        TelemetryItem("RPM", rpm, suffix = "/min")
        TelemetryItem("°C", temp, icon = Icons.Filled.Thermostat)
    }
}
```

---

## TEMPLATE 3: "SMARTPHONE ADAPTIVE" 
**Philosophy:** Optimized for phone mounts in portrait/landscape. Auto-detects orientation and reflows content intelligently.

### Orientation Detection Logic
```kotlin
@Composable
fun ResponsiveAutomotiveLayout(
    obdManager: ObdBluetoothManager,
    mediaController: CarMediaController
) {
    val layoutSize = rememberMeasurePolicy { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val isLandscape = placeable.width > placeable.height
        
        LayoutScope(
            maxWidth = placeable.width,
            maxHeight = placeable.height,
            isLandscape = isLandscape
        )
    }

    when {
        layoutSize.isLandscape -> LandscapeLayout(
            obdManager = obdManager,
            mediaController = mediaController
        )
        
        else -> PortraitLayout(
            obdManager = obdManager,
            mediaController = mediaController
        )
    }
}
```

### Portrait Layout (Phone Mount - Vertical)
```
┌─────────────────────────────────────┐
│  STATUS BAR [8dp height]             │
│  📶 OBD: ● Connected | 🔋 82%       │
├─────────────────────────────────────┤
│                                     │
│  NAVIGATION (Top 50%)               │
│  ┌───────────────────────────────┐  │
│  │                               │  │
│  │    [Map View]                 │  │
│  │    (Google Maps intent)       │  │
│  │                               │  │
│  │  Floating HUD:                │  │
│  │  Speed: 67 km/h ⬆            │  │
│  │  RPM: 1,650                  │  │
│  └───────────────────────────────┘  │
│                                     │
├─────────────────────────────────────┤
│                                     │
│  MEDIA PLAYER (Bottom 50%)          │
│  ┌───────────────────────────────┐  │
│  │  [Album Art Placeholder]      │  │
│  │                              │  │
│  │  🎵 Highway Blues            │  │
│  │  🎤 The Midnight Riders       │  │
│  │                              │  │
│  │  ⏮ ◀ ▶️ ⏭                  │  │
│  └───────────────────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

### Landscape Layout (Horizontal Phone Mount)
```
┌─────────────────────────────────────────────────────┐
│  STATUS BAR                                         │
├──────────────┬──────────────────────────────────────┤
│              │                                      │
│   NAVIGATION │     MEDIA CENTER                     │
│   (Left 50%) │                                      │
│              │     [Album Art / Video]              │
│  Map View    │                                      │
│  + HUD       │     🎵 Title                         │
│ • Speed      │     👤 Artist                        │
│ • RPM        │                                      │
│ • Status     │     ⏮ ▶️ ⏭                          │
├──────────────┴──────────────────────────────────────┤
│              Expand Maps →                          │
└─────────────────────────────────────────────────────┘
```

### Adaptive Component Library
```kotlin
@Composable
fun AdaptiveMediaButton(
    onClick: () -> Unit, 
    minWidth: Int = if (isLandscape) 52.dp else 48.dp
) {
    Box(
        modifier = Modifier
            .size(minWidth, minWidth)
            .clickable(onClick = onClick)
    ) {
        // Icon scales with touch target size
    }
}

@Composable
fun AdaptiveTextDisplay(
    text: String, 
    maxLines: Int = if (isLandscape) 2 else 1
) {
    Text(text = text, maxLines = maxLines, overflow = Ellipsis)
}
```

---

## TEMPLATE 4: "TECH TELEMETRY DASHBOARD" 
**Philosophy:** Engineering-focused, data-dense display for enthusiasts. Multiple data streams, grid-based layout.

### Visual Style
```kotlin
// Grid-Based Layout
GRID_CELL_SIZE = 120.dp
GRID_GAP = 8.dp

// Data Stream Colors
STREAM_SPEED = Color(0xFF3B82F6)   // Blue
STREAM_RPM = Color(0xFFA3E635)     // Lime
STREAM_TEMP = Color(0xFFF59E0B)    // Amber
STREAM_ALTITUDE = Color(0xFF8B5CF6)// Purple

// Digital Font for telemetry feel
@Composable
fun TelemetryNumber(value: Int, unit: String) {
    Text(
        text = "%04d".format(value),
        style = DigitSpec(fontSize = 32.sp),
        color = Color.White
    )
}
```

### Grid Layout Structure
```
┌─────────────────────────────────────────────────────┐
│              [APP TITLE]                            │
│            [LIVE CLOCK: HH:MM:SS]                   │
├═══════════════════┬─────────────────────────────────┤
│  SPEED    (120dp) │        MEDIA PANEL             │
│  ████67   km/h    │                                 │
├═══════════════════┼─────────────────────────────────┤
│  RPM      (120dp) │  [Album Art]                   │
│  ███1,850         │  🎵 Current Track               │
├═══════════════════┼─────────────────────────────────┤
│  TEMP      (60dp) │  👤 Artist                     │
│   ██████92°C      │                                 │
├═══════════════════┼─────────────────────────────────┤
│  ALT       (60dp) │  [Controls]                    │
│   ███850ft        │  ⏮ ▶️ ⏭                       │
└═══════════════════┴─────────────────────────────────┘
```

### Data Grid Implementation
```kotlin
@Composable
fun TelemetryGrid(obdManager: ObdBluetoothManager) {
    val speed = obdManager.speed.collectAsState()
    val rpm = obdManager.rpm.collectAsState()
    val temp = obdManager.temperature.collectAsState()
    
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP)
    ) {
        item {
            TelemetryCard(
                title = "Speed",
                value = speed.value,
                unit = "km/h",
                color = STREAM_SPEED
            )
        }
        item {
            TelemetryCard(
                title = "RPM",
                value = rpm.value,
                unit = "/min",
                color = STREAM_RPM
            )
        }
        item {
            TelemetryCard(
                title = "Coolant",
                value = temp.value,
                unit = "°C",
                color = STREAM_TEMP
            )
        }
    }
}

@Composable
fun TelemetryCard(title: String, value: Int, unit: String, color: Color) {
    Card(
        elevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(GRID_GAP / 2)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MediumText, color = Color.Gray)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "%04d".format(value),
                    style = DigitSpec(fontSize = 36.sp),
                    color = color
                )
                Text(unit, style = SmallText, color = Color.Gray)
            }
        }
    }
}
```

---

## TEMPLATE 5: "MEDIA-CENTER FOCUS" 
**Philosophy:** Audio/visual media is primary. Navigation secondary but always accessible. Large album art, immersive music experience.

### Visual Style
```kotlin
// Immersive Media Theme
PRIMARY_BACKGROUND = "#121418"        // Deep black-blue
SURFACE_MEDIA = "#1E2026"             // Slightly lighter for media
ACCENT_GLOW = Color(0x333B82F6)      // Blue glow effect (20% opacity)

// Gradient Background (Subtle, non-distracting)
BACKGROUND_GRADIENT = gradient(
    0.0f to "#0F1115",
    0.7f to "#1A1D24"
)

// Media-specific typography
ARTIST_NAME = TextSpec(fontSize = 48.sp, typeface = Roboto.Light)
TRACK_TITLE = TextSpec(fontSize = 36.sp, typeface = Roboto.Medium)
ALBUM_ARTICLE = BoxContentAligned(Alignment.Center) {
    // Max size: min(300dp, screen.height * 0.45)
}
```

### Immersive Media Layout
```
┌─────────────────────────────────────────────────────┐
│  TOP BAR (20dp height - minimal)                    │
│  📶 ● OBD | 🔋 87% | 🌡️ 94°C | ⏱️ 14:32          │
├─────────────────────────────────────────────────────┤
│                                                     │
│   ┌──────────────────────────────────────────┐     │
│   │                                          │     │
│   │           [ALBUM ART]                    │     │
│   │         (Large, 240dp × 240dp)           │     │
│   │          OR VIDEO PLAYER                 │     │
│   │                                          │     │
│   │              🎵 Highway Blues            │     │
│   │              The Midnight Riders         │     │
│   │                                          │     │
│   │      ⏪ 1:23 / 4:57 [▶]                 │     │
│   └──────────────────────────────────────────┘     │
│                                                     │
│   NAVIGATION PANEL (Right/Bottom - Expandable)      │
│   ┌─────────────────────────────────────────────┐  │
│   │  [Google Maps View]                        │  │
│   │                                             │  │
│   │  Speed: 72 km/h                            │  │
│   │  RPM: 1,920                                │  │
│   │  Status: Connected                         │  │
│   └─────────────────────────────────────────────┘  │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### Media Controls (Large Touch Targets)
```kotlin
@Composable
fun ImmersiveMediaControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Previous - Medium size
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", 
                  modifier = Modifier.size(52.dp))
        }
        
        // Play/Pause - Extra Large (64dp) - primary action
        IconButton(onClick = onPlayPause) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                  contentDescription = if (isPlaying) "Play" else "Pause",
                  modifier = Modifier.size(64.dp))
        }
        
        // Next - Medium size  
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next",
                  modifier = Modifier.size(52.dp))
        }
    }
}
```

---

## COMPARISON MATRIX

| Template | Best For | Touch Targets | Data Density | Visual Style |
|----------|----------|---------------|--------------|--------------|
| **Driver-Focused** | Daily driving, safety | ⭐⭐⭐⭐⭐ (64dp primary) | Low-Medium | Clean, minimalist |
| **Sport Mode** | Performance cars, enthusiasts | ⭐⭐⭐⭐⭐ (52-64dp) | Medium-High | Animated, aggressive |
| **Smartphone Adaptive** | Phones, flexible mounting | ⭐⭐⭐⭐⭐ (adaptive) | Medium | Clean, context-aware |
| **Tech Telemetry** | Engineers, data enthusiasts | ⭐⭐⭐⭐ (48-64dp) | High | Grid, engineering |
| **Media-Center** | Music/video focus | ⭐⭐⭐⭐⭐ (52-72dp media) | Low-Medium | Immersive, artistic |

---

## RECOMMENDATION

For your project's dual use case (head unit + smartphone), I recommend starting with:

1. **Primary:** Template 3 (**Smartphone Adaptive**) - Works for both form factors
2. **Optional overlays:** Sport Mode gauges OR Media-Center immersion mode (user-selectable)

### Implementation Priority
```kotlin
// Phase 1: Core implementation
@Composable
fun AutomotiveDashboard(
    obdManager: ObdBluetoothManager,
    mediaController: CarMediaController
) {
    val layoutSize = rememberLayoutInfo() // Measure policy
    when {
        layoutSize.width > layoutSize.height -> 
            LandscapeLayout(obdManager, mediaController)
        
        else -> PortraitLayout(obdManager, mediaController)
    }
}

// Phase 2: Add user-selectable themes (preferences)
@Composable
fun DashboardWithThemeSelector(
    obdManager: ObdBluetoothManager,
    mediaController: CarMediaController,
    theme: UserTheme = remember { 
        PreferenceStore().theme.collectAsState() 
    }.value
) {
    when (theme) {
        UserTheme.DRIVER -> Template1Layout(obdManager, mediaController)
        UserTheme.SPORT -> Template2Layout(obdManager, mediaController)
        UserTheme.TECH -> Template4Layout(obdManager, mediaController)
        UserTheme.MEDIA -> Template5Layout(obdManager, mediaController)
    }
}
```

Would you like me to implement any of these templates into your actual codebase?