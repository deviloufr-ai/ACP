package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

private const val MAPS_PACKAGE = "com.google.android.apps.maps"

// Camera default when no location fix is available yet.
private val DEFAULT_LATLNG = LatLng(48.8566, 2.3522) // Paris
private const val DEFAULT_ZOOM = 13f

/**
 * The Maps tile, in priority order:
 *
 *  1. **Privileged system app** → embeds the *real* Google Maps app (with full
 *     navigation) inside the panel via [android.app.ActivityView] — the same
 *     technique OEM/aftermarket car launchers use. Needs the app installed to
 *     `/system/priv-app` (see [SystemInstaller]).
 *  2. **Maps API key** configured → a real Google map via the Maps SDK (shows
 *     the map and your location; no in-panel turn-by-turn).
 *  3. **Neither** → an embedded OpenStreetMap (Leaflet) view.
 */
@Composable
fun MapsPanel(modifier: Modifier = Modifier) {
    // NOTE: ActivityView embedding of the real Maps app renders black on this
    // head unit (it's vendor/version-specific and unreliable), so the tile shows
    // the Google Maps SDK map and the real app (with navigation) is available via
    // the "Float real Maps" freeform window instead — see EmbeddedGoogleMapsPanel
    // below, kept for reference/other units.
    if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
        GoogleMapsPanel(modifier)
    } else {
        LeafletMapsPanel(modifier)
    }
}

// --- Privileged path: the real Google Maps app inside an ActivityView --------

/**
 * Hosts the real Google Maps app in an [android.app.ActivityView] (reflection —
 * the class is hidden and present up to Android 10/11). Interactive: touches,
 * search and navigation all reach Maps. Only reached when [isSystemApp] is true.
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
                Log.e("MapsPanel", "ActivityView unavailable (needs system privileges)", it)
            }.getOrNull()

            if (activityView == null) {
                FrameLayout(ctx)
            } else {
                activityView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        // Give ActivityView a moment to create its virtual display.
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

    Log.d("MapsPanel", "Launching Maps into ActivityView…")
    runCatching {
        activityView.javaClass
            .getMethod("startActivity", Intent::class.java)
            .invoke(activityView, maps)
    }.onFailure {
        Log.e("MapsPanel", "Failed to launch Maps into ActivityView", it)
    }
}

// --- Real Google map (Maps SDK via maps-compose) + route drawing -------------

private val RouteColor = Color(0xFF8AB4F8)
private val OverlayBg = Color(0xE6141518)

@Composable
private fun GoogleMapsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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

    // Recenter on the last known location once we have permission (unless a
    // route is already framed).
    LaunchedEffect(hasLocation) {
        if (hasLocation) {
            lastKnownLatLng(context)?.let {
                cameraPositionState.position = CameraPosition.fromLatLngZoom(it, 15f)
            }
        }
    }

    var destination by remember { mutableStateOf("") }
    var route by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var destMarker by remember { mutableStateOf<LatLng?>(null) }
    var routeInfo by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun clearRoute() {
        route = emptyList(); destMarker = null; routeInfo = null; error = null
    }

    fun showRoute() {
        val q = destination.trim()
        if (q.isEmpty() || loading) return
        error = null
        loading = true
        scope.launch {
            val origin = lastKnownLatLng(context) ?: cameraPositionState.position.target
            val result = withContext(Dispatchers.IO) {
                Directions.fetch(BuildConfig.MAPS_API_KEY, origin, q)
            }
            loading = false
            result.onSuccess { r ->
                route = r.points
                destMarker = r.destination
                routeInfo = "${r.distance} · ${r.duration}"
                val pts = r.points.ifEmpty { listOf(origin, r.destination) }
                runCatching {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(boundsOf(pts), 120))
                }
            }.onFailure { error = it.message ?: "Route not found" }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
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
        ) {
            if (route.isNotEmpty()) {
                Polyline(points = route, color = RouteColor, width = 14f)
            }
            destMarker?.let { Marker(state = rememberMarkerState(position = it), title = destination) }
        }

        // Destination search bar.
        Surface(
            color = OverlayBg,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    placeholder = { Text("Where to?", color = Color(0xFF9AA0A6)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = RouteColor,
                        unfocusedBorderColor = Color(0xFF2A2D33),
                        cursorColor = RouteColor
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { showRoute() })
                )
                Spacer(Modifier.width(6.dp))
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = RouteColor,
                        strokeWidth = 3.dp
                    )
                } else {
                    IconButton(onClick = { showRoute() }) {
                        Icon(Icons.Filled.Search, contentDescription = "Show route", tint = RouteColor)
                    }
                }
                if (route.isNotEmpty() || destMarker != null) {
                    IconButton(onClick = { clearRoute() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear route", tint = Color(0xFF9AA0A6))
                    }
                }
            }
        }

        // Route info + Navigate handoff / error.
        val info = routeInfo
        val err = error
        if (info != null || err != null) {
            Surface(
                color = OverlayBg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = info ?: err ?: "",
                        color = if (err != null) Color(0xFFF28B82) else Color.White
                    )
                    if (info != null) {
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = { startNavigation(context, destination) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RouteColor,
                                contentColor = Color(0xFF0B0C0F)
                            )
                        ) {
                            Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Navigate")
                        }
                    }
                }
            }
        }
    }
}

/** Opens the real Google Maps app in turn-by-turn navigation to [destination]. */
private fun startNavigation(context: Context, destination: String) {
    val q = Uri.encode(destination)
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).apply {
        setPackage(MAPS_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(nav); true }.getOrDefault(false)) return
    val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(fallback) }
}

private fun boundsOf(points: List<LatLng>): LatLngBounds {
    val b = LatLngBounds.builder()
    points.forEach { b.include(it) }
    return b.build()
}

/** Minimal Google Directions API client (draws the route on our own map). */
private object Directions {
    data class Route(
        val points: List<LatLng>,
        val destination: LatLng,
        val distance: String,
        val duration: String
    )

    fun fetch(apiKey: String, origin: LatLng, destQuery: String): Result<Route> = runCatching {
        require(apiKey.isNotBlank()) { "No Maps API key configured" }
        val url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=${origin.latitude},${origin.longitude}" +
            "&destination=${URLEncoder.encode(destQuery, "UTF-8")}" +
            "&mode=driving&key=$apiKey"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        when (val status = json.optString("status")) {
            "OK" -> {}
            "ZERO_RESULTS" -> error("No route found")
            else -> error(json.optString("error_message").ifBlank { "Directions error: $status" })
        }
        val route0 = json.getJSONArray("routes").getJSONObject(0)
        val leg = route0.getJSONArray("legs").getJSONObject(0)
        val distance = leg.getJSONObject("distance").getString("text")
        val duration = leg.getJSONObject("duration").getString("text")
        val end = leg.getJSONObject("end_location")
        val dest = LatLng(end.getDouble("lat"), end.getDouble("lng"))
        val encoded = route0.getJSONObject("overview_polyline").getString("points")
        Route(decodePolyline(encoded), dest, distance, duration)
    }
}

/** Decodes a Google encoded-polyline string into points. */
private fun decodePolyline(encoded: String): List<LatLng> {
    val poly = ArrayList<LatLng>()
    var index = 0
    val len = encoded.length
    var lat = 0
    var lng = 0
    while (index < len) {
        var b: Int
        var shift = 0
        var result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        shift = 0
        result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        poly.add(LatLng(lat / 1E5, lng / 1E5))
    }
    return poly
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
