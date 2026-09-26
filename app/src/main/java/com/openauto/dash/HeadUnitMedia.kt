package com.openauto.dash

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet

/*
 * The head unit's own players on the QF firmware (ROCO K706), which publish
 * a media session with nothing in it: Bluetooth music (com.qf.bluetooth) and
 * the FM/AM radio (com.android.fmradio.ext). What they play comes from their
 * own broadcasts instead, and their buttons from their own control actions
 * (worked out from the firmware). [CarMediaController] shows it in the media
 * tile whenever one of them is the source.
 *
 * The radio only talks while its screen exists: after it was closed, next /
 * previous / play open it again.
 */
object HeadUnitMedia {
    private const val TAG = "HeadUnitMedia"
    const val BT_PACKAGE = "com.qf.bluetooth"
    const val RADIO_PACKAGE = "com.android.fmradio.ext"
    private const val RADIO_ACTIVITY = "com.android.fmradio.FmMainActivity"

    private const val ACTION_BT_INFO = "com.qf.action.BT.MUSIC.INFO"
    private const val ACTION_BT_CONTROL = "com.qf.action.BT.MUSIC.CONTROL"
    private const val BT_PREVIOUS = 1
    private const val BT_NEXT = 2
    private const val BT_TOGGLE = 3

    private const val ACTION_RADIO = "com.qf.radio.update_action"
    private const val RADIO_NEXT = "/customize/radio/next"
    private const val RADIO_PREVIOUS = "/customize/radio/pre"
    private const val RADIO_CLOSE = "/customize/radio/close"

    data class BtTrack(val title: String, val artist: String, val album: String)

    /** [freq] in 10 kHz on FM (8750 = 87.50 MHz), kHz on AM; [band] 0..2 = FM1..3, 3..5 = AM1..3. */
    data class RadioStation(val freq: Int, val band: Int, val name: String?)

    @Volatile var bt: BtTrack? = null
        private set
    @Volatile var radio: RadioStation? = null
        private set

    private var started = false
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun start(context: Context) {
        if (started) return
        val app = context.applicationContext
        if (!isPackageInstalled(app, BT_PACKAGE) && !isPackageInstalled(app, RADIO_PACKAGE)) return
        started = true
        val filter = IntentFilter().apply {
            addAction(ACTION_BT_INFO)
            addAction(ACTION_RADIO)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Told (main thread) whenever a track or station changes. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun isStock(pkg: String?): Boolean = pkg == BT_PACKAGE || pkg?.startsWith("com.android.fmradio") == true

    /** The head unit's current audio source (a package name), as its framework records it. */
    fun source(): String? = systemProperty("persist.sys.qf.last_audio_src")?.takeIf { it.isNotBlank() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_BT_INFO -> bt = BtTrack(
                    intent.getStringExtra("songName").orEmpty().trim(),
                    intent.getStringExtra("songSinger").orEmpty().trim(),
                    intent.getStringExtra("songAlbum").orEmpty().trim()
                )
                ACTION_RADIO -> {
                    val freq = intent.getIntExtra("${ACTION_RADIO}_freq_key", -1)
                    if (freq <= 0) return
                    radio = RadioStation(
                        freq = freq,
                        band = intent.getIntExtra("${ACTION_RADIO}_band_key", 0),
                        name = intent.getStringExtra("${ACTION_RADIO}_name_key")?.trim()?.takeIf { it.isNotEmpty() }
                    )
                }
                else -> return
            }
            listeners.forEach { runCatching { it() }.onFailure { e -> Log.w(TAG, "listener failed", e) } }
        }
    }

    /** What [pkg] (one of the head unit's players) is playing, for the media tile; null when it said nothing yet. */
    fun state(pkg: String): MediaState? = when {
        pkg == BT_PACKAGE -> bt?.takeIf { it.title.isNotEmpty() }?.let { t ->
            MediaState(
                title = t.title,
                artist = listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" · "),
                isPlaying = systemProperty("sys.qf.bt.music.state") == "1",
                hasMedia = true,
                durationMs = btProgress()?.second?.toLong() ?: 0L
            )
        }
        isStock(pkg) -> radio?.let { r ->
            val frequency = radioLabel(r)
            MediaState(
                title = r.name ?: frequency,
                artist = listOfNotNull(r.name?.let { frequency }, bandLabel(r.band)).joinToString(" · "),
                isPlaying = systemProperty("sys.qf.radio.status") == "true",
                hasMedia = true
            )
        }
        else -> null
    }

    /** Bluetooth music's position in ms, when it reports one. */
    fun positionMs(pkg: String): Long? = if (pkg == BT_PACKAGE) btProgress()?.first?.toLong() else null

    fun playPause(context: Context, pkg: String) {
        if (pkg == BT_PACKAGE) return btCommand(context, BT_TOGGLE)
        // The radio has no pause: closing its screen stops it, opening it plays.
        if (radioAlive()) context.sendBroadcast(Intent(RADIO_CLOSE)) else openRadio(context)
    }

    fun next(context: Context, pkg: String) {
        if (pkg == BT_PACKAGE) return btCommand(context, BT_NEXT)
        if (radioAlive()) context.sendBroadcast(Intent(RADIO_NEXT)) else openRadio(context)
    }

    fun previous(context: Context, pkg: String) {
        if (pkg == BT_PACKAGE) return btCommand(context, BT_PREVIOUS)
        if (radioAlive()) context.sendBroadcast(Intent(RADIO_PREVIOUS)) else openRadio(context)
    }

    private fun btCommand(context: Context, command: Int) {
        context.sendBroadcast(Intent(ACTION_BT_CONTROL).putExtra("command", command))
    }

    private fun radioAlive(): Boolean = systemProperty("sys.qf.radio.status") == "true"

    private fun openRadio(context: Context) {
        context.launchSafely(Intent().setComponent(ComponentName(RADIO_PACKAGE, RADIO_ACTIVITY)))
    }

    /** "position,duration" as the Bluetooth app records it. */
    private fun btProgress(): Pair<Int, Int>? = systemProperty("sys.qf.bt.music.progress")
        ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        ?.takeIf { it.size == 2 && it[1] > 0 }
        ?.let { it[0] to it[1] }

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrNull()
}

/** "98.5 MHz" / "1008 kHz". */
internal fun radioLabel(r: HeadUnitMedia.RadioStation): String =
    if (r.band in 0..2) "${(r.freq / 100.0).toBigDecimal().stripTrailingZeros().toPlainString()} MHz" else "${r.freq} kHz"

/** "FM1".."FM3", "AM1".."AM3". */
internal fun bandLabel(band: Int): String? = when (band) {
    in 0..2 -> "FM${band + 1}"
    in 3..5 -> "AM${band - 2}"
    else -> null
}
