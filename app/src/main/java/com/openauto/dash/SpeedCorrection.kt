package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The driver's correction to the speed the car reports over OBD: the engine
 * computer's figure often differs by a few km/h from the car's own speedometer
 * (or from the GPS). [offsetKmh] is added to every OBD speed reading before
 * anything shows or uses it; a stopped car still reads 0.
 */
object SpeedCorrection {
    private const val PREFS = "speed_correction"
    private const val KEY = "offset_kmh"

    /** How far the correction goes either way. */
    const val MAX_OFFSET_KMH = 20

    private val _offsetKmh = MutableStateFlow(0)
    val offsetKmh: StateFlow<Int> = _offsetKmh.asStateFlow()

    private var appContext: Context? = null

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        _offsetKmh.value = clamp(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0))
    }

    fun save(context: Context, offsetKmh: Int) {
        val value = clamp(offsetKmh)
        _offsetKmh.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, value).apply()
    }

    /** [reportedKmh] as the car sent it, corrected. */
    fun corrected(reportedKmh: Int): Int = correct(reportedKmh, _offsetKmh.value)

    /** Pure, for the tests: 0 stays 0 (the car is stopped), and a correction never goes below 0. */
    internal fun correct(reportedKmh: Int, offsetKmh: Int): Int =
        if (reportedKmh <= 0) 0 else (reportedKmh + offsetKmh).coerceAtLeast(0)

    private fun clamp(offsetKmh: Int): Int = offsetKmh.coerceIn(-MAX_OFFSET_KMH, MAX_OFFSET_KMH)
}
