package com.openauto.dash

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
    data class Error(val message: String) : UpdateStatus
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
            info == null -> UpdateStatus.Error("Couldn't check for updates")
            info.buildNumber > currentVersionCode -> UpdateStatus.Available(info)
            else -> UpdateStatus.UpToDate
        }
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        return try {
            val endpoint = URL(
                "https://api.github.com/repos/" +
                    "${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"
            )
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "OpenAutoDash-Updater")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

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
        }
    }

    private fun firstApkAssetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url").ifBlank { null }
            }
        }
        return null
    }

    /** Extracts the trailing integer from a tag, e.g. "v1.0.42" → 42. */
    private fun parseBuildNumber(text: String): Long? =
        Regex("(\\d+)").findAll(text).lastOrNull()?.value?.toLongOrNull()

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
        if (apkFile.exists()) apkFile.delete()

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("OpenAuto Dash ${info.versionName}")
            .setDescription("Downloading update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_NAME)

        _status.value = UpdateStatus.Downloading(0)
        val downloadId = downloadManager.enqueue(request)

        val success = withContext(Dispatchers.IO) { awaitDownload(downloadManager, downloadId) }
        if (!success) {
            _status.value = UpdateStatus.Error("Download failed")
            return
        }

        _status.value = UpdateStatus.Installing
        launchInstaller(apkFile)
    }

    private suspend fun awaitDownload(downloadManager: DownloadManager, id: Long): Boolean {
        while (true) {
            val query = DownloadManager.Query().setFilterById(id)
            downloadManager.query(query).use { cursor ->
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
                    }
                }
            }
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
        private const val APK_NAME = "openauto-dash-update.apk"
    }
}
