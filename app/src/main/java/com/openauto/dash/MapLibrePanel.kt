package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Point
import org.maplibre.navigation.android.navigation.ui.v5.NavigationLauncher
import org.maplibre.navigation.android.navigation.ui.v5.NavigationLauncherOptions
import org.maplibre.navigation.android.navigation.ui.v5.route.NavigationMapRoute
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.RouteOptions
import java.net.URLEncoder
import java.util.Locale

// Free, no-key services: OpenFreeMap tiles, Nominatim geocoding, Valhalla routing.
private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val VALHALLA_URL = "https://valhalla1.openstreetmap.de/route"
private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
private const val USER_AGENT = "OpenAutoDash/1.0 (car launcher)"

private val Accent = Color(0xFF8AB4F8)
private val OverlayBg = Color(0xE6141518)

/**
 * A free, open-source **GPS navigator** built on MapLibre GL:
 *  - map + your location (OpenFreeMap style — no token/account/card),
 *  - type a destination (Nominatim geocoding) or tap the map,
 *  - route computed by a free Valhalla server, drawn on the map with ETA,
 *  - **Start** launches fullscreen turn-by-turn (voice + rerouting).
 */
@Composable
fun MapLibrePanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasLocation by remember { mutableStateOf(hasLocationPerm(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasLocation = result.values.any { it } }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!hasLocation) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context, null).textureMode(true)
        MapView(context, options)
    }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var navRoute by remember { mutableStateOf<NavigationMapRoute?>(null) }
    var route by remember { mutableStateOf<DirectionsRoute?>(null) }
    var query by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun clearRoute() {
        route = null; info = null; error = null
        navRoute?.removeRoute()
        mapRef?.markers?.forEach { mapRef?.removeMarker(it) }
    }

    fun routeTo(dest: Point) {
        val map = mapRef ?: return
        @SuppressLint("MissingPermission")
        val loc = map.locationComponent.lastKnownLocation
        if (loc == null) { error = "Waiting for GPS location…"; return }
        val origin = Point.fromLngLat(loc.longitude, loc.latitude)
        error = null; loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { valhallaRoute(origin, dest, Locale.getDefault().language) }
            }
            loading = false
            result.onSuccess { resp ->
                val first = resp.routes.firstOrNull()
                if (first == null) { error = "No route found"; return@onSuccess }
                route = first.copy(
                    routeOptions = RouteOptions(
                        baseUrl = "https://valhalla.routing",
                        profile = "valhalla",
                        user = "valhalla",
                        accessToken = "valhalla",
                        voiceInstructions = true,
                        bannerInstructions = true,
                        language = Locale.getDefault().language,
                        coordinates = listOf(origin, dest),
                        requestUuid = "0000-0000-0000-0000"
                    )
                )
                navRoute?.addRoutes(resp.routes)
                info = formatEta(first.distance, first.duration)
                map.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(dest.latitude(), dest.longitude()), 13.0)
                )
            }.onFailure { error = it.message ?: "Routing failed" }
        }
    }

    fun searchAndRoute() {
        val q = query.trim()
        if (q.isEmpty() || loading) return
        error = null; loading = true
        scope.launch {
            val dest = withContext(Dispatchers.IO) { runCatching { geocode(q) }.getOrNull() }
            if (dest == null) { loading = false; error = "Address not found"; return@launch }
            mapRef?.addMarker(MarkerOptions().position(LatLng(dest.latitude(), dest.longitude())))
            loading = false
            routeTo(dest)
        }
    }

    DisposableEffect(lifecycleOwner) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onPause() }
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) { mv ->
            mv.getMapAsync { map ->
                mapRef = map
                map.setStyle(Style.Builder().fromUri(OPENFREEMAP_STYLE)) { style ->
                    if (hasLocation) enableLocation(map, style, context)
                    if (navRoute == null) navRoute = NavigationMapRoute(mv, map)
                    map.addOnMapClickListener { latLng ->
                        clearRoute()
                        mapRef?.addMarker(MarkerOptions().position(latLng))
                        routeTo(Point.fromLngLat(latLng.longitude, latLng.latitude))
                        true
                    }
                }
            }
        }

        // Destination search bar.
        Surface(
            color = OverlayBg,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp)
        ) {
            Row(modifier = Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Where to?", color = Color(0xFF9AA0A6)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Color(0xFF2A2D33),
                        cursorColor = Accent
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchAndRoute() })
                )
                Spacer(Modifier.width(6.dp))
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Accent, strokeWidth = 3.dp)
                } else {
                    IconButton(onClick = { searchAndRoute() }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = Accent)
                    }
                }
                if (route != null) {
                    IconButton(onClick = { clearRoute() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear route", tint = Color(0xFF9AA0A6))
                    }
                }
            }
        }

        // Route info + Start (fullscreen turn-by-turn) / error.
        val currentInfo = info
        val currentError = error
        if (currentInfo != null || currentError != null) {
            Surface(
                color = OverlayBg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = currentInfo ?: currentError ?: "",
                        color = if (currentError != null) Color(0xFFF28B82) else Color.White
                    )
                    if (currentInfo != null && route != null) {
                        Button(
                            onClick = {
                                val activity = context.findActivity()
                                val r = route
                                if (activity != null && r != null) {
                                    val options = NavigationLauncherOptions.builder()
                                        .directionsRoute(r)
                                        .shouldSimulateRoute(false)
                                        .build()
                                    NavigationLauncher.startNavigation(activity, options)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF0B0C0F))
                        ) {
                            Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start")
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun enableLocation(map: MapLibreMap, style: Style, context: Context) {
    if (!hasLocationPerm(context)) return
    runCatching {
        val lc = map.locationComponent
        lc.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style).build()
        )
        lc.isLocationComponentEnabled = true
        lc.cameraMode = CameraMode.TRACKING_GPS_NORTH
        lc.renderMode = RenderMode.NORMAL
    }
}

private fun hasLocationPerm(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Geocodes a free-text address to a point via Nominatim (free, no key). */
private fun geocode(query: String): Point? {
    val url = "$NOMINATIM_URL?format=json&limit=1&q=" + URLEncoder.encode(query, "UTF-8")
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    OkHttpClient().newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) return null
        val body = resp.body?.string() ?: return null
        val arr = JsonParser.parseString(body).asJsonArray
        if (arr.size() == 0) return null
        val o = arr[0].asJsonObject
        val lat = o.get("lat").asString.toDouble()
        val lon = o.get("lon").asString.toDouble()
        return Point.fromLngLat(lon, lat)
    }
}

/** Fetches a driving route from a free Valhalla server (OSRM-format response). */
private fun valhallaRoute(origin: Point, dest: Point, language: String): DirectionsResponse {
    val body = mapOf(
        "format" to "osrm",
        "costing" to "auto",
        "banner_instructions" to true,
        "voice_instructions" to true,
        "language" to language,
        "directions_options" to mapOf("units" to "kilometers"),
        "locations" to listOf(
            mapOf("lon" to origin.longitude(), "lat" to origin.latitude(), "type" to "break"),
            mapOf("lon" to dest.longitude(), "lat" to dest.latitude(), "type" to "break")
        )
    )
    val json = Gson().toJson(body)
    val request = Request.Builder()
        .header("User-Agent", USER_AGENT)
        .url(VALHALLA_URL)
        .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    OkHttpClient().newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) error("Routing failed (${resp.code})")
        val rb = resp.body?.string() ?: error("Empty routing response")
        return DirectionsResponse.fromJson(rb)
    }
}

private fun formatEta(distanceMeters: Double?, durationSeconds: Double?): String {
    val km = (distanceMeters ?: 0.0) / 1000.0
    val mins = ((durationSeconds ?: 0.0) / 60.0).toInt()
    val dist = if (km >= 10) "%.0f km".format(km) else "%.1f km".format(km)
    val time = if (mins >= 60) "%dh %02dmin".format(mins / 60, mins % 60) else "$mins min"
    return "$dist · $time"
}
