package com.openauto.dash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Fuel prices at the stations around the car, from the French government's
 * open data (prix-carburants.gouv.fr, free, no key), which every station in
 * France must feed. Elsewhere the list is simply empty.
 */

/** The grades the data knows; [field] is the dataset's column prefix. */
enum class FuelGrade(val field: String, val label: String) {
    GAZOLE("gazole", "Gazole"),
    E10("e10", "E10"),
    SP95("sp95", "SP95"),
    SP98("sp98", "SP98"),
    E85("e85", "E85"),
    GPLC("gplc", "GPLc")
}

/** One station: where it is and what it charges per litre for each grade it sells. */
data class FuelStation(
    val id: Long,
    val address: String,
    val town: String,
    val lat: Double,
    val lng: Double,
    val prices: Map<FuelGrade, Double>
) {
    /** Straight-line distance to ([lat], [lng]) in km. */
    fun distanceKm(fromLat: Double, fromLng: Double): Double {
        val r = 6_371.0
        val dLat = Math.toRadians(lat - fromLat)
        val dLng = Math.toRadians(lng - fromLng)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(fromLat)) * cos(Math.toRadians(lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** A station ranked for one grade: its price for it and how far it is. */
data class RankedStation(val station: FuelStation, val price: Double, val distanceKm: Double)

/** The pure side: which grade the car takes, the query, the reading, the ranking. Unit-tested. */
object FuelPrices {
    const val RADIUS_KM = 15
    private const val LIMIT = 40
    private const val BASE = "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/prix-des-carburants-en-france-flux-instantane-v2/records"

    /** The grades this car can take, best first: a petrol car shows E10 unless only SP95/98 is around. */
    fun gradesFor(car: CarProfile): List<FuelGrade> = when (car.fuel) {
        FuelType.DIESEL -> listOf(FuelGrade.GAZOLE)
        FuelType.PETROL, FuelType.HYBRID -> listOf(FuelGrade.E10, FuelGrade.SP95, FuelGrade.SP98)
        FuelType.LPG -> listOf(FuelGrade.GPLC, FuelGrade.E10, FuelGrade.SP95, FuelGrade.SP98)
    }

    /** Stations within [RADIUS_KM] of the car selling [grade], cheapest first. */
    fun url(lat: Double, lng: Double, grade: FuelGrade): String = String.format(
        Locale.US,
        "%s?where=distance(geom,geom'POINT(%.4f %.4f)',%dkm) AND %s_prix IS NOT NULL" +
            "&select=id,adresse,ville,geom,gazole_prix,e10_prix,sp95_prix,sp98_prix,e85_prix,gplc_prix&order_by=%s_prix&limit=%d",
        BASE, lng, lat, RADIUS_KM, grade.field, grade.field, LIMIT
    ).replace(" ", "%20").replace("'", "%27")

    fun parse(json: String): List<FuelStation> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.optJSONObject(i) ?: return@mapNotNull null
            val geom = o.optJSONObject("geom") ?: return@mapNotNull null
            val lat = geom.optDouble("lat", Double.NaN)
            val lng = geom.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) return@mapNotNull null
            val prices = FuelGrade.entries.mapNotNull { g ->
                val key = "${g.field}_prix"
                if (o.isNull(key)) null else o.optDouble(key).takeIf { it > 0 }?.let { g to it }
            }.toMap()
            FuelStation(o.optLong("id"), tidy(o.optString("adresse")), tidy(o.optString("ville")), lat, lng, prices)
        }
    }

    /** Street data is typed in every style ("181, BOULEVARD VINCENT AURIOL", "PORTE D'ASNIERES"): one look for all. */
    internal fun tidy(s: String): String {
        val out = StringBuilder()
        var startOfWord = true
        for (c in s.trim().lowercase(Locale.FRANCE)) {
            when {
                c == ' ' && (out.isEmpty() || out.last() == ' ') -> continue
                c == ' ' || c == '-' || c == '\'' || c == '’' -> { out.append(c); startOfWord = true }
                startOfWord -> { out.append(c.uppercaseChar()); startOfWord = false }
                else -> out.append(c)
            }
        }
        return out.toString().trimEnd().replace(" ,", ",")
    }

    /** The stations selling [grade], cheapest first (nearest among equals), with their distance from the car. */
    fun rank(stations: List<FuelStation>, grade: FuelGrade, lat: Double, lng: Double): List<RankedStation> =
        stations.mapNotNull { s -> s.prices[grade]?.let { RankedStation(s, it, s.distanceKm(lat, lng)) } }
            .sortedWith(compareBy({ it.price }, { it.distanceKm }))

    fun formatPrice(price: Double): String = String.format(Locale.getDefault(), "%.3f", price)

    fun formatDistance(km: Double): String =
        if (km < 10) String.format(Locale.getDefault(), "%.1f km", km) else "${km.toInt()} km"
}

/** Fetches and keeps the stations around the car; refreshes when it moves on or the list gets old. */
object FuelPriceRepo {
    private val _stations = MutableStateFlow<List<FuelStation>?>(null)
    /** null until the first answer; empty when no station around sells the grade. */
    val stations: StateFlow<List<FuelStation>?> = _stations.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val client = OkHttpClient.Builder()
        .dns(Ipv4First)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private var lastGrade: FuelGrade? = null
    private var lastFetch = 0L

    private const val REFRESH_MS = 30 * 60_000L
    private const val MOVE_DEG = 0.03   // ~3 km: the neighbourhood has changed

    /** Made-up stations (a demo), and null to go back to the real ones. */
    internal fun demoWrite(stations: List<FuelStation>?, error: String?) {
        _stations.value = stations
        _error.value = error
    }

    /** Fetches when the list is stale, the car has moved or the grade changed; cheap to call often. */
    suspend fun refresh(lat: Double, lng: Double, grade: FuelGrade, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val moved = abs(lat - lastLat) > MOVE_DEG || abs(lng - lastLng) > MOVE_DEG
        if (!force && !moved && grade == lastGrade && now - lastFetch < REFRESH_MS) return
        lastFetch = now
        lastLat = lat
        lastLng = lng
        lastGrade = grade
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(FuelPrices.url(lat, lng, grade)).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    FuelPrices.parse(resp.body?.string().orEmpty())
                }
            }.onSuccess {
                _stations.value = it
                _error.value = null
            }.onFailure {
                _error.value = it.message.orEmpty()
                // Allow a retry before the normal interval.
                lastFetch = now - REFRESH_MS + 60_000L
            }
        }
    }
}
