package com.openauto.dash

import android.content.Context

/**
 * Self-installs the app into `/system/priv-app` on a **rooted** device, so it
 * runs as a privileged system app (which unlocks the embedded Google Maps panel
 * — see [MapsPanel]). Uses `su`; the user grants root via Magisk.
 *
 * Does NOT reboot — the caller tells the user to reboot for it to take effect.
 * Writing to `/system` can fail (or, with enforcing dm-verity, risk a boot loop),
 * so this is a power-user action guarded by a confirmation in the UI.
 */
object SystemInstaller {

    private const val DIR = "/system/priv-app/OpenAutoDash"
    private const val DEST = "$DIR/OpenAutoDash.apk"

    /** True if a root shell (`su`) is available and granted. */
    fun isRootAvailable(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        process.waitFor() == 0
    }.getOrDefault(false)

    /**
     * Copies this app's APK into `/system/priv-app` as root. Returns success, or
     * a failure whose message explains what went wrong (shown to the user).
     */
    fun installAsSystemApp(context: Context): Result<Unit> = runCatching {
        val apk = context.applicationInfo.sourceDir
        val script = buildString {
            append("mount -o remount,rw / 2>/dev/null; ")
            append("mount -o remount,rw /system 2>/dev/null; ")
            append("mkdir -p $DIR && ")
            append("cp '$apk' '$DEST' && ")
            append("chmod 755 $DIR && chmod 644 '$DEST' && ")
            append("chcon u:object_r:system_file:s0 '$DEST'; ")
            append("sync")
        }
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
        val code = process.waitFor()
        if (code != 0) {
            val err = process.errorStream.bufferedReader().use { it.readText() }.trim()
            error(if (err.isNotBlank()) err else "su a échoué (code $code) — /system non accessible ?")
        }
    }
}
