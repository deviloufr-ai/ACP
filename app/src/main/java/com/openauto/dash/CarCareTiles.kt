package com.openauto.dash

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Car-care tiles, tailored by the car profile: particle filter, warm-up,
 * battery, eco driving, fuel to destination, break reminder and the spec sheet.
 * The spoken side lives in CarCare; these only show the same state.
 */

/** How a reading stands: drives the status line's colour. */
private enum class Tone { GOOD, INFO, CAUTION, BAD }

private val Tone.color: Color
    get() = when (this) {
        Tone.GOOD -> DashColors.Good
        Tone.INFO -> DashColors.Accent
        Tone.CAUTION -> DashColors.Warning
        Tone.BAD -> DashColors.Critical
    }

/** Ticks every second so elapsed times move. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun CareCard(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TileHeader(title)
            content()
        }
    }
}

/** A big reading with its unit, e.g. "54" "°C". */
@Composable
private fun Reading(value: String, unit: String = "", dimmed: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom) {
        HeroNumber(text = value, size = 40, dimmed = dimmed)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(unit, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.em,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun Status(text: String, tone: Tone) {
    Text(text, color = tone.color, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Hint(text: String) {
    Text(text, color = DashColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun Meter(fraction: Float, color: Color) {
    val shape = RoundedCornerShape(3.dp)
    Row(modifier = Modifier.fillMaxWidth().height(6.dp).clip(shape).background(DashColors.CardHi)) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) Spacer(Modifier.weight(f).fillMaxHeight().background(color, shape))
        if (f < 1f) Spacer(Modifier.weight(1f - f))
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = DashColors.TextPrimary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium)
    }
}

private fun decimal(v: Double, digits: Int = 1): String = String.format(Locale.getDefault(), "%.${digits}f", v)

// --- Particle filter ------------------------------------------------------------

@Composable
internal fun FilterCareCard(modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    val now = rememberNow()
    CareCard(stringResource(R.string.car_filter_title), modifier) {
        if (!car.particleFilter) {
            Hint(stringResource(R.string.car_filter_none))
            return@CareCard
        }
        val streak = care.filter.shortStreak
        val (status, tone) = when {
            streak >= 6 -> stringResource(R.string.car_filter_needs_drive_now) to Tone.BAD
            streak >= CareRules.FILTER_WARN_STREAK -> stringResource(R.string.car_filter_needs_drive) to Tone.CAUTION
            else -> stringResource(R.string.car_filter_ok) to Tone.GOOD
        }
        Reading(streak.toString(), stringResource(R.string.car_filter_short_unit))
        Status(status, tone)
        // The real soot load, when the experimental reading finder got the car to give it up.
        val extra by PidExplorer.readings.collectAsState()
        extra[ExtraReading.SOOT_LOAD]?.let { soot ->
            Meter((soot.value / 100).toFloat(), if (soot.value >= 80) DashColors.Warning else DashColors.Accent)
            Hint(stringResource(R.string.explore_soot_status, soot.value.roundToInt()))
        }
        care.drive?.let { d ->
            val minutes = (d.hotFastMs / 60_000).toInt()
            Meter(d.hotFastMs.toFloat() / CareRules.LONG_DRIVE_MS, if (d.filterFriendly) DashColors.Good else DashColors.Accent)
            Hint(stringResource(R.string.car_filter_this_drive, minutes, (CareRules.LONG_DRIVE_MS / 60_000).toInt()))
        }
        val last = care.filter.lastLongAt
        Hint(
            if (last > 0) stringResource(R.string.car_filter_last_long, DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS).toString())
            else stringResource(R.string.car_filter_last_long_never)
        )
        if (car.filterAdditive) Hint(stringResource(R.string.car_filter_additive))
    }
}

// --- Engine warm-up -------------------------------------------------------------

@Composable
internal fun WarmupCard(obd: ObdData, connected: Boolean, modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    val now = rememberNow()
    CareCard(stringResource(R.string.car_warmup_title), modifier) {
        val t = obd.coolantTempC
        if (!connected || t == 0) {
            Reading("--", "°C", dimmed = true)
            Hint(stringResource(R.string.car_waiting_obd))
            return@CareCard
        }
        Reading(t.toString(), "°C")
        val (status, tone) = when {
            t < car.coldC -> stringResource(R.string.car_warmup_cold, car.coldRpmLimit) to Tone.CAUTION
            t < car.hotC - 10 -> stringResource(R.string.car_warmup_warming) to Tone.INFO
            else -> stringResource(R.string.car_warmup_warm) to Tone.GOOD
        }
        Meter(t.toFloat() / car.hotC, tone.color)
        Status(status, tone)
        care.drive?.let { Hint(stringResource(R.string.car_running_for, formatDuration(now - it.startedAt))) }
    }
}

// --- Battery --------------------------------------------------------------------

@Composable
internal fun BatteryCard(obd: ObdData, connected: Boolean, modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    val watch by BatteryWatch.state.collectAsState()
    CareCard(stringResource(R.string.car_battery_title), modifier) {
        val v = obd.voltage
        if (!connected || v < LiveWatch.MIN_PLAUSIBLE_V || v > LiveWatch.MAX_PLAUSIBLE_V) {
            Reading("--", "V", dimmed = true)
            Hint(stringResource(R.string.car_waiting_obd))
            return@CareCard
        }
        Reading(decimal(v), "V")
        val running = obd.rpm > LiveWatch.RUNNING_RPM
        val (status, tone) = if (running) when {
            v >= LiveWatch.CHARGE_CLEAR_V -> stringResource(R.string.car_battery_charging) to Tone.GOOD
            v >= LiveWatch.NOT_CHARGING_V -> stringResource(R.string.car_battery_charging_low) to Tone.CAUTION
            else -> stringResource(R.string.car_battery_not_charging) to Tone.BAD
        } else when {
            v >= 12.6 -> stringResource(R.string.car_battery_full) to Tone.GOOD
            v >= LiveWatch.BATTERY_CLEAR_V -> stringResource(R.string.car_battery_good) to Tone.GOOD
            v >= LiveWatch.WEAK_BATTERY_V -> stringResource(R.string.car_battery_low) to Tone.CAUTION
            else -> stringResource(R.string.car_battery_weak) to Tone.BAD
        }
        Meter(((v - 11.5) / (14.8 - 11.5)).toFloat(), tone.color)
        Status(status, tone)
        // The trip's range, from the engine computer's readings once running: a
        // spike or dip too short to alert still shows here.
        val lo = watch.tripMin
        val hi = watch.tripMax
        if (lo != null && hi != null) Hint(stringResource(R.string.car_battery_trip_range, decimal(lo), decimal(hi)))
        Hint(
            car.batteryAh?.let { stringResource(R.string.car_battery_capacity, it) }
                ?: stringResource(if (running) R.string.car_battery_hint_running else R.string.car_battery_hint_off)
        )
    }
}

// --- Eco driving ----------------------------------------------------------------

@Composable
internal fun EcoDriveCard(modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    val drive = care.drive ?: care.lastDrive
    CareCard(stringResource(if (care.drive != null || drive == null) R.string.car_eco_title else R.string.car_eco_title_last), modifier) {
        if (drive == null) {
            Reading("--", "/ 100", dimmed = true)
            Hint(stringResource(R.string.car_eco_empty))
            return@CareCard
        }
        val score = drive.ecoScore
        Row(verticalAlignment = Alignment.CenterVertically) {
            Reading(score?.toString() ?: "--", "/ 100", dimmed = score == null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val tone = when {
                    score == null -> Tone.INFO
                    score >= 80 -> Tone.GOOD
                    score >= 60 -> Tone.CAUTION
                    else -> Tone.BAD
                }
                Status(
                    stringResource(
                        when {
                            score == null -> R.string.car_eco_too_early
                            score >= 80 -> R.string.car_eco_smooth
                            score >= 60 -> R.string.car_eco_fair
                            else -> R.string.car_eco_harsh
                        }
                    ),
                    tone
                )
            }
        }
        drive.sweetPercent?.let {
            InfoRow(stringResource(R.string.car_eco_band, car.sweetBand.first, car.sweetBand.last), "$it %")
        }
        InfoRow(stringResource(R.string.car_eco_hard), stringResource(R.string.car_eco_hard_value, drive.hardAccel, drive.hardBrake))
        if (car.gearbox == GearboxType.ROBOTISED) {
            InfoRow(
                stringResource(R.string.car_eco_clutch), drive.clutchHolds.toString(),
                if (drive.clutchHolds > 0) DashColors.Warning else DashColors.TextPrimary
            )
        }
        // What the drive burned at the car's usual consumption, and what it cost.
        val liters = drive.distanceKm * car.typicalUse / 100
        InfoRow(
            stringResource(R.string.car_eco_fuel, decimal(drive.distanceKm)),
            stringResource(R.string.car_eco_fuel_value, decimal(liters), decimal(liters * car.fuelPrice, 2), car.currency)
        )
    }
}

// --- Fuel to destination --------------------------------------------------------

@Composable
internal fun FuelToDestCard(modifier: Modifier = Modifier) {
    val nav by NavDirections.state.collectAsState()
    val canFuel by McuReader.fuelPercent.collectAsState()
    val canRange by McuReader.rangeKm.collectAsState()
    val obd by ObdBluetoothManager.data.collectAsState()
    val car by CarProfileStore.profile.collectAsState()
    val range = remember(canFuel, canRange, obd.fuelLevelPct, car) { carFuelInfo(canFuel, obd.fuelLevelPct, canRange)?.rangeKm }
    val toGo = if (nav.active) CareRules.remainingKm(nav.eta) else null
    CareCard(stringResource(R.string.car_fuel_dest_title), modifier) {
        if (range == null) {
            Reading("--", "km", dimmed = true)
            Hint(stringResource(R.string.car_fuel_dest_no_range))
            return@CareCard
        }
        val verdict = CareRules.fuelVerdict(range, toGo)
        if (verdict == null) {
            Reading(range.toString(), "km")
            Hint(stringResource(R.string.car_fuel_dest_no_nav))
            return@CareCard
        }
        val (status, tone) = when (verdict) {
            FuelVerdict.ENOUGH -> stringResource(R.string.car_fuel_dest_enough) to Tone.GOOD
            FuelVerdict.TIGHT -> stringResource(R.string.car_fuel_dest_tight) to Tone.CAUTION
            FuelVerdict.SHORT -> stringResource(R.string.car_fuel_dest_short) to Tone.BAD
        }
        val km = toGo ?: 0.0
        Reading(((range - km).toInt()).toString(), stringResource(R.string.car_fuel_dest_spare_unit))
        Meter((km / range).toFloat(), tone.color)
        Status(status, tone)
        Hint(stringResource(R.string.car_fuel_dest_detail, range, km.toInt()))
    }
}

// --- Break reminder -------------------------------------------------------------

@Composable
internal fun BreakCard(modifier: Modifier = Modifier) {
    val care by CarCare.state.collectAsState()
    val due = CareRules.FIRST_BREAK_MIN * 60_000L
    val driving = care.rest.drivingMs
    CareCard(stringResource(R.string.car_break_title), modifier) {
        Reading(formatDuration(driving))
        val tone = when {
            driving >= due -> Tone.BAD
            driving >= due - 20 * 60_000L -> Tone.CAUTION
            else -> Tone.GOOD
        }
        Meter(driving.toFloat() / due, tone.color)
        Status(stringResource(if (driving >= due) R.string.car_break_due else R.string.car_break_ok), tone)
        Hint(stringResource(R.string.car_break_hint))
    }
}

// --- My car ---------------------------------------------------------------------

@Composable
internal fun MyCarCard(modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.car_unknown)
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().clickable { editing = true }.padding(DashSpace.Lg),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TileHeader(stringResource(R.string.car_my_car_title)) {
                Text(stringResource(car.source.labelRes), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(car.name, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            InfoRow(stringResource(R.string.car_spec_engine), car.engine.ifBlank { unknown })
            InfoRow(stringResource(R.string.car_spec_gearbox), gearboxText(car).ifBlank { unknown })
            InfoRow(stringResource(R.string.car_spec_oil), listOfNotNull(car.oilCapacityL?.let { decimal(it, 2) + " L" }, car.oilSpec.ifBlank { null }).joinToString(" · ").ifBlank { unknown })
            InfoRow(stringResource(R.string.car_spec_tyres), tyreText(car).ifBlank { unknown })
            InfoRow(stringResource(R.string.car_spec_service), serviceText(car).ifBlank { unknown })
            if (car.source == SpecSource.PRESET) Hint(stringResource(R.string.car_my_car_fetch_hint))
        }
    }
    if (editing) CarSettingsDialog(onDismiss = { editing = false })
}

internal val SpecSource.labelRes: Int
    get() = when (this) {
        SpecSource.PRESET -> R.string.car_source_preset
        SpecSource.AI -> R.string.car_source_ai
        SpecSource.USER -> R.string.car_source_user
    }

@Composable
internal fun gearboxText(car: CarProfile): String = listOfNotNull(
    car.gearboxName.ifBlank { null },
    car.gears?.let { stringResource(R.string.car_gears, it) },
    stringResource(car.gearbox.labelRes)
).joinToString(" · ")

internal val GearboxType.labelRes: Int
    get() = when (this) {
        GearboxType.MANUAL -> R.string.car_gearbox_manual
        GearboxType.ROBOTISED -> R.string.car_gearbox_robotised
        GearboxType.AUTOMATIC -> R.string.car_gearbox_automatic
        GearboxType.DUAL_CLUTCH -> R.string.car_gearbox_dual_clutch
        GearboxType.CVT -> R.string.car_gearbox_cvt
    }

internal val FuelType.labelRes: Int
    get() = when (this) {
        FuelType.DIESEL -> R.string.car_fuel_diesel
        FuelType.PETROL -> R.string.car_fuel_petrol
        FuelType.HYBRID -> R.string.car_fuel_hybrid
        FuelType.LPG -> R.string.car_fuel_lpg
    }

private fun tyreText(car: CarProfile): String {
    val pressure = if (car.tyreFrontBar != null || car.tyreRearBar != null) {
        "${car.tyreFrontBar?.let { decimal(it) } ?: "?"} / ${car.tyreRearBar?.let { decimal(it) } ?: "?"} bar"
    } else null
    return listOfNotNull(car.tyreSize.ifBlank { null }, pressure).joinToString(" · ")
}

@Composable
private fun serviceText(car: CarProfile): String = listOfNotNull(
    car.serviceKm?.let { String.format(Locale.getDefault(), "%,d km", it) },
    car.serviceMonths?.let { stringResource(R.string.car_months, it) }
).joinToString(" / ")
