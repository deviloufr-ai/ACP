package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.openauto.dash.link.ConversationLine
import com.openauto.dash.link.PhoneNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max

/*
 * Live data sources shared by the dashboard widgets. Each feed is a process
 * singleton that composables acquire / release, so one GPS or sensor
 * subscription serves every tile that needs it and stops when none do.
 */

internal fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Running totals since the last reset, fed by GPS fixes. */
data class TripState(
    val startedAt: Long = System.currentTimeMillis(),
    val distanceM: Double = 0.0,
    val movingMs: Long = 0L,
    val maxSpeedKmh: Float = 0f
) {
    val elapsedMs: Long get() = System.currentTimeMillis() - startedAt
    val avgSpeedKmh: Double get() = if (movingMs > 0L) (distanceM / 1000.0) / (movingMs / 3_600_000.0) else 0.0
}

/** GPS position plus the trip computer that accumulates from it. */
object LocationFeed {
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    private val _trip = MutableStateFlow(TripState())
    val trip: StateFlow<TripState> = _trip

    /** Last heading we trust (from GPS while moving); kept while stopped. */
    private val _headingDeg = MutableStateFlow<Float?>(null)
    val headingDeg: StateFlow<Float?> = _headingDeg

    private var refs = 0
    private var manager: LocationManager? = null
    private var lastFix: Location? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission")
    fun acquire(context: Context) {
        // Only count the ref once we really register; if permission is missing
        // the next acquire (after the grant) must be allowed to try again.
        if (!hasLocationPermission(context)) return
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return
        if (refs++ > 0) return
        manager = lm
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        }
        runCatching {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
        }
        runCatching {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { _location.value = it }
        }
    }

    fun release() {
        if (--refs > 0) return
        refs = 0
        manager?.removeUpdates(listener)
        manager = null
    }

    fun resetTrip() {
        _trip.value = TripState()
    }

    /** [DemoMode]'s position and trip (and, when it ends, the real ones back). */
    internal fun demoWrite(location: Location?, trip: TripState, heading: Float?) {
        _location.value = location
        _trip.value = trip
        _headingDeg.value = heading
    }

    private fun onFix(l: Location) {
        if (DemoMode.isOn) return
        val prev = lastFix
        _location.value = l
        val speedKmh = l.speed * 3.6f
        if (l.hasBearing() && speedKmh > 3f) _headingDeg.value = l.bearing

        // Only consecutive GPS fixes of decent accuracy count towards the trip.
        if (prev != null && l.provider == LocationManager.GPS_PROVIDER &&
            prev.provider == LocationManager.GPS_PROVIDER && l.accuracy <= 30f
        ) {
            val d = prev.distanceTo(l).toDouble()
            val dt = (l.time - prev.time).coerceIn(0L, 60_000L)
            if (d >= 2.0) {
                _trip.value = _trip.value.let { t ->
                    t.copy(
                        distanceM = t.distanceM + d,
                        movingMs = t.movingMs + if (speedKmh > 3f) dt else 0L,
                        maxSpeedKmh = max(t.maxSpeedKmh, speedKmh)
                    )
                }
            }
        }
        lastFix = l
    }
}

/** Lateral / longitudinal acceleration in g, from the head unit's accelerometer. */
data class GForce(
    val lateral: Float = 0f,
    val longitudinal: Float = 0f,
    val peakLateral: Float = 0f,
    val peakLongitudinal: Float = 0f
)

object GForceFeed : SensorEventListener {
    private val _g = MutableStateFlow(GForce())
    val g: StateFlow<GForce> = _g

    private var refs = 0
    private var manager: SensorManager? = null
    private val gravity = FloatArray(3)
    private var lat = 0f
    private var lon = 0f

    fun acquire(context: Context) {
        val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (refs++ > 0) return
        manager = sm
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun release() {
        if (--refs > 0) return
        refs = 0
        manager?.unregisterListener(this)
        manager = null
    }

    fun resetPeaks() {
        _g.value = _g.value.copy(peakLateral = 0f, peakLongitudinal = 0f)
    }

    /** [DemoMode]'s forces (and, when it ends, the real ones back). */
    internal fun demoWrite(g: GForce) {
        _g.value = g
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (DemoMode.isOn) return
        // Low-pass to isolate gravity, subtract it for linear acceleration.
        for (i in 0..2) gravity[i] = 0.9f * gravity[i] + 0.1f * event.values[i]
        val lx = event.values[0] - gravity[0]
        val lz = event.values[2] - gravity[2]
        // The unit is fixed in the dash with its screen facing the cabin: X runs
        // across the car (lateral) and Z points out of the screen towards the
        // rear, so forward acceleration shows up as -Z.
        lat = 0.7f * lat + 0.3f * (lx / SensorManager.GRAVITY_EARTH)
        lon = 0.7f * lon + 0.3f * (-lz / SensorManager.GRAVITY_EARTH)
        val cur = _g.value
        _g.value = GForce(
            lateral = lat,
            longitudinal = lon,
            peakLateral = max(cur.peakLateral, abs(lat)),
            peakLongitudinal = max(cur.peakLongitudinal, abs(lon))
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Where the car was left, so the parking widget can lead back to it. */
data class ParkingSpot(val lat: Double, val lng: Double, val savedAt: Long)

object ParkingStore {
    private const val PREFS = "parking_spot"
    private val _spot = MutableStateFlow<ParkingSpot?>(null)
    val spot: StateFlow<ParkingSpot?> = _spot

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("lat")) return
        _spot.value = ParkingSpot(
            lat = java.lang.Double.longBitsToDouble(p.getLong("lat", 0L)),
            lng = java.lang.Double.longBitsToDouble(p.getLong("lng", 0L)),
            savedAt = p.getLong("at", 0L)
        )
    }

    fun save(context: Context, location: Location) {
        val s = ParkingSpot(location.latitude, location.longitude, System.currentTimeMillis())
        _spot.value = s
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("lat", java.lang.Double.doubleToRawLongBits(s.lat))
            .putLong("lng", java.lang.Double.doubleToRawLongBits(s.lng))
            .putLong("at", s.savedAt)
            .apply()
    }

    fun clear(context: Context) {
        _spot.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/** One notification as shown by the notifications widget. */
data class NotifItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val icon: Bitmap?,
    val contentIntent: PendingIntent?,
    /** Came from the driver's phone over [PhoneLink] rather than from this head unit. */
    val fromPhone: Boolean = false,
    val canReply: Boolean = false,
    val canMarkRead: Boolean = false,
    /** The latest lines of a phone conversation, oldest first. */
    val messages: List<ConversationLine> = emptyList()
)

/**
 * Recent notifications from other apps, fed by the notification listener,
 * plus the driver's phone's while [PhoneLink] is connected.
 */
object NotificationFeed {
    private const val MAX = 20
    private val _items = MutableStateFlow<List<NotifItem>>(emptyList())
    val items: StateFlow<List<NotifItem>> = _items

    /** [DemoMode]'s notifications (and, when it ends, the real ones back). */
    internal fun demoWrite(items: List<NotifItem>) {
        _items.value = items
    }

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        if (DemoMode.isOn) return
        if (sbn.packageName == context.packageName) return
        if (sbn.isOngoing) return
        val n = sbn.notification ?: return
        val title = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
            ?: n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val (label, icon) = appIdentity(context, sbn.packageName)
        val item = NotifItem(sbn.key, sbn.packageName, label, title, text, sbn.postTime, icon, n.contentIntent)
        _items.update { items -> (listOf(item) + items.filter { it.key != sbn.key }).take(MAX) }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        if (DemoMode.isOn) return
        _items.update { items -> items.filter { it.key != sbn.key } }
    }

    // --- The phone's notifications (keys prefixed so they never clash with this head unit's) ---

    private const val PHONE = "phone:"

    /** The phone's own key for a phone item's [NotifItem.key]. */
    fun phoneKey(key: String): String = key.removePrefix(PHONE)

    /** Everything the phone shows right now, sent when the link comes up. */
    fun phoneSync(notifications: List<PhoneNotification>) {
        if (DemoMode.isOn) return
        val fromPhone = notifications.map(::fromPhone)
        _items.update { items -> (fromPhone + items.filter { !it.fromPhone }).sortedByDescending { it.postedAt }.take(MAX) }
    }

    fun phonePosted(notification: PhoneNotification) {
        if (DemoMode.isOn) return
        val item = fromPhone(notification)
        _items.update { items -> (listOf(item) + items.filter { it.key != item.key }).take(MAX) }
    }

    fun phoneRemoved(key: String) {
        _items.update { items -> items.filter { it.key != PHONE + key } }
    }

    /** The link ended: the phone's notifications are no longer current. */
    fun phoneClear() {
        _items.update { items -> items.filter { !it.fromPhone } }
    }

    private val phoneIcons = HashMap<String, Bitmap?>()

    private fun fromPhone(n: PhoneNotification): NotifItem {
        val icon = synchronized(phoneIcons) {
            phoneIcons.getOrPut(n.packageName) {
                n.iconPng?.let { png ->
                    runCatching {
                        val bytes = Base64.getDecoder().decode(png)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
            }
        }
        return NotifItem(
            key = PHONE + n.key, packageName = n.packageName, appLabel = n.appName,
            title = n.title, text = n.text, postedAt = n.postedAt, icon = icon, contentIntent = null,
            fromPhone = true, canReply = n.canReply, canMarkRead = n.canMarkRead, messages = n.messages
        )
    }

    // Label + icon rasterisation per package, done once: this runs on the
    // notification listener's main thread for every notification any app posts.
    private val identityCache = HashMap<String, Pair<String, Bitmap?>>()

    @Synchronized
    private fun appIdentity(context: Context, packageName: String): Pair<String, Bitmap?> =
        identityCache.getOrPut(packageName) {
            val pm = context.packageManager
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
                .getOrDefault(packageName)
            val icon = runCatching { pm.getApplicationIcon(packageName).toBitmap(96, 96) }.getOrNull()
            label to icon
        }

    fun dismissAll() {
        _items.value = emptyList()
    }

    /** Takes one item off the card (a phone message answered or dismissed from the car). */
    fun remove(key: String) {
        _items.update { items -> items.filter { it.key != key } }
    }
}
