package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

private const val MAPS_PACKAGE = "com.google.android.apps.maps"

/**
 * Left map panel.
 *
 * If the app is installed as a **privileged/system app** (see the QF001/Roco
 * root notes), it hosts the real Google Maps activity inside an [ActivityView]
 * — the interactive embedding used by vendor launchers on Android 10. Because
 * `ActivityView` is a hidden system class (present up to Android 10/11, removed
 * in Android 12), it is accessed by reflection and only works for a system app.
 * A normal install can't do this, so it falls back to an embedded OpenStreetMap
 * map + an "Open Maps" button.
 */
@Composable
fun MapsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val canEmbed = remember {
        // ROCO/K706 units often have ActivityView even on newer Android versions.
        // We try to use it if the class exists and we have system-level privileges.
        val hasActivityView = runCatching { Class.forName("android.app.ActivityView") }.isSuccess
        val isSystem = isSystemApp(context)
        
        Log.d("MapsPanel", "canEmbed check: hasActivityView=$hasActivityView, isSystem=$isSystem")
        
        hasActivityView && isSystem
    }

    if (canEmbed) {
        EmbeddedGoogleMapsPanel(modifier)
    } else {
        LeafletMapsPanel(modifier)
    }
}

private fun isSystemApp(context: Context): Boolean {
    val appInfo = context.applicationInfo
    val flags = appInfo.flags
    val isSystem = (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    val isSystemPath = appInfo.sourceDir.startsWith("/system/") || 
                      appInfo.sourceDir.startsWith("/priv-app/") ||
                      appInfo.sourceDir.startsWith("/product/") ||
                      appInfo.sourceDir.startsWith("/vendor/")
                      
    return isSystem || isSystemPath
}

// --- Privileged path: real Google Maps inside an ActivityView (Android 10) ---

/**
 * Hosts the real Google Maps app in an [android.app.ActivityView] (reflection).
 * Interactive: touches reach Maps. Requires the app to be a privileged system
 * app; otherwise the reflected calls throw and this composable stays blank (the
 * chooser only routes here when [isSystemApp] is true).
 */
@Composable
private fun EmbeddedGoogleMapsPanel(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val activityView = runCatching {
                Class.forName("android.app.ActivityView")
                    .getConstructor(Context::class.java)
                    .newInstance(ctx) as ViewGroup
            }.onFailure {
                Log.e("MapsPanel", "Failed to create ActivityView (requires system app privileges)", it)
            }.getOrNull()

            if (activityView == null) {
                FrameLayout(ctx)
            } else {
                activityView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        // Give ActivityView a moment to create its virtual display.
                        // Slow head units may need a longer delay.
                        v.postDelayed({ launchMapsInto(ctx, v) }, 1000)
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        runCatching { v.javaClass.getMethod("release").invoke(v) }
                    }
                })
                activityView
            }
        }
    )
}

private fun launchMapsInto(context: Context, activityView: View) {
    val maps = context.packageManager
        .getLaunchIntentForPackage(MAPS_PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ?.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) ?: return
        
    Log.d("MapsPanel", "Launching Maps into ActivityView...")
    runCatching {
        activityView.javaClass
            .getMethod("startActivity", Intent::class.java)
            .invoke(activityView, maps)
    }.onFailure {
        Log.e("MapsPanel", "Failed to launch Maps into ActivityView", it)
    }
}

// --- Fallback path: embedded OpenStreetMap (non-privileged installs) ---------

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeafletMapsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* map recenters once a fix is available */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: Context ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#1B1D22"))
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setGeolocationEnabled(true)
                        allowFileAccess = true
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: GeolocationPermissions.Callback?
                        ) {
                            callback?.invoke(origin, true, false)
                        }

                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                            Log.d(
                                "MapsPanel",
                                "${m.message()} @${m.sourceId()}:${m.lineNumber()}"
                            )
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            injectLastKnownLocation(ctx, view)
                        }
                    }
                    loadUrl("file:///android_asset/map.html")
                }
            }
        )

        // Fallback: open the real Google Maps app (works offline; a WebView can't
        // embed it). Useful if the head unit can't load online map tiles.
        FilledTonalButton(
            onClick = { openMapsApp(context) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
        ) {
            Text("Open Maps")
        }
    }
}

/** Opens the Google Maps app, or a generic geo intent as a fallback. */
private fun openMapsApp(context: Context) {
    val intent = context.packageManager
        .getLaunchIntentForPackage(MAPS_PACKAGE)
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Centers the map on the device's last known location, if permission is held. */
@SuppressLint("MissingPermission")
private fun injectLastKnownLocation(context: Context, web: WebView?) {
    web ?: return
    val fine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
    val location = try {
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } catch (e: SecurityException) {
        null
    } ?: return

    web.evaluateJavascript("setCenter(${location.latitude}, ${location.longitude});", null)
}
