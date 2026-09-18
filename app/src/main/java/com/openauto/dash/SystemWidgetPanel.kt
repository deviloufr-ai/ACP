package com.openauto.dash

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val HOST_ID = 1024

/**
 * Process-wide [AppWidgetHost]. Every hosted widget shares ONE host (a single
 * host id): two hosts with the same id conflict, and hosting widgets in more
 * than one place at once requires the same host. Listening is ref-counted so it
 * runs only while at least one widget is on screen and stops once they are gone.
 */
object WidgetHostHolder {
    private var host: AppWidgetHost? = null
    private var active = 0

    private fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }

    fun acquire(context: Context): AppWidgetHost {
        val h = host(context)
        if (active == 0) runCatching { h.startListening() }
        active++
        return h
    }

    fun release() {
        active--
        if (active <= 0) {
            active = 0
            runCatching { host?.stopListening() }
        }
    }

    /** Releases the system-side allocation for a widget the user removed. */
    fun delete(context: Context, appWidgetId: Int) {
        runCatching { host(context).deleteAppWidgetId(appWidgetId) }
    }
}

/**
 * Renders a bound Android app-widget by its [appWidgetId] inside the shared host.
 * Keeps the host listening while it is on screen. Shows a small placeholder if
 * the widget's provider is gone (e.g. its app was uninstalled).
 */
@Composable
fun HostedSystemWidget(appWidgetId: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val host = remember { WidgetHostHolder.acquire(context) }
    val manager = remember { AppWidgetManager.getInstance(context) }

    DisposableEffect(Unit) {
        onDispose { WidgetHostHolder.release() }
    }

    val info = remember(appWidgetId) { manager.getAppWidgetInfo(appWidgetId) }
    if (info == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "Widget unavailable",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    AndroidView(
        factory = { ctx -> host.createView(ctx, appWidgetId, info) },
        modifier = modifier.fillMaxSize().padding(8.dp),
        update = { }
    )
}

/**
 * Returns a launcher lambda that runs the system app-widget picker (pick → bind →
 * optional configure) and calls [onAdded] with the resulting, ready-to-host widget
 * id. On cancellation at any step the allocated id is released.
 */
@Composable
fun rememberSystemWidgetAdder(onAdded: (Int) -> Unit): () -> Unit {
    val context = LocalContext.current
    val host = remember { WidgetHostHolder.acquire(context) }
    val manager = remember { AppWidgetManager.getInstance(context) }

    DisposableEffect(Unit) {
        onDispose { WidgetHostHolder.release() }
    }

    // Id awaiting its configure activity result.
    var pendingConfigureId by remember { mutableIntStateOf(-1) }

    val configureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = pendingConfigureId
        pendingConfigureId = -1
        if (result.resultCode == Activity.RESULT_OK && id != -1) {
            onAdded(id)
        } else if (id != -1) {
            host.deleteAppWidgetId(id)
        }
    }

    fun finish(id: Int) {
        val info = manager.getAppWidgetInfo(id)
        if (info?.configure != null) {
            pendingConfigureId = id
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = info.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
            runCatching { configureLauncher.launch(intent) }
                .onFailure { host.deleteAppWidgetId(id) }
        } else {
            onAdded(id)
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (result.resultCode == Activity.RESULT_OK && id != -1) {
            finish(id)
        } else if (id != -1) {
            host.deleteAppWidgetId(id)
        }
    }

    return {
        val id = host.allocateAppWidgetId()
        val pick = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            // Some ROMs NPE on a picker launched without these extras present.
            putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_INFO, arrayListOf())
            putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_EXTRAS, arrayListOf())
        }
        runCatching { pickLauncher.launch(pick) }
            .onFailure { host.deleteAppWidgetId(id) }
    }
}
