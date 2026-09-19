package com.openauto.dash

/** A human-readable meaning + fix hint for an OBD-II trouble code. */
data class DtcInfo(val code: String, val title: String, val fix: String)

/**
 * Explains OBD-II Diagnostic Trouble Codes. Contains the common generic codes
 * plus ones frequent on the user's **Citroën C4 Picasso (2011)** — 1.6 VTi/THP
 * petrol (timing chain, cam/crank, turbo) and 1.6/2.0 HDi diesel (fuel rail,
 * DPF, EGR). Unknown codes fall back to a description derived from the code's
 * structure so there's always something useful.
 */
object ObdCodes {

    private data class Entry(val title: String, val fix: String)

    private val TABLE: Map<String, Entry> = mapOf(
        // --- Misfires (common on 1.6 THP) ---
        "P0300" to Entry("Random/multiple cylinder misfire", "Check spark plugs & coils (THP: coils are a known weak point), fuel injectors, and for vacuum leaks. Do plugs/coils as a set."),
        "P0301" to Entry("Cylinder 1 misfire", "Swap coil/plug from cyl 1 to another cyl; if the misfire follows, replace that coil/plug. Also check injector 1."),
        "P0302" to Entry("Cylinder 2 misfire", "Swap coil/plug to isolate; check injector 2 and compression."),
        "P0303" to Entry("Cylinder 3 misfire", "Swap coil/plug to isolate; check injector 3 and compression."),
        "P0304" to Entry("Cylinder 4 misfire", "Swap coil/plug to isolate; check injector 4 and compression."),

        // --- Timing / cam-crank (THP timing chain stretch is very common) ---
        "P0016" to Entry("Crankshaft/camshaft correlation (bank1 sensor A)", "Classic 1.6 THP stretched timing chain. Check chain, tensioner and cam/crank sensors. Often needs a timing chain kit."),
        "P0017" to Entry("Crank/cam correlation (bank1 sensor B)", "Likely stretched timing chain / tensioner (1.6 THP). Inspect chain & sprockets."),
        "P0011" to Entry("Intake camshaft timing over-advanced (bank1)", "VVT/cam phaser or oil control valve. Check oil level/quality, VVT solenoid, and timing chain wear."),
        "P0341" to Entry("Camshaft position sensor range", "Check the cam sensor and its wiring; verify timing (chain) is correct."),
        "P0340" to Entry("Camshaft position sensor circuit", "Test/replace cam sensor; inspect connector and wiring."),

        // --- Turbo / boost (THP & HDi) ---
        "P0299" to Entry("Turbocharger underboost", "Common on THP/HDi. Check for boost leaks (hoses/intercooler), wastegate/turbo actuator, and the diverter valve."),
        "P0234" to Entry("Turbo overboost", "Check wastegate actuator sticking, boost solenoid, and for a stuck VNT (HDi)."),

        // --- Fuel system (HDi diesel) ---
        "P0087" to Entry("Fuel rail pressure too low", "HDi: check fuel filter, low-pressure supply, high-pressure pump and rail pressure regulator; look for air/leaks."),
        "P0089" to Entry("Fuel pressure regulator performance", "Check the rail pressure regulator/metering valve and pump; inspect fuel filter."),
        "P0093" to Entry("Large fuel leak detected", "HDi: inspect injector leak-off, high-pressure pipes and pump seals for diesel leaks."),

        // --- Emissions / after-treatment ---
        "P0420" to Entry("Catalyst efficiency below threshold (bank1)", "Often a worn catalytic converter or a lazy rear O2 sensor. Rule out exhaust leaks and misfires first."),
        "P0401" to Entry("EGR flow insufficient", "Clean or replace the EGR valve; check EGR cooler and passages for carbon (common on HDi)."),
        "P0402" to Entry("EGR flow excessive", "Check EGR valve stuck open and its control; clean carbon build-up."),
        "P2463" to Entry("DPF soot accumulation too high", "HDi diesel: do a long highway run for regeneration; check DPF pressure sensor and additive (Eolys) level. May need forced regen."),
        "P242F" to Entry("DPF restriction (ash accumulation)", "DPF near end of life or blocked; may need forced regen or DPF cleaning/replacement."),

        // --- Sensors ---
        "P0113" to Entry("Intake air temperature sensor high", "Check IAT sensor and wiring/connector; clean or replace the sensor (often in the MAF/intake)."),
        "P0128" to Entry("Coolant below thermostat regulating temp", "Usually a stuck-open thermostat. Replace thermostat; verify coolant level and sensor."),
        "P0135" to Entry("O2 sensor heater (bank1 sensor1)", "Check the upstream O2 sensor heater circuit and fuse; replace the sensor if the heater is open."),
        "P0130" to Entry("O2 sensor circuit (bank1 sensor1)", "Inspect upstream O2 sensor and wiring; replace if lazy/faulty."),
        "P0171" to Entry("System too lean (bank1)", "Check for intake/vacuum leaks (common), dirty MAF, weak fuel pump/injectors."),
        "P0172" to Entry("System too rich (bank1)", "Check for leaking injectors, high fuel pressure, dirty MAF or faulty O2 sensor."),

        // --- Idle / throttle ---
        "P0505" to Entry("Idle air control system", "Clean throttle body; check for intake leaks and throttle motor operation."),
        "P2100" to Entry("Throttle actuator motor circuit", "Check throttle body motor and wiring; may need throttle body replacement.")
    )

    fun describe(code: String): DtcInfo {
        val c = code.uppercase().trim()
        TABLE[c]?.let { return DtcInfo(c, it.title, it.fix) }
        return DtcInfo(c, fallbackTitle(c), fallbackFix(c))
    }

    private fun fallbackTitle(code: String): String {
        if (code.length < 2) return "Unknown code"
        val system = when (code[0]) {
            'P' -> "Powertrain"
            'C' -> "Chassis"
            'B' -> "Body"
            'U' -> "Network"
            else -> "Unknown"
        }
        val scope = if (code.getOrNull(1) == '1' || code.getOrNull(1) == '3') "manufacturer-specific" else "generic"
        val area = when (code.getOrNull(2)) {
            '1', '2' -> "fuel & air metering"
            '3' -> "ignition / misfire"
            '4' -> "emissions / auxiliary"
            '5' -> "speed / idle control"
            '6' -> "computer / outputs"
            '7', '8', '9' -> "transmission"
            else -> "system"
        }
        return "$system $scope fault — $area"
    }

    private fun fallbackFix(code: String): String =
        "No stored description. Scan for related codes, check the affected " +
            "circuit's sensor/wiring, and search \"$code Citroën C4 Picasso\" for " +
            "model-specific guidance."
}
