@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Information and convenience widgets: clock, weather, calendar, quick dial,
 * notifications and audio volume.
 */

// --- Permission helper ------------------------------------------------------------

internal class PermissionState(val granted: Boolean, val request: () -> Unit)

/** Runtime permission as state, with a launcher to ask for it. */
@Composable
internal fun rememberPermission(permission: String): PermissionState {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    return PermissionState(granted) { launcher.launch(permission) }
}

/** Centered "needs X" state with a button, used by tiles gated on a permission. */
@Composable
internal fun NeedsAccess(icon: ImageVector, title: String, action: String, onAction: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val button: @Composable () -> Unit = {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                shape = DashShape.Medium,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) { Text(action, maxLines = 1) }
        }
        if (maxHeight < 110.dp) {
            // Short tile: one line, icon + text + button.
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    title, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                button()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(4.dp))
                Text(title, color = DashColors.TextSecondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                button()
            }
        }
    }
}

// --- Clock ----------------------------------------------------------------------

/** Time and date, big. Tap opens the clock / alarms app. */
@Composable
internal fun ClockCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    val locale = Locale.getDefault()
    val timeFmt = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    val dateFmt = remember(locale) { SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale) }
    val secFmt = remember(locale) { SimpleDateFormat("ss", locale) }
    val time = timeFmt.format(now)
    val date = dateFmt.format(now)
    val seconds = secFmt.format(now)

    Card(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    runCatching {
                        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                .padding(DashSpace.Lg)
        ) {
            val numSize = min(maxWidth.value * 0.28f, maxHeight.value * 0.55f).coerceIn(36f, 120f).roundToInt()
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    HeroNumber(text = time, size = numSize)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        seconds,
                        color = DashColors.TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = (numSize * 0.16f).dp)
                    )
                }
                Text(
                    date.replaceFirstChar { it.uppercase() },
                    color = DashColors.TextSecondary,
                    letterSpacing = 1.sp,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// --- Weather --------------------------------------------------------------------

internal fun weatherIcon(code: Int): ImageVector = when (code) {
    0, 1 -> Icons.Filled.WbSunny
    2 -> Icons.Filled.WbCloudy
    3 -> Icons.Filled.Cloud
    45, 48 -> Icons.Filled.Dehaze
    in 51..67, in 80..82 -> Icons.Filled.Grain
    in 71..77, 85, 86 -> Icons.Filled.AcUnit
    95, 96, 99 -> Icons.Filled.FlashOn
    else -> Icons.Filled.Cloud
}

/** Current conditions at the car's position (Open-Meteo, refreshed every 15 min). */
@Composable
internal fun WeatherCard(modifier: Modifier = Modifier) {
    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val weather by WeatherRepo.weather.collectAsState()
    val error by WeatherRepo.error.collectAsState()
    LaunchedEffect(location?.latitude?.let { (it * 20).roundToInt() }, location?.longitude?.let { (it * 20).roundToInt() }) {
        val l = location ?: return@LaunchedEffect
        while (true) {
            WeatherRepo.refresh(l.latitude, l.longitude)
            delay(60_000)
        }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_weather_title)) {
                val l = location
                if (l != null) {
                    var busy by remember { mutableStateOf(false) }
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    val refreshLabel = stringResource(R.string.info_weather_refresh)
                    IconButton(
                        onClick = { scope.launch { busy = true; WeatherRepo.refresh(l.latitude, l.longitude, force = true); busy = false } },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = refreshLabel, tint = if (busy) DashColors.Accent else DashColors.Muted, modifier = Modifier.size(20.dp))
                    }
                }
            }
            val w = weather
            when {
                w != null -> Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(weatherIcon(w.code), contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            HeroNumber(text = w.tempC.roundToInt().toString(), size = 44)
                            Spacer(Modifier.width(4.dp))
                            Text("°C", color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Text(w.condition, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (!w.hiC.isNaN()) {
                                stringResource(
                                    R.string.info_weather_details_range, w.feelsC.roundToInt(), w.windKmh.roundToInt(),
                                    w.loC.roundToInt(), w.hiC.roundToInt()
                                )
                            } else {
                                stringResource(R.string.info_weather_details, w.feelsC.roundToInt(), w.windKmh.roundToInt())
                            },
                            color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                location == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.info_waiting_gps), color = DashColors.Muted)
                }
                else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val err = error
                    Text(
                        when {
                            err == null -> stringResource(R.string.info_weather_loading)
                            err.isBlank() -> stringResource(R.string.info_weather_unavailable)
                            else -> stringResource(R.string.info_weather_unavailable_detail, err)
                        },
                        color = DashColors.Muted, textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// --- Calendar ---------------------------------------------------------------------

internal data class AgendaEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String)

internal fun loadAgenda(context: Context, hours: Int = 36): List<AgendaEvent> {
    val now = System.currentTimeMillis()
    val until = now + hours * 3_600_000L
    val uri = ContentUris.appendId(ContentUris.appendId(CalendarContract.Instances.CONTENT_URI.buildUpon(), now), until).build()
    val projection = arrayOf(
        CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION
    )
    val out = mutableListOf<AgendaEvent>()
    runCatching {
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 6) {
                val end = c.getLong(2)
                if (end < now) continue
                out += AgendaEvent(
                    // Blank titles get a localized "(No title)" at display time.
                    title = c.getString(0).orEmpty(),
                    begin = c.getLong(1), end = end,
                    allDay = c.getInt(3) == 1,
                    location = c.getString(4).orEmpty()
                )
            }
        }
    }
    return out
}

/** Next events from the device calendars. */
@Composable
internal fun CalendarCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val perm = rememberPermission(Manifest.permission.READ_CALENDAR)
    var events by remember { mutableStateOf<List<AgendaEvent>>(emptyList()) }
    LaunchedEffect(perm.granted) {
        if (!perm.granted) return@LaunchedEffect
        while (true) {
            events = withContext(Dispatchers.IO) { loadAgenda(context) }
            delay(5 * 60_000)
        }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_agenda_title)) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) { Text(stringResource(R.string.info_open), color = DashColors.Accent, style = MaterialTheme.typography.labelMedium) }
            }
            when {
                !perm.granted -> NeedsAccess(
                    Icons.Filled.Event, stringResource(R.string.info_agenda_needs_access),
                    stringResource(R.string.info_agenda_allow), perm.request
                )
                events.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.info_agenda_empty), color = DashColors.Muted)
                }
                else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(events) { e -> AgendaRow(e) }
                }
            }
        }
    }
}

@Composable
private fun AgendaRow(e: AgendaEvent) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dayFmt = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
    val today = remember(e.begin) {
        val a = java.util.Calendar.getInstance(); val b = java.util.Calendar.getInstance().apply { timeInMillis = e.begin }
        a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR) && a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
    }
    val ongoing = e.begin <= System.currentTimeMillis()
    val noTitle = stringResource(R.string.info_agenda_no_title)
    val allDay = stringResource(R.string.info_agenda_all_day)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DashShape.Small)
            .itemFill(if (DashColors.Glass) DashColors.haze(0.06f) else DashColors.CardHi, DashShape.Small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(30.dp)
                .clip(CircleShape)
                .background(if (ongoing) DashColors.Good else DashColors.Accent)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(e.title.ifBlank { noTitle }, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    if (!today) append(dayFmt.format(Date(e.begin))).append(" ")
                    append(if (e.allDay) allDay else "${timeFmt.format(Date(e.begin))} – ${timeFmt.format(Date(e.end))}")
                    if (e.location.isNotBlank()) append(" · ").append(e.location)
                },
                color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// --- Quick dial -------------------------------------------------------------------

internal data class Favourite(val name: String, val number: String?, val photo: Bitmap?)

internal fun loadFavourites(context: Context, limit: Int = 8): List<Favourite> {
    val cr = context.contentResolver
    val out = mutableListOf<Favourite>()
    runCatching {
        cr.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
            "${ContactsContract.Contacts.STARRED} = 1 AND ${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
            null,
            "${ContactsContract.Contacts.TIMES_CONTACTED} DESC"
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val number = cr.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null
                )?.use { p -> if (p.moveToFirst()) p.getString(0) else null }
                val photo = c.getString(2)?.let { u ->
                    runCatching { cr.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
                }
                out += Favourite(name, number, photo)
            }
        }
    }
    return out
}

/** Starred contacts as big tap-to-call targets. Tapping opens the dialer. */
@Composable
internal fun QuickDialCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val perm = rememberPermission(Manifest.permission.READ_CONTACTS)
    var favourites by remember { mutableStateOf<List<Favourite>>(emptyList()) }
    LaunchedEffect(perm.granted) {
        if (perm.granted) favourites = withContext(Dispatchers.IO) { loadFavourites(context) }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_quickdial_title)) {
                TextButton(
                    onClick = { context.launchSafely(Intent(Intent.ACTION_DIAL)) },
                    modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) { Text(stringResource(R.string.info_quickdial_dialer), color = DashColors.Accent, style = MaterialTheme.typography.labelMedium) }
            }
            when {
                !perm.granted -> NeedsAccess(
                    Icons.Filled.Call, stringResource(R.string.info_quickdial_needs_access),
                    stringResource(R.string.info_quickdial_allow), perm.request
                )
                favourites.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.info_quickdial_empty), color = DashColors.Muted, textAlign = TextAlign.Center)
                }
                else -> BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val avatar = min(maxHeight.value * 0.55f, 64f).coerceAtLeast(36f).dp
                    Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        favourites.forEach { f ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(DashShape.Medium)
                                    .clickable(enabled = f.number != null) {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${f.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(avatar)
                                        .clip(CircleShape)
                                        .background(DashColors.AccentBrush)
                                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val bmp = f.photo
                                    if (bmp != null) {
                                        Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    } else {
                                        Text(
                                            f.name.split(' ').take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString(""),
                                            color = DashColors.OnAccent, fontWeight = FontWeight.ExtraBold,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(f.name.substringBefore(' '), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Notifications ----------------------------------------------------------------

/**
 * Recent notifications from other apps; tap one to open it. The driver's
 * phone's ones (over [PhoneLink]) open a sheet to hear and answer them.
 */
@Composable
internal fun NotificationsCard(hasAccess: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items by NotificationFeed.items.collectAsState()
    val phoneConnected = PhoneLink.state.collectAsState().value is PhoneLinkState.Connected
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var opened by remember { mutableStateOf<NotifItem?>(null) }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_notif_title)) {
                if (items.isNotEmpty()) {
                    TextButton(onClick = { NotificationFeed.dismissAll() }, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                        Text(stringResource(R.string.info_clear), color = DashColors.Muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            when {
                !hasAccess && !phoneConnected && items.none { it.fromPhone } -> NeedsAccess(
                    Icons.Filled.Notifications, stringResource(R.string.info_notif_needs_access),
                    stringResource(R.string.info_grant_access)
                ) {
                    CarMediaController.openNotificationAccessSettings(context)
                }
                items.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.info_notif_empty), color = DashColors.Muted)
                }
                else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.key }) { n ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(DashShape.Small)
                                .itemFill(if (DashColors.Glass) DashColors.haze(0.06f) else DashColors.CardHi, DashShape.Small)
                                .clickable { if (n.fromPhone) opened = n else runCatching { n.contentIntent?.send() } }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val bmp = n.icon
                            if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)))
                            else Icon(Icons.Filled.Notifications, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(n.title.ifEmpty { n.appLabel }, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                if (n.text.isNotEmpty()) Text(n.text, color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(timeFmt.format(Date(n.postedAt)), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (n.fromPhone) {
                                        Icon(
                                            Icons.Filled.PhoneAndroid, contentDescription = stringResource(R.string.phone_from_phone),
                                            tint = DashColors.Accent, modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                    }
                                    Text(n.appLabel, color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    opened?.let { item ->
        // Follows the live item (new lines of the conversation); closes once it's gone from the phone.
        val live = items.firstOrNull { it.key == item.key }
        LaunchedEffect(live == null) { if (live == null) opened = null }
        if (live != null) PhoneMessageSheet(live, onDismiss = { opened = null })
    }
}

// --- Audio ------------------------------------------------------------------------

/** Media volume with mute, plus shortcuts to the sound and Bluetooth settings. */
@Composable
internal fun AudioCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var dragging by remember { mutableStateOf(false) }
    // Follow the hardware knob / other apps while nobody is dragging the slider.
    // The system broadcasts VOLUME_CHANGED_ACTION on every change; a slow poll
    // remains as a fallback for ROMs that don't send it.
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!dragging) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        val filter = android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        runCatching { androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            if (!dragging) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }
    val muted = volume == 0

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.info_audio_title)) {
                Text("${(volume * 100f / max).roundToInt()}%", color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE, 0)
                        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, contentDescription = stringResource(if (muted) R.string.info_audio_unmute else R.string.info_audio_mute),
                        tint = if (muted) DashColors.Warning else DashColors.TextPrimary, modifier = Modifier.size(26.dp))
                }
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { v ->
                        dragging = true
                        volume = v.roundToInt()
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                    },
                    onValueChangeFinished = { dragging = false },
                    valueRange = 0f..max.toFloat(),
                    steps = (max - 1).coerceAtLeast(0),
                    colors = SliderDefaults.colors(
                        thumbColor = if (DashColors.Light) DashColors.Accent else Color.White, activeTrackColor = DashColors.Accent,
                        inactiveTrackColor = if (DashColors.Glass) DashColors.well(0.35f) else DashColors.CardHi,
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(stringResource(R.string.info_audio_sound)) { context.launchSafely(Intent(Settings.ACTION_SOUND_SETTINGS)) }
                SmallAction("Bluetooth", Icons.Filled.Bluetooth) { context.launchSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = DashShape.Pill
    Row(
        modifier = Modifier
            .clip(shape)
            .itemFill(if (DashColors.Glass) DashColors.haze(0.08f) else DashColors.CardHi, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em, style = MaterialTheme.typography.labelMedium)
    }
}
