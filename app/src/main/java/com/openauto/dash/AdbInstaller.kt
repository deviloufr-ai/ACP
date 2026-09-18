package com.openauto.dash

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Self-installs the app into `/system/priv-app` over the head unit's **internal
 * wireless ADB** socket (ROCO/K706: fixed `service.adb.tcp.port` = 9876 in
 * build.prop). This is how some OEM launchers self-privilege.
 *
 * Steps: connect (with an app-owned RSA key), make sure `adbd` is **root**
 * (`adb root` restart — the same thing an external "adb root" does), push the
 * APK to a writable temp dir, then root-`cp` it into `/system/priv-app` and fix
 * perms + SELinux label. Pushing straight into `/system` fails ("sync FAIL")
 * even when connected, so we always stage through `/data/local/tmp`.
 */
object AdbInstaller {

    const val DEFAULT_PORT = 9876
    private const val HOST = "127.0.0.1"
    private const val DIR = "/system/priv-app/OpenAutoDash"
    private const val DEST = "$DIR/OpenAutoDash.apk"
    private const val TMP = "/data/local/tmp/OpenAutoDash.apk"

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

    private fun currentUid(context: Context, port: Int): String? =
        runCatching { connect(context, port).use { it.shell("id -u").output.trim() } }.getOrNull()

    /** Restart adbd as root (like `adb root`), then wait for it to come back. */
    private fun ensureRoot(context: Context, port: Int): Boolean {
        if (currentUid(context, port) == "0") return true
        runCatching {
            connect(context, port).use { dadb ->
                val stream = dadb.open("root:")
                runCatching { stream.close() }
            }
        }
        repeat(10) {
            Thread.sleep(1000)
            if (currentUid(context, port) == "0") return true
        }
        return false
    }

    fun installViaAdb(context: Context, port: Int = DEFAULT_PORT): Result<Unit> = runCatching {
        val apk = File(context.applicationInfo.sourceDir)
        ensureRoot(context, port)
        connect(context, port).use { dadb ->
            val uid = dadb.shell("id -u").output.trim()
            // Stage the APK in a writable temp dir (sync straight to /system FAILs).
            dadb.push(apk, TMP)
            // Privileged copy into /system/priv-app. Needs root adbd.
            val script = buildString {
                append("mount -o remount,rw / 2>/dev/null; ")
                append("mount -o remount,rw /system 2>/dev/null; ")
                append("mkdir -p $DIR && ")
                append("cp $TMP $DEST && ")
                append("chmod 755 $DIR && chmod 644 $DEST && ")
                append("(chcon u:object_r:system_file:s0 $DEST 2>/dev/null || true) && ")
                append("sync && echo OKINSTALL")
            }
            val res = dadb.shell(script)
            if (!res.allOutput.contains("OKINSTALL")) {
                val hint = if (uid != "0") " (adbd not root: uid=$uid — this unit's :$port adbd won't run as root)" else ""
                error("${res.allOutput.trim().ifBlank { "install failed" }}$hint")
            }
            Log.d("AdbInstaller", "Installed to $DEST via ADB :$port (uid=$uid)")
        }
    }

    fun rebootViaAdb(context: Context, port: Int = DEFAULT_PORT): Result<Unit> = runCatching {
        ensureRoot(context, port)
        connect(context, port).use { it.shell("svc power reboot || reboot") }
        Unit
    }
}
