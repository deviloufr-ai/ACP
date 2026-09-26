package com.openauto.dash

import android.content.Context
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Identifies one physical button, seen one of three ways:
 * - a standard Android [keyCode] (most remotes);
 * - a raw HID [scanCode] Android can't name (`keyCode == KEYCODE_UNKNOWN`);
 * - a CAN frame from the car ([canKey], a [McuReader] key) taking the value
 *   [canHex] while the button is held. On MCU head units (the ROCO K706) the
 *   wheel buttons never become Android keys at all; they only show up here.
 */
internal data class WheelKey(
    val keyCode: Int,
    val scanCode: Int,
    val canKey: String? = null,
    val canHex: String? = null
) {
    val id: String
        get() = when {
            canKey != null -> "c:$canKey=$canHex"
            keyCode != KeyEvent.KEYCODE_UNKNOWN -> "k$keyCode"
            else -> "s$scanCode"
        }

    /** A readable name: "Media Next", "Volume Up"... The dialog words the raw and CAN ones in the chosen language. */
    val label: String
        get() {
            if (canKey != null) return "CAN $canKey: $canHex"
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return "Button #$scanCode"
            val raw = runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrDefault("KEYCODE_$keyCode")
            return raw.removePrefix("KEYCODE_")
                .split("_")
                .filter { it.isNotEmpty() }
                .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
        }

    companion object {
        fun can(key: String, hex: String) = WheelKey(KeyEvent.KEYCODE_UNKNOWN, 0, key, hex)
    }
}

internal data class WheelMapping(val key: WheelKey, val assignment: WheelAssignment)

/**
 * Tells a steering wheel button apart from the rest of the CAN stream: a frame
 * that had been still for [quietMs] takes a new value, then goes back to the
 * old one within [releaseMs]: pressed, then let go. Speed, revs and the like
 * change too often to be still; fuel or temperature drift and never come back
 * to the exact old value. Pure, for the tests.
 */
internal class CanButtonDetector(private val quietMs: Long = 1_500L, private val releaseMs: Long = 5_000L) {
    private data class Pending(val idle: String, val pressed: String, val at: Long)

    private val lastChange = HashMap<String, Long>()
    // Per frame: other frames keep ticking while a button is held.
    private val pending = HashMap<String, Pending>()

    fun reset() {
        pending.clear()
    }

    /** Feeds one change; returns the pressed value once its frame [key] has gone back to idle. */
    fun onChange(key: String, previousHex: String?, hex: String, at: Long): String? {
        val stillFor = lastChange[key]?.let { at - it } ?: Long.MAX_VALUE
        lastChange[key] = at
        val p = pending.remove(key)
        if (p != null) return if (hex == p.idle && at - p.at <= releaseMs) p.pressed else null
        if (previousHex != null && stillFor >= quietMs) pending[key] = Pending(previousHex, hex, at)
        return null
    }
}

/**
 * Learned steering wheel buttons: what each one is bound to, persisted as
 * JSON, and the two ways a press arrives: an Android key through
 * [MainActivity.dispatchKeyEvent] (or the learning dialog's own window), and
 * a CAN frame through [McuReader.changes], which works whatever app is in
 * front. While the "press a button" screen (SteeringWheelDialog.kt) is
 * [listening], the next press is captured instead of running what it's bound to.
 */
internal object SteeringWheelStore {
    private const val PREFS = "steering_wheel"
    private const val KEY_MAPPINGS = "mappings"

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val detector = CanButtonDetector()
    /** This store holds a [McuReader.start]: while listening, or while a CAN button is bound. */
    private var holdsReader = false

    private val _mappings = MutableStateFlow<List<WheelMapping>>(emptyList())
    val mappings: StateFlow<List<WheelMapping>> = _mappings.asStateFlow()

    /** True while the learning screen wants the very next press, instead of running its mapped action. */
    val listening = MutableStateFlow(false)

    /** The key just seen while [listening] was on; the screen consumes it via [consumeCaptured]. */
    val captured = MutableStateFlow<WheelKey?>(null)

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _mappings.value = load(appContext!!)
        scope.launch { McuReader.changes.collect { onCanChange(it) } }
        updateReader()
    }

    fun startListening() {
        captured.value = null
        detector.reset()
        listening.value = true
        updateReader()
    }

    fun stopListening() {
        listening.value = false
        updateReader()
    }

    fun consumeCaptured() {
        captured.value = null
    }

    fun assign(key: WheelKey, assignment: WheelAssignment) {
        persist(_mappings.value.filterNot { it.key.id == key.id } + WheelMapping(key, assignment))
    }

    fun remove(key: WheelKey) {
        persist(_mappings.value.filterNot { it.key.id == key.id })
    }

    private fun persist(updated: List<WheelMapping>) {
        _mappings.value = updated
        appContext?.let { save(it, updated) }
        updateReader()
    }

    private fun capture(key: WheelKey) {
        listening.value = false
        captured.value = key
        updateReader()
    }

    /** The CAN stream is read (root logcat) only while it can be needed. */
    private fun updateReader() {
        val wanted = listening.value || _mappings.value.any { it.key.canKey != null }
        if (wanted == holdsReader) return
        holdsReader = wanted
        if (wanted) McuReader.start() else McuReader.stop()
    }

    /**
     * Every hardware key the launcher's windows get reaches here first.
     * True means "handled" — swallow it: either it was captured for the
     * learning screen, or it just ran the action it's bound to. False leaves
     * the key to Android's own handling (volume UI, back, an unmapped media
     * button...).
     */
    fun onKeyEvent(context: Context, event: KeyEvent): Boolean {
        val key = WheelKey(event.keyCode, event.scanCode)
        if (listening.value) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) capture(key)
            return true
        }
        val mapping = _mappings.value.firstOrNull { it.key.id == key.id } ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) perform(context, mapping.assignment)
        return true
    }

    private fun onCanChange(change: McuReader.Change) {
        val e = change.entry
        val pressed = detector.onChange(e.key, change.previousHex, e.hex, e.changedAt)
        if (listening.value) {
            if (pressed != null) capture(WheelKey.can(e.key, pressed))
            return
        }
        val context = appContext ?: return
        val mapping = _mappings.value.firstOrNull { it.key.canKey == e.key && it.key.canHex == e.hex } ?: return
        perform(context, mapping.assignment)
    }

    private fun perform(context: Context, assignment: WheelAssignment) {
        when (assignment) {
            is WheelAssignment.Preset -> assignment.action.run(context)
            is WheelAssignment.LaunchApp -> AppLauncher.launch(context, assignment.packageName)
        }
    }

    private fun load(context: Context): List<WheelMapping> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MAPPINGS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toMapping() }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, mappings: List<WheelMapping>) {
        val arr = JSONArray()
        mappings.forEach { arr.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MAPPINGS, arr.toString()).apply()
    }

    private fun WheelMapping.toJson(): JSONObject {
        val obj = JSONObject().put("keyCode", key.keyCode).put("scanCode", key.scanCode)
        if (key.canKey != null) obj.put("canKey", key.canKey).put("canHex", key.canHex)
        return when (val a = assignment) {
            is WheelAssignment.Preset -> obj.put("t", "preset").put("a", a.action.name)
            is WheelAssignment.LaunchApp -> obj.put("t", "app").put("pkg", a.packageName).put("label", a.appLabel)
        }
    }

    private fun JSONObject.toMapping(): WheelMapping? {
        val canKey = if (has("canKey")) optString("canKey") else null
        val canHex = if (has("canHex")) optString("canHex") else null
        val key = if (canKey != null && canHex != null) WheelKey.can(canKey, canHex)
        else WheelKey(optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN), optInt("scanCode", 0))
        val assignment = when (optString("t")) {
            "preset" -> runCatching { SteeringWheelAction.valueOf(optString("a")) }.getOrNull()?.let { WheelAssignment.Preset(it) }
            "app" -> optString("pkg").takeIf { it.isNotBlank() }?.let { WheelAssignment.LaunchApp(it, optString("label")) }
            else -> null
        } ?: return null
        return WheelMapping(key, assignment)
    }
}
