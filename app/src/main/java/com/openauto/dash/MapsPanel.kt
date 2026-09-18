package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState

private const val MAPS_PACKAGE = "com.google.android.apps.maps"

// Camera default when no location fix is available yet.
private val DEFAULT_LATLNG = LatLng(48.8566, 2.3522) // Paris
private const val DEFAULT_ZOOM = 13f

/**
 * The Maps tile.
 *
 * When a Google Maps API key is configured ([BuildConfig.MAPS_API_KEY]), this
 * renders a **real Google map** with the Maps SDK inside the panel-sized tile,
 * centered on the device location. Without a key it falls back to an embedded
 * OpenStreetMap (Leaflet) view so the tile still shows a live map.
 */
@Composable
fun MapsPanel(modifier: Modifier = Modifier) {
    if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
        GoogleMapsPanel(modifier)
    } else {
        LeafletMapsPanel(modifier)
    }
}

// --- Real Google map (Maps SDK via maps-compose) -----------------------------

@Composable
private fun GoogleMapsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasLocation by remember { mutableStateOf(hasLocationPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasLocation = result.values.any { it } }

    LaunchedEffect(Unit) {
        if (!hasLocation) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(DEFAULT_LATLNG, DEFAULT_ZOOM)
    }

    // Recenter on the last known location once we have permission.
    LaunchedEffect(hasLocation) {
        if (hasLocation) {
            lastKnownLatLng(context)?.let {
                cameraPositionState.position = CameraPosition.fromLatLngZoom(it, 15f)
            }
        }
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(
            isMyLocationEnabled = hasLocation,
            mapType = MapType.NORMAL
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = hasLocation,
            compassEnabled = true
        )
    )
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
private fun lastKnownLatLng(context: Context): LatLng? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val location = try {
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } catch (e: SecurityException) {
        null
    } ?: return null
    return LatLng(location.latitude, location.longitude)
}

// --- Fallback: embedded OpenStreetMap (no Maps API key configured) -----------

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeafletMapsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* map recenters once a fix is available */ }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission(context)) {
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
                            Log.d("MapsPanel", "${m.message()} @${m.sourceId()}:${m.lineNumber()}")
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

        // Fallback: open the real Google Maps app for turn-by-turn navigation.
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

/** Centers the Leaflet map on the device's last known location, if permitted. */
private fun injectLastKnownLocation(context: Context, web: WebView?) {
    web ?: return
    val latLng = lastKnownLatLng(context) ?: return
    web.evaluateJavascript("setCenter(${latLng.latitude}, ${latLng.longitude});", null)
}
