package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

/**
 * Left panel: an actual interactive map.
 *
 * Google Maps on the web deliberately degrades inside a WebView to an
 * "open the app" page, so this shows a Leaflet + OpenStreetMap map. Leaflet is
 * bundled in `assets/` (not loaded from a CDN, which a head unit may not be able
 * to reach), so only the map tiles need the network. The map centers on the
 * vehicle's location, injected from Android's [LocationManager] (WebView
 * geolocation is unreliable from a file:// origin).
 *
 * The real Google Maps app can't be embedded in a view; use the split-screen
 * cockpit ([SplitScreenLauncher]) to run it as its own pane.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapsPanel(modifier: Modifier = Modifier) {
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
            factory = { ctx ->
                WebView(ctx).apply {
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
    }
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
