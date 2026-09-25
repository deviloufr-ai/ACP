package com.openauto.dash.companion

import android.content.Context
import com.openauto.dash.link.PairingOffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** A head unit this phone agreed to share with. */
data class PairedUnit(val id: String, val name: String, val secret: ByteArray, val pairedAt: Long)

/**
 * The head units the driver allowed, with the secret each one proves on every
 * connection. Kept in private preferences (the app opts out of backups).
 */
object PairedUnits {
    private const val PREFS = "paired_units"
    private const val KEY = "units"
    private const val KEY_ENABLED = "enabled"

    private val _units = MutableStateFlow<List<PairedUnit>>(emptyList())
    val units: StateFlow<List<PairedUnit>> = _units
    private var loaded = false

    @Synchronized
    fun load(context: Context): List<PairedUnit> {
        if (!loaded) {
            _units.value = read(context)
            loaded = true
        }
        return _units.value
    }

    fun secretFor(context: Context, id: String): ByteArray? = load(context).firstOrNull { it.id == id }?.secret

    fun nameOf(context: Context, id: String): String? = load(context).firstOrNull { it.id == id }?.name

    @Synchronized
    fun add(context: Context, offer: PairingOffer) {
        val unit = PairedUnit(offer.id, offer.unitName, offer.secret, System.currentTimeMillis())
        write(context, load(context).filter { it.id != offer.id } + unit)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, load(context).filter { it.id != id })
    }

    /** Whether the driver has sharing switched on (on by default once paired). */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): List<PairedUnit> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                PairedUnit(o.getString("id"), o.getString("name"), Base64.getDecoder().decode(o.getString("secret")), o.optLong("pairedAt"))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, units: List<PairedUnit>) {
        val array = JSONArray()
        units.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("secret", Base64.getEncoder().encodeToString(it.secret))
                    .put("pairedAt", it.pairedAt)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
        _units.value = units
    }
}
