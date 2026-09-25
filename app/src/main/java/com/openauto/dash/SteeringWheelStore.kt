package com.openauto.dash

import android.content.Context
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Identifies one physical button. Most remotes send a standard Android
 * [keyCode]; the odd steering wheel wired into a cheap head unit sends a raw
 * HID [scanCode] that Android can't name (`keyCode == KEYCODE_UNKNOWN`) — [id]
 * falls back to that so those buttons can still be told apart and learned.
 */
internal data class WheelKey(val keyCode: Int, val scanCode: Int) {
    val id: Int get() = if (keyCode != KeyEvent.KEYCODE_UNKNOWN) keyCode else -(scanCode + 1)

    /** A readable name: "Media Next", "Volume Up"... or "Button #305" for a raw, unnamed code. */
    val label: String
        get() {
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return "Button #$scanCode"
            val raw = runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrDefault("KEYCODE_$keyCode")
            return raw.removePrefix("KEYCODE_")
                .split("_")
                .filter { it.isNotEmpty() }
                .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
        }
}

internal data class WheelMapping(val key: WheelKey, val assignment: WheelAssignment)

/**
 * Learned steering wheel buttons: what each one is bound to, persisted as
 * JSON, plus the small handshake with [MainActivity.dispatchKeyEvent] that
 * lets the "press a button" screen (SteeringWheelDialog.kt) capture the next
 * key instead of it running whatever it's already bound to (or, unbound,
 * falling through to Android's own handling).
 */
internal object SteeringWheelStore {
    private const val PREFS = "steering_wheel"
    private const val KEY_MAPPINGS = "mappings"

    private var appContext: Context? = null

    private val _mappings = MutableStateFlow<List<WheelMapping>>(emptyList())
    val mappings: StateFlow<List<WheelMapping>> = _mappings.asStateFlow()

    /** True while the learning screen wants the very next key, instead of running its mapped action. */
    val listening = MutableStateFlow(false)

    /** The key just seen while [listening] was on; the screen consumes it via [consumeCaptured]. */
    val captured = MutableStateFlow<WheelKey?>(null)

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _mappings.value = load(appContext!!)
    }

    fun startListening() {
        captured.value = null
        listening.value = true
    }

    fun stopListening() {
        listening.value = false
    }

    fun consumeCaptured() {
        captured.value = null
    }

    fun assign(key: WheelKey, assignment: WheelAssignment) {
        val updated = _mappings.value.filterNot { it.key.id == key.id } + WheelMapping(key, assignment)
        persist(updated)
    }

    fun remove(key: WheelKey) {
        persist(_mappings.value.filterNot { it.key.id == key.id })
    }

    private fun persist(updated: List<WheelMapping>) {
        _mappings.value = updated
        appContext?.let { save(it, updated) }
    }

    /**
     * Every hardware key reaches here first, from [MainActivity.dispatchKeyEvent].
     * True means "handled" — swallow it: either it was captured for the
     * learning screen, or it just ran the action it's bound to. False leaves
     * the key to Android's own handling (volume UI, back, an unmapped media
     * button...).
     */
    fun onKeyEvent(context: Context, event: KeyEvent): Boolean {
        val key = WheelKey(event.keyCode, event.scanCode)
        if (listening.value) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                listening.value = false
                captured.value = key
            }
            return true
        }
        val mapping = _mappings.value.firstOrNull { it.key.id == key.id } ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (val a = mapping.assignment) {
                is WheelAssignment.Preset -> a.action.run(context)
                is WheelAssignment.LaunchApp -> AppLauncher.launch(context, a.packageName)
            }
        }
        return true
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
        return when (val a = assignment) {
            is WheelAssignment.Preset -> obj.put("t", "preset").put("a", a.action.name)
            is WheelAssignment.LaunchApp -> obj.put("t", "app").put("pkg", a.packageName).put("label", a.appLabel)
        }
    }

    private fun JSONObject.toMapping(): WheelMapping? {
        val key = WheelKey(optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN), optInt("scanCode", 0))
        val assignment = when (optString("t")) {
            "preset" -> runCatching { SteeringWheelAction.valueOf(optString("a")) }.getOrNull()?.let { WheelAssignment.Preset(it) }
            "app" -> optString("pkg").takeIf { it.isNotBlank() }?.let { WheelAssignment.LaunchApp(it, optString("label")) }
            else -> null
        } ?: return null
        return WheelMapping(key, assignment)
    }
}
