package com.openauto.dash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Current conditions at the car, from Open-Meteo (free, no key, no account). */
data class Weather(
    val tempC: Double,
    val feelsC: Double,
    val code: Int,
    val windKmh: Double,
    val hiC: Double,
    val loC: Double,
    val fetchedAt: Long
) {
    /** Plain-language condition for a WMO weather code. */
    val condition: String
        get() = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"
            56, 57 -> "Freezing drizzle"
            61, 63, 65 -> "Rain"
            66, 67 -> "Freezing rain"
            71, 73, 75, 77 -> "Snow"
            80, 81, 82 -> "Showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"
            96, 99 -> "Hail storm"
            else -> "Unknown"
        }
}

object WeatherRepo {
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private var lastFetch = 0L

    private const val REFRESH_MS = 15 * 60_000L
    private const val MOVE_DEG = 0.05   // ~5 km: refresh sooner when the car has moved on

    /** Fetches when the last result is stale or the car has moved; cheap to call often. */
    suspend fun refresh(lat: Double, lng: Double, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val moved = abs(lat - lastLat) > MOVE_DEG || abs(lng - lastLng) > MOVE_DEG
        if (!force && !moved && now - lastFetch < REFRESH_MS) return
        lastFetch = now
        lastLat = lat
        lastLng = lng

        val url = String.format(
            Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
            lat, lng
        )
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val cur = json.getJSONObject("current")
                    val daily = json.getJSONObject("daily")
                    Weather(
                        tempC = cur.getDouble("temperature_2m"),
                        feelsC = cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")),
                        code = cur.optInt("weather_code", -1),
                        windKmh = cur.optDouble("wind_speed_10m", 0.0),
                        hiC = daily.getJSONArray("temperature_2m_max").optDouble(0, Double.NaN),
                        loC = daily.getJSONArray("temperature_2m_min").optDouble(0, Double.NaN),
                        fetchedAt = now
                    )
                }
            }.onSuccess {
                _weather.value = it
                _error.value = null
            }.onFailure {
                _error.value = it.message ?: "No connection"
                // Allow a retry before the normal interval.
                lastFetch = now - REFRESH_MS + 60_000L
            }
        }
    }
}
