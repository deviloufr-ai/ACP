package com.openauto.dash

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** The engines the 2011 C4 Picasso shipped with; answers are tailored to the one fitted. */
enum class CarEngine(val label: String, val detail: String) {
    VTI_16("1.6 VTi", "1.6 VTi petrol (PSA EP6, 120 hp)"),
    THP_16("1.6 THP", "1.6 THP turbo petrol (PSA EP6 turbo, 156 hp)"),
    HDI_16("1.6 HDi", "1.6 HDi diesel (PSA DV6, ~110 hp, particulate filter with Eolys additive)"),
    HDI_20("2.0 HDi", "2.0 HDi diesel (PSA DW10, 150-163 hp, particulate filter)")
}

/**
 * Language the mechanic writes and speaks in. [label] is the language's own
 * name (for the picker), [promptName] its English name (for Gemini), [locale]
 * the voice and the number format. Entry names are persisted.
 */
enum class AiLanguage(val label: String, val promptName: String, val locale: Locale) {
    ENGLISH("English", "English", Locale.UK),
    FRENCH("Français", "French", Locale.FRANCE),
    GERMAN("Deutsch", "German", Locale.GERMANY),
    SPANISH("Español", "Spanish", Locale("es", "ES")),
    ITALIAN("Italiano", "Italian", Locale.ITALY),
    PORTUGUESE("Português", "Portuguese", Locale("pt", "PT")),
    DUTCH("Nederlands", "Dutch", Locale("nl", "NL")),
    POLISH("Polski", "Polish", Locale("pl", "PL"));

    /**
     * The app's strings in this language, whatever the launcher's own: spoken
     * lines must match the voice. A blank Configuration is a delta, so only
     * the locale changes.
     */
    fun resources(context: Context): Resources =
        context.createConfigurationContext(Configuration().apply { setLocale(locale) }).resources

    companion object {
        /** The language matching [locale], English when the mechanic doesn't speak it. */
        fun of(locale: Locale): AiLanguage = entries.firstOrNull { it.locale.language == locale.language } ?: ENGLISH
    }
}

data class AiConfig(
    val apiKey: String = "",
    /** The key was unlocked with the activation code, so it isn't shown on screen. */
    val keyFromCode: Boolean = false,
    val engine: CarEngine = CarEngine.HDI_16,
    /** The driver's pick; null follows the launcher's language. */
    val languageChoice: AiLanguage? = null,
    val speak: Boolean = true
) {
    /** The language actually used: the pick, else the launcher's current one. */
    val language: AiLanguage get() = languageChoice ?: AiLanguage.of(Locale.getDefault())
}

object AiSettings {
    private const val PREFS = "ai_prefs"

    fun load(context: Context): AiConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AiConfig(
            apiKey = p.getString("api_key", "").orEmpty(),
            keyFromCode = p.getBoolean("key_from_code", false),
            engine = p.getString("engine", null)?.let { runCatching { CarEngine.valueOf(it) }.getOrNull() }
                ?: CarEngine.HDI_16,
            languageChoice = p.getString("language", null)?.let { runCatching { AiLanguage.valueOf(it) }.getOrNull() },
            speak = p.getBoolean("speak", true)
        )
    }

    fun save(context: Context, config: AiConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("api_key", config.apiKey.trim())
            .putBoolean("key_from_code", config.keyFromCode)
            .putString("engine", config.engine.name)
            .apply { config.languageChoice?.let { putString("language", it.name) } ?: remove("language") }
            .putBoolean("speak", config.speak)
            .apply()
    }
}

enum class Severity { OK, SOON, STOP }

data class CodeAdvice(val code: String, val meaning: String, val causes: List<String>, val checkFirst: String)

/** The mechanic's verdict on a set of fault codes. */
data class Diagnosis(val severity: Severity, val summary: String, val codes: List<CodeAdvice>)

/** Builds the question for Gemini and reads its answer back. Pure, so it's unit-tested. */
object MechanicPrompt {

    /** JSON shape Gemini must answer in (Gemini's OpenAPI-style schema). */
    val SCHEMA: JSONObject
        get() {
            fun str() = JSONObject().put("type", "STRING")
            val advice = JSONObject().put("type", "OBJECT").put(
                "properties",
                JSONObject()
                    .put("code", str())
                    .put("meaning", str())
                    .put("causes", JSONObject().put("type", "ARRAY").put("items", str()))
                    .put("check_first", str())
            ).put("required", JSONArray(listOf("code", "meaning", "causes", "check_first")))
            return JSONObject().put("type", "OBJECT").put(
                "properties",
                JSONObject()
                    .put("severity", str().put("enum", JSONArray(listOf("ok", "soon", "stop"))))
                    .put("summary", str())
                    .put("codes", JSONObject().put("type", "ARRAY").put("items", advice))
            ).put("required", JSONArray(listOf("severity", "summary", "codes")))
        }

    fun build(codes: List<String>, engine: CarEngine, language: AiLanguage, data: ObdData?): String = buildString {
        appendLine("You are an experienced mechanic who knows Citroën / PSA cars well.")
        appendLine("Car: Citroën C4 Picasso (2011), engine: ${engine.detail}.")
        appendLine("Its OBD scan reports these stored fault codes: ${codes.joinToString(", ")}.")
        readings(data)?.let { appendLine("Live readings at the time of the scan: $it.") }
        appendLine()
        appendLine("Answer in ${language.promptName}. The driver reads this on a small car screen, so be concrete and brief.")
        appendLine("- severity: \"ok\" = fine to keep driving normally; \"soon\" = drive gently and get it checked within days; \"stop\" = stop driving, risk of damage or danger.")
        appendLine("- summary: ONE short sentence that will be spoken aloud to the driver: the problem in plain words and what to do. No code numbers, no jargon.")
        appendLine("- codes: one entry per code, same order. meaning = what it means on this engine; causes = the 2 or 3 most likely causes on this engine, most likely first; check_first = the cheapest, simplest thing to check first.")
        append("Consider the codes together and with the readings: several codes often share one cause.")
    }

    /** Live values worth sending; zeros mean "not reported" and are left out. */
    private fun readings(d: ObdData?): String? {
        d ?: return null
        val parts = buildList {
            add(if (d.rpm > 0) "engine running at ${d.rpm} rpm" else "engine not running")
            if (d.rpm > 0) add("speed ${d.speedKmh} km/h")
            if (d.coolantTempC != 0) add("coolant ${d.coolantTempC} °C")
            if (d.intakeTempC != 0) add("intake air ${d.intakeTempC} °C")
            if (d.rpm > 0) add("engine load ${d.engineLoadPct} %")
            if (d.voltage > 0.0) add(String.format(Locale.US, "battery %.1f V", d.voltage))
        }
        return parts.joinToString(", ")
    }

    /** Reads Gemini's JSON (tolerating code fences and missing fields); null when unusable. */
    fun parse(text: String): Diagnosis? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
        val summary = json.optString("summary").trim()
        if (summary.isEmpty()) return null
        val severity = when (json.optString("severity").trim().lowercase()) {
            "ok" -> Severity.OK
            "stop" -> Severity.STOP
            else -> Severity.SOON
        }
        val list = json.optJSONArray("codes") ?: JSONArray()
        val codes = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val code = o.optString("code").trim().uppercase()
            if (code.isEmpty()) return@mapNotNull null
            val causes = o.optJSONArray("causes")
                ?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }
                .orEmpty()
            CodeAdvice(code, o.optString("meaning").trim(), causes, o.optString("check_first").trim())
        }
        return Diagnosis(severity, summary, codes)
    }
}

/**
 * Plain rules on the live readings (no AI, so they work offline). Each warning
 * is said once, then stays quiet until the reading has recovered, so a flaky
 * adapter reconnecting mid-drive doesn't repeat it.
 */
internal class LiveWatch {
    enum class Alert { OVERHEAT, NOT_CHARGING, WEAK_BATTERY }

    private var overheatArmed = true
    private var chargeArmed = true
    private var batteryArmed = true
    // When the current low stretch began; null while the reading is fine.
    private var lowChargeSince: Long? = null
    private var weakSince: Long? = null
    private var lastSample: Long? = null

    fun check(d: ObdData, now: Long): Alert? {
        // Timers only count uninterrupted readings; a gap (adapter dropped) restarts them.
        if (lastSample.let { it == null || now - it > GAP_MS }) {
            lowChargeSince = null
            weakSince = null
        }
        lastSample = now

        // The 1.6 HDi runs about 90 °C and its fan cuts in near 100 °C.
        if (d.coolantTempC in 1 until OVERHEAT_CLEAR_C) overheatArmed = true
        if (overheatArmed && d.coolantTempC >= OVERHEAT_C) {
            overheatArmed = false
            return Alert.OVERHEAT
        }

        // Clone adapters can report nonsense volts; only trust a plausible bus voltage.
        val v = d.voltage
        if (v < MIN_PLAUSIBLE_V || v > MAX_PLAUSIBLE_V) return null
        val running = d.rpm > RUNNING_RPM

        // The alternator should hold ~14 V; the e-HDi's smart charging dips lower,
        // so only a long stretch well under 13 V counts.
        if (running && v >= CHARGE_CLEAR_V) chargeArmed = true
        if (running && v < NOT_CHARGING_V) {
            val since = lowChargeSince ?: now.also { lowChargeSince = it }
            if (chargeArmed && now - since >= NOT_CHARGING_MS) {
                chargeArmed = false
                return Alert.NOT_CHARGING
            }
        } else {
            lowChargeSince = null
        }

        // Ignition on, engine off: a healthy battery rests above 12.4 V. Sustained
        // so the dip while cranking isn't mistaken for a weak battery.
        if (v >= BATTERY_CLEAR_V) batteryArmed = true
        if (d.rpm == 0 && v < WEAK_BATTERY_V) {
            val since = weakSince ?: now.also { weakSince = it }
            if (batteryArmed && now - since >= WEAK_BATTERY_MS) {
                batteryArmed = false
                return Alert.WEAK_BATTERY
            }
        } else {
            weakSince = null
        }
        return null
    }

    companion object {
        const val OVERHEAT_C = 110
        const val OVERHEAT_CLEAR_C = 100
        const val RUNNING_RPM = 500
        const val NOT_CHARGING_V = 12.5
        const val CHARGE_CLEAR_V = 13.2
        const val NOT_CHARGING_MS = 120_000L
        const val WEAK_BATTERY_V = 12.0
        const val BATTERY_CLEAR_V = 12.4
        const val WEAK_BATTERY_MS = 10_000L
        const val MIN_PLAUSIBLE_V = 9.0
        const val MAX_PLAUSIBLE_V = 16.0
        const val GAP_MS = 5_000L
    }
}

/**
 * A sentence to say: a string (or, with [quantity], a plurals) resource and its
 * arguments. Kept apart from the text so the choice is testable on the JVM;
 * [text] resolves it with [AiLanguage.resources] so it matches the voice.
 */
internal data class SpokenLine(val res: Int, val args: List<Any>, val quantity: Int? = null) {
    fun text(resources: Resources): String {
        val a = args.toTypedArray()
        return if (quantity != null) resources.getQuantityString(res, quantity, *a) else resources.getString(res, *a)
    }
}

/** Sentences the car says without the AI (offline, no key, or live-reading warnings). */
internal object MechanicLines {

    fun newCodes(codes: List<String>): SpokenLine {
        // Spaced out so the voice reads "P 0 1 2 8", not "P one hundred twenty-eight".
        val spoken = codes.joinToString(", ") { it.toCharArray().joinToString(" ") }
        return SpokenLine(R.plurals.ai_say_new_codes, listOf(codes.size, spoken), quantity = codes.size)
    }

    fun alert(alert: LiveWatch.Alert, d: ObdData, language: AiLanguage): SpokenLine {
        // Written the way the voice's language writes it: "12,1" in French.
        val volts = String.format(language.locale, "%.1f", d.voltage)
        return when (alert) {
            LiveWatch.Alert.OVERHEAT -> SpokenLine(R.string.ai_say_overheat, listOf(d.coolantTempC))
            LiveWatch.Alert.NOT_CHARGING -> SpokenLine(R.string.ai_say_not_charging, listOf(volts))
            LiveWatch.Alert.WEAK_BATTERY -> SpokenLine(R.string.ai_say_weak_battery, listOf(volts))
        }
    }
}

/** Gemini answered, but not in the requested shape. */
internal class UnreadableAnswerException : Exception("Gemini's answer was unreadable")

/**
 * The AI mechanic. When the OBD link comes up it scans for fault codes by
 * itself; codes it hasn't seen before get explained by Gemini and announced in
 * one spoken sentence, with the full advice on the Fault codes tile. Live
 * readings are watched for overheating and charging problems.
 *
 * Only the codes, the engine readings and the car model leave the car.
 */
object AiMechanic {

    data class State(
        /** Codes from the last scan; null until one has run. */
        val codes: List<String>? = null,
        val diagnosis: Diagnosis? = null,
        val thinking: Boolean = false,
        /** Why there's no AI advice (no key, offline, refused). */
        val note: Note? = null,
        /** The request failed in a way asking again might fix. */
        val canRetry: Boolean = false
    )

    /** Why a tile shows no AI advice; turned into text when shown, in the launcher's language. */
    sealed interface Note {
        /** No Gemini key: only the built-in table's explanations. */
        data object NoKey : Note
        /** Asking Gemini failed because of [error]. */
        data class Unavailable(val error: Throwable) : Note
    }

    private const val PREFS = "ai_mechanic"
    private const val KEY_KNOWN = "known_codes"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Scan results can come from the auto-scan and the Scan button at once.
    private val mutex = Mutex()
    private val liveWatch = LiveWatch()
    private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
        CarVoice.setContext(context)
    }

    /** Called when the OBD link comes up: scans without being asked. */
    suspend fun autoScan() {
        ObdBluetoothManager.readTroubleCodes().onSuccess { report(it, announce = true) }
    }

    /**
     * New scan results. Codes not heard before are announced when [announce];
     * the explanation always lands on the tile.
     */
    fun report(codes: List<String>, announce: Boolean) {
        scope.launch {
            mutex.withLock {
                val context = appContext ?: return@withLock
                val list = codes.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val known = prefs.getString(KEY_KNOWN, "").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
                // Remember exactly the current codes: one that is repaired and comes back is news again.
                prefs.edit().putString(KEY_KNOWN, list.joinToString(",")).apply()
                _state.value = State(codes = list)
                if (list.isNotEmpty()) explain(context, list, fresh = if (announce) list.filter { it !in known } else emptyList())
            }
        }
    }

    /** Codes were cleared from the car: forget them. */
    fun cleared() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(KEY_KNOWN)?.apply()
        _state.value = State(codes = emptyList())
    }

    /** Asks again for the current codes (after adding a key, changing language, or coming back online). */
    fun refresh() {
        val codes = _state.value.codes ?: return
        if (codes.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val context = appContext ?: return@withLock
                explain(context, codes, fresh = emptyList())
            }
        }
    }

    /** Checks one set of live readings against the warning rules. */
    fun watch(data: ObdData) {
        val alert = liveWatch.check(data, System.currentTimeMillis()) ?: return
        val context = appContext ?: return
        val config = AiSettings.load(context)
        if (!config.speak) return
        val line = MechanicLines.alert(alert, data, config.language)
        CarVoice.speak(line.text(config.language.resources(context)), config.language.locale)
    }

    /** Fills in the advice for [codes]; speaks only when some are [fresh] (new and to be announced). */
    private suspend fun explain(context: Context, codes: List<String>, fresh: List<String>) {
        val config = AiSettings.load(context)
        val say: (String) -> Unit = { if (fresh.isNotEmpty() && config.speak) CarVoice.speak(it, config.language.locale) }
        val offline = MechanicLines.newCodes(fresh).text(config.language.resources(context))
        val cacheKey = "diag_" + codes.sorted().joinToString(",") + "|" + config.engine.name + "|" + config.language.name
        val cache = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        cache.getString(cacheKey, null)?.let(MechanicPrompt::parse)?.let { cached ->
            _state.value = State(codes = codes, diagnosis = cached)
            say(cached.summary)
            return
        }
        if (config.apiKey.isBlank()) {
            _state.value = State(codes = codes, note = Note.NoKey)
            say(offline)
            return
        }

        _state.value = State(codes = codes, thinking = true)
        val prompt = MechanicPrompt.build(codes, config.engine, config.language, ObdBluetoothManager.data.value)
        GeminiClient.generate(config.apiKey, prompt, MechanicPrompt.SCHEMA)
            .mapCatching { reply -> reply.text to (MechanicPrompt.parse(reply.text) ?: throw UnreadableAnswerException()) }
            .onSuccess { (raw, diagnosis) ->
                cache.edit().putString(cacheKey, raw).apply()
                _state.value = State(codes = codes, diagnosis = diagnosis)
                say(diagnosis.summary)
            }
            .onFailure {
                _state.value = State(codes = codes, note = Note.Unavailable(it), canRetry = true)
                say(offline)
            }
    }

    /** [note] as shown on the tile, in [context]'s language. */
    fun noteText(context: Context, note: Note): String = when (note) {
        Note.NoKey -> context.getString(R.string.ai_note_no_key)
        is Note.Unavailable -> context.getString(R.string.ai_note_unavailable, describe(context, note.error))
    }

    /** A short, human reason for a failed request. Google's own error messages are shown as they come. */
    internal fun describe(context: Context, error: Throwable): String = when (error) {
        is GeminiException -> when (error.status) {
            429 -> context.getString(R.string.ai_error_quota)
            400, 401, 403 -> error.message ?: context.getString(R.string.ai_error_key_refused)
            // A 2xx that still failed: the answer had no text.
            in 200..299 -> context.getString(R.string.ai_error_empty)
            else -> error.message ?: context.getString(R.string.ai_error_gemini, error.status)
        }
        is UnreadableAnswerException -> context.getString(R.string.ai_error_unreadable)
        is IOException -> context.getString(R.string.ai_error_offline)
        else -> error.message ?: context.getString(R.string.ai_error_unknown)
    }
}
