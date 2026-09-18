package com.openauto.dash

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private const val HOST_ID = 1024

/**
 * Process-wide [AppWidgetHost]. Every [SystemWidgetPanel] shares ONE host (a
 * single host id): two hosts with the same id conflict, and hosting widgets in
 * more than one place at once requires the same host. Listening is ref-counted
 * so it is only started while at least one panel is on screen and stopped once
 * they are all gone.
 */
private object WidgetHostHolder {
    private var host: AppWidgetHost? = null
    private var active = 0

    fun acquire(context: Context): AppWidgetHost {
        val h = host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }
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
}

@Composable
fun SystemWidgetPanel(
    slotKey: String,
    placeholderText: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appWidgetManager = remember { AppWidgetManager.getInstance(context) }
    val appWidgetHost = remember { WidgetHostHolder.acquire(context) }

    var savedWidgetId by remember {
        mutableStateOf(
            context.getSharedPreferences("system_widgets_prefs", Context.MODE_PRIVATE)
                .getInt(slotKey, -1)
        )
    }

    var showAppWidgetPicker by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { WidgetHostHolder.release() }
    }

    val configureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ -> }

    val bindLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val widgetId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (result.resultCode == Activity.RESULT_OK && widgetId != -1) {
            savedWidgetId = widgetId
            val widgetInfo = appWidgetManager.getAppWidgetInfo(widgetId)
            if (widgetInfo?.configure != null) {
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = widgetInfo.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
                configureLauncher.launch(intent)
            }
            context.getSharedPreferences("system_widgets_prefs", Context.MODE_PRIVATE)
                .edit().putInt(slotKey, widgetId).apply()
        } else if (widgetId != -1) {
            appWidgetHost.deleteAppWidgetId(widgetId)
        }
    }

    val systemPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val widgetId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
            if (widgetId != -1) {
                savedWidgetId = widgetId
                val widgetInfo = appWidgetManager.getAppWidgetInfo(widgetId)
                if (widgetInfo?.configure != null) {
                    val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                        component = widgetInfo.configure
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    }
                    configureLauncher.launch(intent)
                }
                context.getSharedPreferences("system_widgets_prefs", Context.MODE_PRIVATE)
                    .edit().putInt(slotKey, widgetId).apply()
            }
        } else {
            val widgetId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
            if (widgetId != -1) {
                appWidgetHost.deleteAppWidgetId(widgetId)
            }
        }
    }

    Surface(
        modifier = modifier,
        color = ColorSchemeDefaults.Card,
        shape = RoundedCornerShape(24.dp)
    ) {
        if (savedWidgetId != -1) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        appWidgetHost.createView(ctx, savedWidgetId, appWidgetManager.getAppWidgetInfo(savedWidgetId))
                    },
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    update = { _ -> }
                )
                IconButton(
                    onClick = {
                        appWidgetHost.deleteAppWidgetId(savedWidgetId)
                        savedWidgetId = -1
                        context.getSharedPreferences("system_widgets_prefs", Context.MODE_PRIVATE)
                            .edit().remove(slotKey).apply()
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Widget", tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { showAppWidgetPicker = true },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Widget",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = placeholderText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    if (showAppWidgetPicker) {
        AlertDialog(
            onDismissRequest = { showAppWidgetPicker = false },
            title = { Text("Select Widget Source") },
            text = {
                Text("Choose whether to use the built-in widget selector list or launch the system's official widget bind picker panel.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAppWidgetPicker = false
                        val newWidgetId = appWidgetHost.allocateAppWidgetId()
                        val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, newWidgetId)
                        }
                        systemPickerLauncher.launch(pickIntent)
                    }
                ) {
                    Text("System Picker")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAppWidgetPicker = false
                        // Fallback to built-in list picker logic
                        val newWidgetId = appWidgetHost.allocateAppWidgetId()
                        val installedProviders = appWidgetManager.installedProviders
                        val mapsProvider = installedProviders.firstOrNull { it.provider.packageName.contains("apps.maps") }
                        if (mapsProvider != null) {
                            val allowed = appWidgetManager.bindAppWidgetIdIfAllowed(newWidgetId, mapsProvider.provider)
                            if (!allowed) {
                                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, newWidgetId)
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, mapsProvider.provider)
                                }
                                bindLauncher.launch(intent)
                            } else {
                                savedWidgetId = newWidgetId
                                val widgetInfo = appWidgetManager.getAppWidgetInfo(newWidgetId)
                                if (widgetInfo?.configure != null) {
                                    val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                                        component = widgetInfo.configure
                                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, newWidgetId)
                                    }
                                    configureLauncher.launch(intent)
                                }
                                context.getSharedPreferences("system_widgets_prefs", Context.MODE_PRIVATE)
                                    .edit().putInt(slotKey, newWidgetId).apply()
                            }
                        } else if (installedProviders.isNotEmpty()) {
                            // Select first available item fallback if maps package string matches nothing
                            val allowed = appWidgetManager.bindAppWidgetIdIfAllowed(newWidgetId, installedProviders[0].provider)
                            if (!allowed) {
                                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, newWidgetId)
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, installedProviders[0].provider)
                                }
                                bindLauncher.launch(intent)
                            } else {
                                savedWidgetId = newWidgetId
                                context.getSharedPreferences("system_widgets_prefs", Context.MODE_PRIVATE)
                                    .edit().putInt(slotKey, newWidgetId).apply()
                            }
                        }
                    }
                ) {
                    Text("Auto-Bind Maps")
                }
            }
        )
    }
}

private object ColorSchemeDefaults {
    val Card = Color(0xFF1E2024)
}
