package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
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
 * "open the app" page, so this uses a self-contained Leaflet + OpenStreetMap
 * map (no API key) loaded from an inline document. It centers on the vehicle's
 * GPS position via the browser geolocation API, which needs the app's location
 * permission — requested here on first display.
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
    ) { /* map recenters automatically once geolocation is allowed */ }

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
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: GeolocationPermissions.Callback?
                        ) {
                            callback?.invoke(origin, true, false)
                        }
                    }
                    loadDataWithBaseURL(
                        "https://www.openstreetmap.org/",
                        MAP_HTML,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        )
    }
}

/** Self-contained Leaflet map that tracks the device's GPS position. */
private const val MAP_HTML = """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5" />
  <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
  <style>html,body,#map{height:100%;margin:0;background:#0b0c0f}</style>
</head>
<body>
  <div id="map"></div>
  <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
  <script>
    var map = L.map('map', { zoomControl: true }).setView([48.8566, 2.3522], 13);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors'
    }).addTo(map);

    var marker = null;
    var centered = false;
    function place(lat, lng) {
      var ll = [lat, lng];
      if (marker) { marker.setLatLng(ll); } else { marker = L.marker(ll).addTo(map); }
      if (!centered) { map.setView(ll, 16); centered = true; }
    }
    if (navigator.geolocation) {
      navigator.geolocation.watchPosition(
        function (p) { place(p.coords.latitude, p.coords.longitude); },
        function (e) { /* keep default view on error */ },
        { enableHighAccuracy: true, maximumAge: 10000, timeout: 15000 }
      );
    }
  </script>
</body>
</html>
"""
