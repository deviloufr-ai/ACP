package com.openauto.dash

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Self-installs the app into `/system/priv-app` over the head unit's **internal
 * wireless ADB** socket. Many aftermarket units (e.g. ROCO/K706) ship with
 * `adbd` running as **root** and listening on a fixed localhost TCP port
 * (`service.adb.tcp.port` in build.prop — here 9876). Connecting to it gives a
 * root shell without Magisk/su, which is enough to remount `/system` and copy
 * the APK in — exactly how some OEM launchers self-privilege.
 *
 * We generate our own ADB RSA keypair under the app's private files dir (the
 * default `~/.android/adbkey` path isn't writable from an app sandbox). If the
 * unit's `adbd` is secure it will show a one-time "allow debugging" prompt for
 * this key; if insecure it connects straight away.
 */
object AdbInstaller {

    const val DEFAULT_PORT = 9876
    private const val HOST = "127.0.0.1"
    private const val DIR = "/system/priv-app/OpenAutoDash"
    private const val DEST = "$DIR/OpenAutoDash.apk"

    private fun connect(context: Context, port: Int): Dadb {
        val keyDir = File(context.filesDir, "adb").apply { mkdirs() }
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            AdbKeyPair.generate(priv, pub)
        }
        val keyPair = AdbKeyPair.read(priv, pub)
        return Dadb.create(HOST, port, keyPair)
    }

    fun installViaAdb(context: Context, port: Int = DEFAULT_PORT): Result<Unit> = runCatching {
        val apk = File(context.applicationInfo.sourceDir)
        connect(context, port).use { dadb ->
            // Remount system writable and make sure the target dir exists.
            dadb.shell(
                "mount -o remount,rw / 2>/dev/null; " +
                    "mount -o remount,rw /system 2>/dev/null; " +
                    "mkdir -p $DIR"
            )
            // Push our own APK into the privileged app directory.
            dadb.push(apk, DEST)
            // Fix ownership/labels so the package manager will pick it up.
            val fix = dadb.shell(
                "chmod 755 $DIR && chmod 644 $DEST; " +
                    "chcon u:object_r:system_file:s0 $DEST 2>/dev/null; sync"
            )
            if (fix.exitCode != 0) {
                error(fix.allOutput.trim().ifBlank { "adb shell failed (${fix.exitCode})" })
            }
            Log.d("AdbInstaller", "Installed to $DEST via ADB :$port")
        }
    }

    fun rebootViaAdb(context: Context, port: Int = DEFAULT_PORT): Result<Unit> = runCatching {
        connect(context, port).use { it.shell("svc power reboot || reboot") }
        Unit
    }
}
