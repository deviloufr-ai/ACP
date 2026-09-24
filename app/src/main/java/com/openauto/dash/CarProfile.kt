package com.openauto.dash

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * The car the launcher is fitted to: its specs, from a built-in preset, from
 * Gemini ("My car" → Fetch specs) or typed by the driver. Tiles, the fuel range,
 * the drive monitor and the AI mechanic all read it, so nothing hardcodes the car.
 */

enum class FuelType { DIESEL, PETROL, HYBRID, LPG }

/** ROBOTISED = single-clutch automated manual (Citroën BMP6 / EGS, Peugeot 2-Tronic). */
enum class GearboxType { MANUAL, ROBOTISED, AUTOMATIC, DUAL_CLUTCH, CVT }

/** Where the specs came from. */
enum class SpecSource { PRESET, AI, USER }

/**
 * Everything the launcher knows about the car. Nullable specs are "not known":
 * the derived values below fall back to safe figures for the fuel type.
 * [fuelPrice] and [currency] are the driver's own, never fetched.
 */
data class CarProfile(
    val name: String,
    val engine: String = "",
    val fuel: FuelType = FuelType.DIESEL,
    val powerHp: Int? = null,
    val torqueNm: Int? = null,
    val torqueRpm: Int? = null,
    val redlineRpm: Int? = null,
    val gearbox: GearboxType = GearboxType.MANUAL,
    val gearboxName: String = "",
    val gears: Int? = null,
    val tankL: Double? = null,
    val consumptionL100: Double? = null,
    val particleFilter: Boolean = false,
    val filterAdditive: Boolean = false,
    val oilCapacityL: Double? = null,
    val oilSpec: String = "",
    val serviceKm: Int? = null,
    val serviceMonths: Int? = null,
    val timing: String = "",
    val tyreSize: String = "",
    val tyreFrontBar: Double? = null,
    val tyreRearBar: Double? = null,
    val batteryAh: Int? = null,
    val operatingTempC: Int? = null,
    /** Known weak points and upkeep tips for this engine and gearbox. */
    val notes: List<String> = emptyList(),
    val fuelPrice: Double = 1.75,
    val currency: String = "€",
    val source: SpecSource = SpecSource.PRESET,
    val updatedAt: Long = 0L
) {
    val diesel: Boolean get() = fuel == FuelType.DIESEL

    /** Tank size, or a typical family car's. */
    val tank: Double get() = tankL ?: TANK_LITERS

    /** Everyday consumption, or the old rough figure. */
    val typicalUse: Double get() = consumptionL100 ?: AVG_L_PER_100KM

    /** Coolant temperature the engine runs at once warm. */
    val hotC: Int get() = operatingTempC ?: 90

    /** Below this the engine is still cold: go easy. */
    val coldC: Int get() = hotC - 30

    /** Rev limit while cold: diesels make their torque low and need less. */
    val coldRpmLimit: Int get() = if (diesel) 2500 else 3000

    /** The relaxed band to drive in: from peak torque up about 1,000 rpm. */
    val sweetBand: IntRange get() {
        val low = torqueRpm ?: if (diesel) 1750 else 2500
        return low..(low + 1000)
    }

    /** Revs above this count against the eco score. */
    val ecoRpmMax: Int get() = if (diesel) 3000 else 3500

    /** One line naming the car for Gemini: model, engine, gearbox, filter. */
    fun promptDescription(): String = buildString {
        append(name.trim())
        val details = listOfNotNull(
            engine.ifBlank { null },
            fuel.name.lowercase(Locale.ROOT),
            powerHp?.let { "$it hp" },
            gearboxLabel().ifBlank { null },
            when {
                particleFilter && filterAdditive -> "particulate filter with additive (Eolys)"
                particleFilter -> "particulate filter"
                else -> null
            }
        )
        if (details.isNotEmpty()) append(" (" + details.joinToString(", ") + ")")
    }

    private fun gearboxLabel(): String {
        val kind = when (gearbox) {
            GearboxType.MANUAL -> "manual gearbox"
            GearboxType.ROBOTISED -> "robotised single-clutch gearbox"
            GearboxType.AUTOMATIC -> "automatic gearbox"
            GearboxType.DUAL_CLUTCH -> "dual-clutch gearbox"
            GearboxType.CVT -> "CVT gearbox"
        }
        return listOfNotNull(gears?.let { "$it-speed" }, kind, gearboxName.ifBlank { null }?.let { "($it)" }).joinToString(" ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("engine", engine); put("fuel", fuel.name)
        putOpt("power_hp", powerHp); putOpt("torque_nm", torqueNm); putOpt("torque_rpm", torqueRpm); putOpt("redline_rpm", redlineRpm)
        put("gearbox", gearbox.name); put("gearbox_name", gearboxName); putOpt("gears", gears)
        putOpt("tank_l", tankL); putOpt("consumption_l100", consumptionL100)
        put("particle_filter", particleFilter); put("filter_additive", filterAdditive)
        putOpt("oil_capacity_l", oilCapacityL); put("oil_spec", oilSpec)
        putOpt("service_km", serviceKm); putOpt("service_months", serviceMonths); put("timing", timing)
        put("tyre_size", tyreSize); putOpt("tyre_front_bar", tyreFrontBar); putOpt("tyre_rear_bar", tyreRearBar)
        putOpt("battery_ah", batteryAh); putOpt("operating_temp_c", operatingTempC)
        put("notes", JSONArray(notes))
        put("fuel_price", fuelPrice); put("currency", currency)
        put("source", source.name); put("updated_at", updatedAt)
    }

    companion object {
        /**
         * The driver's car: Citroën C4 Picasso 1.6 HDi 110 FAP Exclusive (2011)
         * with the BMP6 robotised gearbox. Only well-known figures; the rest
         * (tyres, service plan...) comes from "Fetch specs".
         */
        val PRESET = CarProfile(
            name = "Citroën C4 Picasso 1.6 HDi 110 FAP Exclusive 2011, BMP6",
            engine = "1.6 HDi 110 FAP (PSA DV6TED4)",
            fuel = FuelType.DIESEL,
            powerHp = 110,
            torqueNm = 240,
            torqueRpm = 1750,
            gearbox = GearboxType.ROBOTISED,
            gearboxName = "BMP6",
            gears = 6,
            tankL = 60.0,
            consumptionL100 = AVG_L_PER_100KM,
            particleFilter = true,
            filterAdditive = true,
            oilCapacityL = 3.75,
            oilSpec = "5W-30 PSA B71 2290",
            operatingTempC = 90
        )

        fun fromJson(o: JSONObject): CarProfile {
            fun int(k: String) = if (o.isNull(k)) null else o.optInt(k)
            fun dbl(k: String) = if (o.isNull(k)) null else o.optDouble(k).takeIf { !it.isNaN() }
            fun <E : Enum<E>> enum(k: String, values: Array<E>, default: E) =
                values.firstOrNull { it.name == o.optString(k) } ?: default
            return CarProfile(
                name = o.optString("name").ifBlank { PRESET.name },
                engine = o.optString("engine"),
                fuel = enum("fuel", FuelType.entries.toTypedArray(), FuelType.DIESEL),
                powerHp = int("power_hp"), torqueNm = int("torque_nm"), torqueRpm = int("torque_rpm"), redlineRpm = int("redline_rpm"),
                gearbox = enum("gearbox", GearboxType.entries.toTypedArray(), GearboxType.MANUAL),
                gearboxName = o.optString("gearbox_name"), gears = int("gears"),
                tankL = dbl("tank_l"), consumptionL100 = dbl("consumption_l100"),
                particleFilter = o.optBoolean("particle_filter"), filterAdditive = o.optBoolean("filter_additive"),
                oilCapacityL = dbl("oil_capacity_l"), oilSpec = o.optString("oil_spec"),
                serviceKm = int("service_km"), serviceMonths = int("service_months"), timing = o.optString("timing"),
                tyreSize = o.optString("tyre_size"), tyreFrontBar = dbl("tyre_front_bar"), tyreRearBar = dbl("tyre_rear_bar"),
                batteryAh = int("battery_ah"), operatingTempC = int("operating_temp_c"),
                notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
                fuelPrice = o.optDouble("fuel_price", PRESET.fuelPrice).takeIf { !it.isNaN() } ?: PRESET.fuelPrice,
                currency = o.optString("currency").ifBlank { PRESET.currency },
                source = enum("source", SpecSource.entries.toTypedArray(), SpecSource.USER),
                updatedAt = o.optLong("updated_at")
            )
        }
    }
}

/** The saved car, shared by every screen; the preset until the driver changes it. */
object CarProfileStore {
    private const val PREFS = "car_profile"
    private const val KEY = "profile"

    private var appContext: Context? = null
    private val _profile = MutableStateFlow(CarProfile.PRESET)
    val profile: StateFlow<CarProfile> = _profile.asStateFlow()

    /** The car right now, for code outside Compose. */
    val current: CarProfile get() = _profile.value

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching { CarProfile.fromJson(JSONObject(raw)) }.onSuccess { _profile.value = it }
    }

    fun save(profile: CarProfile) {
        _profile.value = profile
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY, profile.toJson().toString())?.apply()
    }
}
