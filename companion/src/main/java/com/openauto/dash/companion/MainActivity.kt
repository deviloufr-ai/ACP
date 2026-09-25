package com.openauto.dash.companion

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.openauto.dash.link.PairingOffer
import java.text.DateFormat
import java.util.Date

/**
 * The companion's only screen: is the car connected, what still needs
 * allowing, and which cars are paired. Pairing is scanning the code the
 * launcher shows (or opening its dashwheel://pair link), then confirming.
 */
/** The companion's own download, which the launcher's pairing screen also shows as a QR code. */
private const val COMPANION_APK = "dashwheel-companion.apk"

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so the checklist re-reads what the driver just allowed. */
    private var resumes by mutableIntStateOf(0)
    private var offer by mutableStateOf<PairingOffer?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PairedUnits.load(this)
        takeOffer(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF5B8DEF), secondary = Color(0xFF2DD4BF))) {
                CompanionScreen(
                    resumes = resumes,
                    offer = offer,
                    onScanned = { text ->
                        val parsed = PairingOffer.parse(text)
                        if (parsed == null) explainWrongCode(text)
                        offer = parsed
                    },
                    onOfferDone = { offer = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOffer(intent)
    }

    override fun onResume() {
        super.onResume()
        resumes++
        LinkService.sync(this)
    }

    /** The launcher shows two codes; the download one is the easy one to scan by mistake. */
    private fun explainWrongCode(text: String) {
        val message = if (text.contains(COMPANION_APK, ignoreCase = true)) {
            getString(R.string.scanned_download_code)
        } else {
            getString(R.string.invalid_code_read, text.trim().take(60))
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun takeOffer(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        offer = PairingOffer.parse(data.toString())
        if (offer == null) explainWrongCode(data.toString())
        // Handled once: a rotation must not ask again.
        setIntent(Intent(this, MainActivity::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionScreen(resumes: Int, offer: PairingOffer?, onScanned: (String) -> Unit, onOfferDone: () -> Unit) {
    val context = LocalContext.current
    val units by PairedUnits.units.collectAsState()
    val state by LinkServer.state.collectAsState()
    var enabled by remember { mutableStateOf(PairedUnits.isEnabled(context)) }
    var removing by remember { mutableStateOf<PairedUnit?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(onScanned) }
    val scanPrompt = stringResource(R.string.pair_hint)

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    text = when {
                        units.isEmpty() -> stringResource(R.string.status_no_car)
                        !enabled -> stringResource(R.string.status_off)
                        state is LinkState.Connected -> stringResource(R.string.status_connected, (state as LinkState.Connected).unitName)
                        else -> stringResource(R.string.status_waiting)
                    },
                    connected = state is LinkState.Connected,
                    enabled = enabled,
                    canToggle = units.isNotEmpty(),
                    onToggle = {
                        enabled = it
                        PairedUnits.setEnabled(context, it)
                        LinkService.sync(context)
                    }
                )
            }
            item { SectionTitle(stringResource(R.string.setup_title)) }
            item { SetupSteps(resumes) }
            item { SectionTitle(stringResource(R.string.cars_title)) }
            items(units, key = { it.id }) { unit ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DirectionsCar, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(unit.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.paired_since, DateFormat.getDateInstance().format(Date(unit.pairedAt))),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { removing = unit }) { Text(stringResource(R.string.remove)) }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        scan.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt(scanPrompt)
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pair_car))
                }
                Text(scanPrompt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    if (offer != null) {
        AlertDialog(
            onDismissRequest = onOfferDone,
            icon = { Icon(Icons.Filled.DirectionsCar, contentDescription = null) },
            title = { Text(stringResource(R.string.confirm_title, offer.unitName)) },
            text = { Text(stringResource(R.string.confirm_body)) },
            confirmButton = {
                Button(onClick = {
                    PairedUnits.add(context, offer)
                    enabled = true
                    PairedUnits.setEnabled(context, true)
                    LinkService.sync(context)
                    onOfferDone()
                }) { Text(stringResource(R.string.confirm_allow)) }
            },
            dismissButton = { TextButton(onClick = onOfferDone) { Text(stringResource(R.string.cancel)) } }
        )
    }

    removing?.let { unit ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_title, unit.name)) },
            text = { Text(stringResource(R.string.remove_body)) },
            confirmButton = {
                Button(onClick = {
                    PairedUnits.remove(context, unit.id)
                    LinkService.sync(context)
                    removing = null
                }) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun StatusCard(text: String, connected: Boolean, enabled: Boolean, canToggle: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (connected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.share_toggle), fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = enabled && canToggle, onCheckedChange = onToggle, enabled = canToggle)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SetupSteps(resumes: Int) {
    val context = LocalContext.current
    // Re-read on every resume: the driver comes back from the system settings.
    val listener = remember(resumes) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    var postGranted by remember(resumes) { mutableStateOf(canPostNotifications(context)) }
    val battery = remember(resumes) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val askPost = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { postGranted = it }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Step(
            Icons.Filled.Notifications, stringResource(R.string.step_notifications),
            stringResource(R.string.step_notifications_detail), done = listener
        ) {
            open(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!listener) {
            // Android 13+ greys out Notification access for apps installed outside a store.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.step_notifications_restricted), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text(stringResource(R.string.step_app_info)) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Step(
                Icons.Filled.NotificationsActive, stringResource(R.string.step_post),
                stringResource(R.string.step_post_detail), done = postGranted
            ) {
                askPost.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        Step(
            Icons.Filled.BatteryFull, stringResource(R.string.step_battery),
            stringResource(R.string.step_battery_detail), done = battery
        ) {
            open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }
        Step(
            Icons.Filled.Wifi, stringResource(R.string.step_hotspot),
            stringResource(R.string.step_hotspot_detail), done = null
        ) {
            if (!open(context, Intent("android.settings.TETHER_SETTINGS"))) open(context, Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }
}

/** One thing to set up. [done] null: can't be checked from here, always offers to open it. */
@Composable
private fun Step(icon: ImageVector, title: String, detail: String, done: Boolean?, onFix: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            when (done) {
                true -> Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.done), tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
                false -> Button(onClick = onFix) { Text(stringResource(R.string.action_allow)) }
                null -> OutlinedButton(onClick = onFix) { Text(stringResource(R.string.step_open_settings)) }
            }
        }
    }
}

private fun canPostNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun open(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}
