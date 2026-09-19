package com.openauto.dash

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap

private const val HOST_ID = 1024

/**
 * Process-wide [AppWidgetHost]. Every hosted widget shares ONE host (a single
 * host id): two hosts with the same id conflict, and hosting widgets in more
 * than one place at once requires the same host. Listening is ref-counted.
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

    fun delete(context: Context, appWidgetId: Int) {
        runCatching { host(context).deleteAppWidgetId(appWidgetId) }
    }
}

/**
 * Renders a bound Android app-widget by its [appWidgetId] inside the shared host.
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
 * Returns a launcher lambda that shows OUR OWN widget picker (built from the full
 * [AppWidgetManager.installedProviders] list — the stock system picker hides many
 * widgets, e.g. Google Maps), binds the chosen provider, runs any configure step,
 * and calls [onAdded] with the ready widget id.
 */
@Composable
fun rememberSystemWidgetAdder(onAdded: (Int) -> Unit): () -> Unit {
    val context = LocalContext.current
    val host = remember { WidgetHostHolder.acquire(context) }
    val manager = remember { AppWidgetManager.getInstance(context) }

    DisposableEffect(Unit) {
        onDispose { WidgetHostHolder.release() }
    }

    var showPicker by remember { mutableStateOf(false) }
    var pendingId by remember { mutableIntStateOf(-1) }

    val configureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = pendingId
        pendingId = -1
        if (result.resultCode == Activity.RESULT_OK && id != -1) onAdded(id)
        else if (id != -1) host.deleteAppWidgetId(id)
    }

    fun finishBound(id: Int) {
        val info = manager.getAppWidgetInfo(id)
        if (info?.configure != null) {
            pendingId = id
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

    val bindLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = pendingId
        pendingId = -1
        if (result.resultCode == Activity.RESULT_OK && id != -1) finishBound(id)
        else if (id != -1) host.deleteAppWidgetId(id)
    }

    fun pick(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        val allowed = runCatching { manager.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
        if (allowed) {
            finishBound(id)
        } else {
            pendingId = id
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            }
            runCatching { bindLauncher.launch(intent) }.onFailure { host.deleteAppWidgetId(id) }
        }
    }

    if (showPicker) {
        SystemWidgetPickerDialog(
            providers = remember { manager.installedProviders.sortedBy { it.loadLabel(context.packageManager).lowercase() } },
            onPick = { showPicker = false; pick(it) },
            onDismiss = { showPicker = false }
        )
    }

    return { showPicker = true }
}

/** Our full widget picker: every installed AppWidget provider, icon + label. */
@Composable
private fun SystemWidgetPickerDialog(
    providers: List<AppWidgetProviderInfo>,
    onPick: (AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a widget") },
        text = {
            if (providers.isEmpty()) {
                Text("No widgets found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                    items(providers) { info ->
                        val label = remember(info) {
                            runCatching { info.loadLabel(context.packageManager) }.getOrDefault(
                                info.provider.packageName
                            )
                        }
                        val iconBitmap = remember(info) {
                            runCatching {
                                (info.loadIcon(context, 0)
                                    ?: context.packageManager.getApplicationIcon(info.provider.packageName))
                                    .toBitmap(width = 96, height = 96).asImageBitmap()
                            }.getOrNull()
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(info) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (iconBitmap != null) {
                                Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(36.dp))
                            } else {
                                Spacer(Modifier.size(36.dp))
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(label, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    info.provider.packageName,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
