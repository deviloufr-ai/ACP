package com.openauto.dash

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Details of the newest release found on GitHub. */
data class UpdateInfo(
    val versionName: String,
    val buildNumber: Long,
    val apkUrl: String,
    val notes: String
)

/** Update lifecycle observed by the UI. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data class Downloading(val percent: Int) : UpdateStatus
    data object Installing : UpdateStatus
    data class Error(@StringRes val messageRes: Int) : UpdateStatus
}

/**
 * In-app updater that tracks the installed version against the latest GitHub
 * Release and downloads/installs a newer APK.
 *
 * Version tracking: the installed [currentVersionCode] comes from the APK
 * (set by CI to the Actions run number); the latest build number is parsed from
 * the release tag (e.g. `v1.0.42` → 42). A newer build number means an update.
 *
 * Note: Android always shows its own install confirmation, and a downloaded APK
 * can only replace the installed one if both are signed with the same key.
 */
class UpdateManager(private val context: Context) {

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    val currentVersionName: String = BuildConfig.VERSION_NAME
    val currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()

    /** Queries GitHub for the latest release and updates [status]. */
    suspend fun checkForUpdate() {
        _status.value = UpdateStatus.Checking
        val info = withContext(Dispatchers.IO) { fetchLatestRelease() }
        _status.value = when {
            info == null -> UpdateStatus.Error(R.string.sys_update_check_failed)
            info.buildNumber > currentVersionCode -> UpdateStatus.Available(info)
            else -> UpdateStatus.UpToDate
        }
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = URL(
                "https://api.github.com/repos/" +
                    "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
            )
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "OpenAutoDash-Updater")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }

            connection.inputStream.bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val tag = json.optString("tag_name")
                val notes = json.optString("body")
                val buildNumber = parseBuildNumber(tag) ?: return null
                val apkUrl = firstApkAssetUrl(json) ?: return null
                UpdateInfo(
                    versionName = tag,
                    buildNumber = buildNumber,
                    apkUrl = apkUrl,
                    notes = notes
                )
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun firstApkAssetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            // The release also carries the phone companion app: never install that here.
            if (name.endsWith(".apk", ignoreCase = true) && !name.equals(COMPANION_APK_NAME, ignoreCase = true)) {
                val url = asset.optString("browser_download_url").ifBlank { null } ?: continue
                // The URL comes from a JSON document fetched over the network;
                // only accept GitHub's own release hosts.
                val host = Uri.parse(url).host.orEmpty()
                if (url.startsWith("https://") && host in ALLOWED_DOWNLOAD_HOSTS) return url
            }
        }
        return null
    }

    /** True if the app may install APKs (Android 8+ requires a per-app grant). */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Sends the user to enable "install unknown apps" for this app. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Downloads the update APK, then launches the system installer. */
    suspend fun downloadAndInstall(info: UpdateInfo) {
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
        withContext(Dispatchers.IO) { if (apkFile.exists()) apkFile.delete() }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("Dashwheel ${info.versionName}")
            .setDescription(context.getString(R.string.sys_update_downloading))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)

        _status.value = UpdateStatus.Downloading(0)
        val downloadId = downloadManager.enqueue(request)

        var success = false
        try {
            success = withContext(Dispatchers.IO) {
                withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { awaitDownload(downloadManager, downloadId) } ?: false
            }
        } finally {
            // Failed, stuck or abandoned: nothing left queued in the system's downloader.
            if (!success) downloadManager.remove(downloadId)
        }
        if (!success) {
            _status.value = UpdateStatus.Error(R.string.sys_update_download_failed)
            return
        }

        _status.value = UpdateStatus.Installing
        launchInstaller(apkFile)
    }

    /**
     * Follows the download to its end. A download paused or pending (no
     * network, the server gone quiet) that makes no progress for [STALL_MS]
     * counts as failed rather than showing the same percentage forever.
     */
    private suspend fun awaitDownload(downloadManager: DownloadManager, id: Long): Boolean {
        var lastBytes = -1L
        var lastProgressAt = SystemClock.elapsedRealtime()
        while (true) {
            val query = DownloadManager.Query().setFilterById(id)
            downloadManager.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return false
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> return true
                    DownloadManager.STATUS_FAILED -> return false
                    else -> {
                        val soFar =
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total =
                            cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        if (total > 0) {
                            _status.value = UpdateStatus.Downloading(((soFar * 100) / total).toInt())
                        }
                        if (soFar != lastBytes) {
                            lastBytes = soFar
                            lastProgressAt = SystemClock.elapsedRealtime()
                        } else if (SystemClock.elapsedRealtime() - lastProgressAt > STALL_MS) {
                            return false
                        }
                    }
                }
            } ?: return false
            delay(400)
        }
    }

    private fun launchInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun dismiss() {
        _status.value = UpdateStatus.Idle
    }

    companion object {
        /** Build number from a release tag or name: "v1.0.42" -> 42 (the last number wins). */
        internal fun parseBuildNumber(text: String): Long? =
            Regex("(\\d+)").findAll(text).lastOrNull()?.value?.toLongOrNull()

        private const val APK_NAME = "openauto-dash-update.apk"
        /** The whole download, however slowly it still moves. */
        private const val DOWNLOAD_TIMEOUT_MS = 30 * 60_000L
        /**
         * No byte arrived for this long: paused for good, as far as the driver is
         * concerned. Generous, as car Wi-Fi (a phone hotspot) drops out in tunnels.
         */
        private const val STALL_MS = 5 * 60_000L
        /** The phone companion app's asset in each release (see build.yml). */
        const val COMPANION_APK_NAME = "dashwheel-companion.apk"
        private val ALLOWED_DOWNLOAD_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    }
}
