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
            // /system is often 100% full on these units, so try every privileged
            // partition Android scans and install into the first one with space.
            val res = dadb.shell(INSTALL_SCRIPT)
            val ok = res.allOutput.lineSequence().firstOrNull { it.startsWith("OKINSTALL:") }
            if (ok == null) {
                val hint = if (uid != "0") " (adbd not root: uid=$uid)" else ""
                error("${res.allOutput.trim().ifBlank { "install failed" }}$hint")
            }
            Log.d("AdbInstaller", "Installed via ADB :$port (uid=$uid): ${ok.removePrefix("OKINSTALL:")}")
        }
    }

    // Tries each privileged partition; installs into the first that has room.
    // Uses \$ for shell variables so Kotlin doesn't interpolate them.
    private val INSTALL_SCRIPT: String = buildString {
        append("TMP=$TMP; ERR=/data/local/tmp/oad_err; : > \$ERR; ")
        append("for BASE in /system /product /system_ext /vendor /odm; do ")
        append("  DIR=\$BASE/priv-app/OpenAutoDash; ")
        append("  mount -o remount,rw \$BASE 2>>\$ERR; mount -o remount,rw / 2>>\$ERR; ")
        append("  if mkdir -p \"\$DIR\" 2>>\$ERR && cp \"\$TMP\" \"\$DIR/OpenAutoDash.apk\" 2>>\$ERR; then ")
        append("    chmod 755 \"\$DIR\" 2>>\$ERR; chmod 644 \"\$DIR/OpenAutoDash.apk\" 2>>\$ERR; ")
        append("    chcon u:object_r:system_file:s0 \"\$DIR/OpenAutoDash.apk\" 2>>\$ERR; ")
        // Drop a privapp-permissions allowlist so a ROM with
        // ro.control_privapp_permissions=enforce GRANTS our signature|privileged
        // perms (ADD_TRUSTED_DISPLAY etc.) instead of boot-looping. Must live in
        // the SAME partition's etc/permissions as the priv-app.
        append("    PERM=\$BASE/etc/permissions; mkdir -p \"\$PERM\" 2>>\$ERR; ")
        append("    XML=\"\$PERM/privapp-permissions-openautodash.xml\"; ")
        append("    { echo '<permissions>'; ")
        append("      echo '<privapp-permissions package=\"com.openauto.dash\">'; ")
        append("      echo '<permission name=\"android.permission.ADD_TRUSTED_DISPLAY\"/>'; ")
        append("      echo '<permission name=\"android.permission.INJECT_EVENTS\"/>'; ")
        append("      echo '<permission name=\"android.permission.BIND_APPWIDGET\"/>'; ")
        append("      echo '</privapp-permissions>'; echo '</permissions>'; } > \"\$XML\" 2>>\$ERR; ")
        append("    chmod 644 \"\$XML\" 2>>\$ERR; chcon u:object_r:system_file:s0 \"\$XML\" 2>>\$ERR; sync; ")
        append("    echo \"OKINSTALL:\$DIR\"; break; ")
        append("  else rm -rf \"\$DIR\" 2>/dev/null; fi; ")
        append("done; ")
        append("echo '---DIAG---'; df /system /product /system_ext /vendor /odm 2>/dev/null; ")
        append("echo '---ERR---'; cat \$ERR 2>/dev/null")
    }

    fun rebootViaAdb(context: Context, port: Int = DEFAULT_PORT): Result<Unit> = runCatching {
        ensureRoot(context, port)
        connect(context, port).use { it.shell("svc power reboot || reboot") }
        Unit
    }
}
