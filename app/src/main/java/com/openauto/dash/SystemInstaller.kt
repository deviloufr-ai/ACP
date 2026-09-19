package com.openauto.dash

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Self-installs the app into `/system/priv-app` on a **rooted** device, so it
 * runs as a privileged system app. That is what unlocks embedding the real
 * Google Maps app inside a dashboard panel (see [MapsPanel]) — the same
 * mechanism OEM/aftermarket car launchers use. Uses `su`; the user grants root
 * via Magisk.
 *
 * Does NOT reboot automatically unless asked ([reboot]). Writing to `/system`
 * can fail (or, with enforcing dm-verity, risk a boot loop), so this is a
 * power-user action guarded by a confirmation in the UI.
 */
object SystemInstaller {

    private const val DIR = "/system/priv-app/OpenAutoDash"
    private const val DEST = "$DIR/OpenAutoDash.apk"

    /** True if the app is already running from a system/privileged location. */
    fun isSystemApp(context: Context): Boolean {
        val info = context.applicationInfo
        val flagged = (info.flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        val path = info.sourceDir.startsWith("/system/") ||
            info.sourceDir.startsWith("/system_ext/") ||
            info.sourceDir.startsWith("/priv-app/") ||
            info.sourceDir.startsWith("/product/") ||
            info.sourceDir.startsWith("/vendor/") ||
            info.sourceDir.startsWith("/odm/")
        return flagged || path
    }

    /** True if a root shell (`su`) is available and granted. */
    fun isRootAvailable(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        process.waitFor() == 0
    }.getOrDefault(false)

    /**
     * Installs the app as a privileged system app using `su`.
     *
     * On a Magisk device it creates a **systemless Magisk module** that provides
     * the APK at `/system/priv-app/OpenAutoDash` (mounted from `/data`, so it does
     * NOT need free space on the full `/system` partition) plus a
     * privapp-permissions whitelist so the priv-app boots cleanly. Without Magisk
     * it falls back to writing `/system` directly (fails if `/system` is full).
     */
    fun installAsSystemApp(context: Context): Result<Unit> = runCatching {
        val apk = context.applicationInfo.sourceDir
        val mod = "/data/adb/modules/openautodash"
        val script = """
            if [ -d /data/adb/modules ]; then
              mkdir -p $mod/system/priv-app/OpenAutoDash $mod/system/etc/permissions || exit 21
              cp '$apk' $mod/system/priv-app/OpenAutoDash/OpenAutoDash.apk || exit 22
              chmod 644 $mod/system/priv-app/OpenAutoDash/OpenAutoDash.apk
              cat > $mod/module.prop <<'P'
            id=openautodash
            name=OpenAuto Dash (priv-app)
            version=v1
            versionCode=1
            author=OpenAutoDash
            description=Installs OpenAuto Dash as a privileged system app (systemless).
            P
              cat > $mod/system/etc/permissions/privapp-permissions-openautodash.xml <<'X'
            <permissions>
              <privapp-permissions package="com.openauto.dash">
                <permission name="android.permission.BIND_APPWIDGET"/>
              </privapp-permissions>
            </permissions>
            X
              echo OKINSTALL:magisk
            else
              mount -o remount,rw / 2>/dev/null
              mount -o remount,rw /system 2>/dev/null
              mkdir -p /system/priv-app/OpenAutoDash || exit 31
              cp '$apk' /system/priv-app/OpenAutoDash/OpenAutoDash.apk || exit 32
              chmod 644 /system/priv-app/OpenAutoDash/OpenAutoDash.apk
              chcon u:object_r:system_file:s0 /system/priv-app/OpenAutoDash/OpenAutoDash.apk 2>/dev/null
              sync
              echo OKINSTALL:system
            fi
        """.trimIndent()

        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        process.waitFor()
        if (!out.contains("OKINSTALL")) {
            error((err.ifBlank { out }).trim().ifBlank { "su install failed" })
        }
    }

    /** Reboots the device via root so the system-app install takes effect. */
    fun reboot(): Result<Unit> = runCatching {
        Runtime.getRuntime().exec(arrayOf("su", "-c", "svc power reboot || reboot"))
        Unit
    }

    /**
     * Installs the app as a privileged system app, preferring the head unit's
     * internal root ADB socket ([AdbInstaller]) and falling back to `su`.
     */
    fun install(context: Context): Result<Unit> {
        // Prefer su/Magisk (systemless, survives a full /system); fall back to ADB.
        val viaSu = installAsSystemApp(context)
        if (viaSu.isSuccess) return viaSu
        val viaAdb = AdbInstaller.installViaAdb(context)
        return if (viaAdb.isSuccess) viaAdb else viaAdb.recoverCatching {
            error("su: ${viaSu.exceptionOrNull()?.message}; ADB: ${it.message}")
        }
    }

    /** Reboots via su, falling back to the root ADB socket. */
    fun rebootDevice(context: Context): Result<Unit> =
        reboot().recoverCatching { AdbInstaller.rebootViaAdb(context).getOrThrow() }
}
