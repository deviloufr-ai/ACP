package com.openauto.dash

/*
 * The AI mechanic's verdict, and the car's own rules that bound it. How
 * serious a fault is decides whether the driver stops, so the model's word is
 * never the last one: a fault the rules know to be serious is rated at least
 * that serious whatever the model said. Pure, so it's unit-tested.
 */

enum class Severity { OK, SOON, STOP }

/**
 * One fault code as the AI mechanic explains it: [meaning] is a short title,
 * [checkFirst] the one thing to look at; the rest fills the detail sheet and is
 * empty when the answer didn't include it.
 */
data class CodeAdvice(
    val code: String,
    val meaning: String,
    val causes: List<String>,
    val checkFirst: String,
    val explanation: String = "",
    val symptoms: List<String> = emptyList(),
    val checks: List<String> = emptyList(),
    val repair: String = "",
    val cost: String = "",
    val diy: String = "",
    val driving: String = ""
)

/**
 * The mechanic's verdict on a set of fault codes: [summary] is spoken,
 * [overview] ties the codes together. [model] is the Gemini model that
 * answered (empty for an answer cached before it was noted); [raisedByRules]
 * says [SeverityFloor] rated it more serious than the model did, and
 * [summary] is then the rules' sentence, not the model's.
 */
data class Diagnosis(
    val severity: Severity,
    val summary: String,
    val codes: List<CodeAdvice>,
    val overview: String = "",
    val model: String = "",
    val raisedByRules: Boolean = false
)

/** Coolant temperatures that count as overheating, from what the engine runs at once warm ([CarProfile.hotC]). */
internal object Overheat {
    /** About 20 °C over its normal temperature: the fan should have caught it long before. */
    fun alarmC(hotC: Int): Int = hotC + 20

    /** Back under this, the engine has cooled and a new overheat is news again. */
    fun clearC(hotC: Int): Int = hotC + 10
}

/** The least serious verdict the car's own rules allow for a scan. */
internal object SeverityFloor {

    /** Faults that can wreck the engine within minutes: always "stop". */
    private val STOP_CODES = setOf(
        "P0217", // engine over temperature
        "P0524"  // engine oil pressure too low
    )

    /**
     * Faults to see a garage about within days, whatever the model thinks:
     * misfires (they destroy the catalytic converter), a failing crankshaft
     * sensor (the engine can cut out), low fuel rail pressure, the cooling fan
     * and oil pressure circuits, the charging voltage, the engine computer
     * itself, and lost contact with it.
     */
    private val SOON_CODES = powertrain(300..312) + powertrain(335..339) + powertrain(480..482) +
        powertrain(520..523) + powertrain(600..606) +
        setOf("P0087", "P0218", "P0219", "P0562", "P0563", "U0100")

    /** "P0300" … for each number in [numbers]. */
    private fun powertrain(numbers: IntRange): Set<String> = numbers.map { "P" + it.toString().padStart(4, '0') }.toSet()

    /**
     * The floor for [codes] with the engine's coolant at [coolantC] (0 = not
     * reported) and a normal running temperature of [hotC]. Chassis codes (C:
     * brakes, ABS, stability, steering) and airbag codes (B00xx) are never
     * "fine" either.
     */
    fun of(codes: List<String>, coolantC: Int, hotC: Int): Severity {
        val list = codes.map { it.trim().uppercase() }
        return when {
            coolantC >= Overheat.alarmC(hotC) -> Severity.STOP
            list.any { it in STOP_CODES } -> Severity.STOP
            list.any { it in SOON_CODES || it.startsWith("C") || it.startsWith("B00") } -> Severity.SOON
            else -> Severity.OK
        }
    }

    /**
     * [d] rated at least [floor]. When that raises it, the model's sentence
     * (which may say "keep driving") gives way to [ruleLine]'s for the new
     * severity, so what is spoken never contradicts the verdict shown.
     */
    fun apply(d: Diagnosis, floor: Severity, ruleLine: (Severity) -> String): Diagnosis =
        if (floor <= d.severity) d else d.copy(severity = floor, summary = ruleLine(floor), raisedByRules = true)
}

/** Who the model answers as: a mechanic who knows the driver's own car, not one make written in. */
internal object MechanicPersona {
    fun of(car: String): String =
        "You are an experienced independent mechanic who knows this car's make, engine and gearbox well: $car."
}
