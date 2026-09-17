package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable

/**
 * A launchable app installed on the device.
 *
 * [icon] is the app's launcher icon; [packageName] identifies it for launching.
 */
@Immutable
data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

/**
 * Reads the list of launchable apps from [PackageManager] and launches them.
 *
 * Enumerating other apps requires the `<queries>` element declared in the
 * manifest (Android 11+ package visibility). Apps are launched fullscreen via
 * their launch [Intent] — Android does not allow a normal app to embed another
 * app's UI inside a view, so "opening" an app hands the whole screen to it.
 */
object AppLauncher {

    /** Package-name hints used to seed the favorites rail with common car apps. */
    private val PREFERRED_HINTS = listOf(
        "maps",        // Google Maps / navigation
        "music", "spotify", "deezer", "youtube.music", // media
        "dialer", "phone", // phone
        "messaging", "messages", // messages
        "waze"
    )

    /** All launchable apps except this launcher, sorted alphabetically by label. */
    fun loadApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null // hide ourselves
                runCatching {
                    AppEntry(
                        label = resolveInfo.loadLabel(pm).toString(),
                        packageName = pkg,
                        icon = resolveInfo.loadIcon(pm)
                    )
                }.getOrNull()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Picks up to [max] favorites for the rail: preferred car apps first (in
     * hint order), then the remaining apps alphabetically to fill the slots.
     */
    fun pickFavorites(apps: List<AppEntry>, max: Int = 5): List<AppEntry> {
        val preferred = PREFERRED_HINTS.mapNotNull { hint ->
            apps.firstOrNull { it.packageName.contains(hint, ignoreCase = true) }
        }.distinctBy { it.packageName }

        val fill = apps.filter { app -> preferred.none { it.packageName == app.packageName } }
        return (preferred + fill).take(max)
    }

    /**
     * Launches [packageName]. When [adjacent] is true (the default) it opens in
     * a split-screen pane next to the current app via `LAUNCH_ADJACENT`, rather
     * than fullscreen. On devices without split-screen support the flag is
     * ignored and the app simply opens fullscreen. Returns false if the package
     * has no launch intent.
     */
    fun launch(context: Context, packageName: String, adjacent: Boolean = true): Boolean {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (adjacent) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        }
        return runCatching { context.startActivity(launchIntent); true }.getOrDefault(false)
    }
}
