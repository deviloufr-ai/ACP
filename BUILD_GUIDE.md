# OpenAuto Dash - Build Instructions

## Quick Start (Manual Build)

### Prerequisites
1. Install Android Studio
2. Open project and let Gradle sync
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. Find output at: `app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions Automated Build

### Automatic Trigger
Every push to the `main` branch automatically triggers a build in GitHub Actions.

1. **Commit your code** (already done!)
2. **Go to GitHub**: https://github.com/deviloufr-ai/ACP
3. **Navigate to Actions tab**
4. **Click "Run workflow"** or wait for automatic trigger
5. **Wait 3-8 minutes** for build to complete
6. **Download APK** from Actions → Your Workflow → Artifacts

### Manual Trigger (if needed)
1. Go to: https://github.com/deviloufr-ai/ACP/actions
2. Click "Run workflow" button
3. Select `main` branch and click "Run workflow"

## After Downloading APK

1. Transfer APK to your Android device (phone or car head unit)
2. Enable "Install from unknown sources" in device settings
3. Open file manager, navigate to APK location
4. Tap APK to install
5. Launch app via home screen or app drawer

## Testing Checklist

### Auto-Launch Feature
- ✅ App should appear on device home screen (launcher icon)
- ✅ Clicking icon launches app immediately

### OBD-II Telemetry
- ✅ Connect Bluetooth ELM327 adapter to phone/car head unit
- ✅ App automatically detects connection
- ✅ Real-time speed, RPM, coolant temperature displayed

### Media Integration
- ✅ System audio plays music/podcast
- ✅ Album art displays in media panel
- ✅ Play/Pause/Next/Previous controls work

### Responsive UI
- ✅ Landscape mode: 50/50 left-right split (maps + media)
- ✅ Portrait mode: 50/50 top-bottom split

---

**Expected APK Size:** ~15-25 MB  
**Build Time on GitHub Actions:** ~5 minutes first build, ~3 minutes subsequent builds
