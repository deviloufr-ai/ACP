package com.openauto.dash

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Google Maps URL loaded on start; opens the standard maps surface. */
private const val MAPS_URL = "https://www.google.com/maps"

/**
 * Left panel that automatically opens Google Maps.
 *
 * Maps is embedded directly via a [WebView] (auto-loaded on first composition),
 * so navigation stays visible next to the app drawer. If the page fails to load
 * (e.g. no connection), a fallback offers to open the installed Maps app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var loadFailed by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        if (loadFailed) {
            MapsFallback(
                onOpenMapsApp = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MAPS_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            setGeolocationEnabled(true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedError(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                // Only fail on the main frame, not sub-resources.
                                if (request?.isForMainFrame == true) loadFailed = true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onGeolocationPermissionsShowPrompt(
                                origin: String?,
                                callback: GeolocationPermissions.Callback?
                            ) {
                                // App-level location permission is requested elsewhere;
                                // grant the map origin so it can center on the vehicle.
                                callback?.invoke(origin, true, false)
                            }
                        }
                        loadUrl(MAPS_URL)
                    }
                }
            )
        }
    }
}

@Composable
private fun MapsFallback(onOpenMapsApp: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Map,
            contentDescription = null,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = "Maps couldn't load",
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
        )
        Button(onClick = onOpenMapsApp) {
            Text("Open Maps app")
        }
    }
}
