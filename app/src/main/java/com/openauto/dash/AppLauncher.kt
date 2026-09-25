package com.openauto.dash

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.UserHandle
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
     * Calls [onChange] (on the main thread) whenever an app is installed,
     * removed or updated, so a launcher that stays up for days still lists
     * what is really there. Returns the call that stops watching.
     */
    fun watchPackages(context: Context, onChange: () -> Unit): () -> Unit {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return {}
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String, user: UserHandle) = onChange()
            override fun onPackageAdded(packageName: String, user: UserHandle) = onChange()
            override fun onPackageChanged(packageName: String, user: UserHandle) = onChange()
            override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = onChange()
            override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = onChange()
        }
        launcherApps.registerCallback(callback)
        return { launcherApps.unregisterCallback(callback) }
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

    // --- User-chosen menu favorites -----------------------------------------

    private const val PREFS = "launcher_prefs"
    private const val KEY_FAVORITES = "favorite_packages"

    /** Package names the user pinned to the menu, in order (may be empty). */
    fun favoritePackages(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FAVORITES, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun setFavoritePackages(context: Context, packages: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAVORITES, packages.joinToString(",")).apply()
    }

    /** Pins/unpins [packageName] (capped at [max]); returns the new list. */
    fun toggleFavorite(context: Context, packageName: String, max: Int = 5): List<String> {
        val current = favoritePackages(context).toMutableList()
        when {
            current.contains(packageName) -> current.remove(packageName)
            current.size < max -> current.add(packageName)
        }
        setFavoritePackages(context, current)
        return current
    }

    /** The rail's favorites: the user's chosen apps if any, else auto-picked. */
    fun favorites(context: Context, apps: List<AppEntry>, max: Int = 5): List<AppEntry> {
        val chosen = favoritePackages(context)
        if (chosen.isNotEmpty()) {
            val byPackage = apps.associateBy { it.packageName }
            val resolved = chosen.mapNotNull { byPackage[it] }
            if (resolved.isNotEmpty()) return resolved.take(max)
        }
        return pickFavorites(apps, max)
    }

    /** Launches [packageName] fullscreen. Returns false if it has no launch intent. */
    fun launch(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return runCatching { context.startActivity(launchIntent); true }.getOrDefault(false)
    }
}
