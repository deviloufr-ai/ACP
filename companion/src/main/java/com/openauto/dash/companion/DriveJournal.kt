package com.openauto.dash.companion

import android.content.Context
import com.openauto.dash.link.DriveSummaries
import com.openauto.dash.link.DriveSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The car's drives, as the head unit reports them over the link ([DriveSummary]):
 * the trip computer's figures and the eco-driving score of each. Kept in
 * private preferences, newest first, so they are there once the car is
 * switched off and the link is gone. One entry per drive (its start time): the
 * drive under way is reported as it goes and replaced by its final figures.
 */
object DriveJournal {
    private const val PREFS = "drive_journal"
    private const val KEY = "drives"
    private const val MAX_ITEMS = 100

    private val _drives = MutableStateFlow<List<DriveSummary>>(emptyList())
    val drives: StateFlow<List<DriveSummary>> = _drives
    private var loaded = false

    @Synchronized
    fun load(context: Context): List<DriveSummary> {
        if (!loaded) {
            _drives.value = prefs(context).getString(KEY, null)?.let { DriveSummaries.decode(it) }.orEmpty()
            loaded = true
        }
        return _drives.value
    }

    /** One drive's latest figures. */
    @Synchronized
    fun update(context: Context, drive: DriveSummary) {
        write(context, DriveSummaries.merge(load(context), drive, MAX_ITEMS))
    }

    /** Everything the head unit knows, as the link comes up. */
    @Synchronized
    fun sync(context: Context, drives: List<DriveSummary>) {
        write(context, DriveSummaries.mergeAll(load(context), drives, MAX_ITEMS))
    }

    private fun write(context: Context, drives: List<DriveSummary>) {
        if (drives == _drives.value) return
        _drives.value = drives
        prefs(context).edit().putString(KEY, DriveSummaries.encode(drives)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
