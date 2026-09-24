package com.openauto.dash

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/*
 * EXPERIMENTAL: readings generic OBD doesn't give (particle-filter soot load,
 * regeneration, additive level...) live behind maker-specific requests. Gemini
 * proposes candidate requests for this car; each is tried on the adapter twice
 * and only kept when the car answers with a plausible, stable value. What
 * survives is then read every few seconds alongside the standard PIDs. The
 * AI can be wrong, so nothing here is trusted until the car itself confirms it.
 */

/** The readings worth hunting for. Names are persisted, so never rename an entry. */
enum class ExtraReading(@StringRes val labelRes: Int, val unit: String) {
    SOOT_LOAD(R.string.explore_soot_load, "%"),
    DPF_TEMP(R.string.explore_dpf_temp, "°C"),
    DPF_PRESSURE(R.string.explore_dpf_pressure, "mbar"),
    REGEN_ACTIVE(R.string.explore_regen, ""),
    KM_SINCE_REGEN(R.string.explore_km_since_regen, "km"),
    ADDITIVE_LEVEL(R.string.explore_additive, "%"),
    OIL_TEMP(R.string.explore_oil_temp, "°C")
}

/**
 * One request to try: an optional CAN [header] (the computer to address, e.g.
 * 7E0 for the engine), the [request] bytes, and the [formula] turning the
 * reply's data bytes A, B, C, D... into a value expected within [min]..[max].
 *
 * Many makers only serve their own requests on their own diagnostic addresses
 * (PSA's engine computer listens on 6A8 and answers on 688, not 7E0/7E8), and
 * only inside a diagnostic [session] ("10C0", "1003"): [replyAddress] is where
 * the answer comes from, so the adapter listens there.
 */
data class PidCandidate(
    val reading: ExtraReading,
    val header: String?,
    val request: String,
    val formula: String,
    val min: Double,
    val max: Double,
    val fromAi: Boolean,
    val replyAddress: String? = null,
    val session: String? = null
) {
    /** How the reply starts: the request's first byte + 0x40, then the rest echoed ("221A5B" → "621A5B"). */
    val replyHeader: String
        get() {
            val mode = request.take(2).toIntOrNull(16) ?: return request
            return String.format(Locale.US, "%02X", mode + 0x40) + request.drop(2)
        }

    fun toJson(): JSONObject = JSONObject().put("reading", reading.name).putOpt("header", header).put("request", request)
        .put("formula", formula).put("min", min).put("max", max).put("ai", fromAi)
        .putOpt("reply", replyAddress).putOpt("session", session)

    /** How the request is addressed, as shown: "6A8→688 10C0 2181", "7E0 22F40C", "015C". */
    val label: String
        get() = listOfNotNull(header?.let { h -> replyAddress?.let { "$h→$it" } ?: h }, session, request).joinToString(" ")

    companion object {
        fun fromJson(o: JSONObject): PidCandidate? {
            val reading = ExtraReading.entries.firstOrNull { it.name == o.optString("reading") } ?: return null
            return PidCandidate(
                reading, o.optString("header").takeIf { it.isNotBlank() }, o.optString("request"), o.optString("formula"),
                o.optDouble("min", Double.NEGATIVE_INFINITY), o.optDouble("max", Double.POSITIVE_INFINITY), o.optBoolean("ai"),
                o.optString("reply").takeIf { it.isNotBlank() }, o.optString("session").takeIf { it.isNotBlank() }
            )
        }
    }
}

/** How a candidate fared on the adapter. */
enum class ProbeVerdict { NO_ANSWER, REFUSED, UNREADABLE, IMPLAUSIBLE, UNSTABLE, OK }

data class ProbeResult(val candidate: PidCandidate, val verdict: ProbeVerdict, val value: Double? = null, val reply: String? = null)

/** A verified reading's latest value. */
data class ExtraValue(val value: Double, val at: Long)

/**
 * Evaluates the little arithmetic formulas OBD tables use ("(A*256+B)/10-40",
 * "A*100/255"): + - * / parentheses, numbers, and the letters A..H for the
 * data bytes. Pure, unit-tested.
 */
object Formula {
    fun eval(expr: String, bytes: List<Int>): Double? {
        val src = expr.replace(" ", "").uppercase(Locale.ROOT)
        if (src.isEmpty()) return null
        val parser = Parser(src, bytes)
        val v = parser.expression() ?: return null
        return if (parser.done && v.isFinite()) v else null
    }

    /** Recursive descent over expression → term → factor; local functions can't call each other, so a class. */
    private class Parser(private val src: String, private val bytes: List<Int>) {
        private var pos = 0
        val done: Boolean get() = pos == src.length
        private fun peek(): Char? = src.getOrNull(pos)

        fun expression(): Double? {
            var left = term() ?: return null
            while (peek() == '+' || peek() == '-') {
                val op = src[pos++]
                val right = term() ?: return null
                left = if (op == '+') left + right else left - right
            }
            return left
        }

        private fun term(): Double? {
            var left = factor() ?: return null
            while (peek() == '*' || peek() == '/') {
                val op = src[pos++]
                val right = factor() ?: return null
                left = if (op == '*') left * right else if (right == 0.0) return null else left / right
            }
            return left
        }

        private fun factor(): Double? {
            val c = peek() ?: return null
            return when {
                c == '(' -> {
                    pos++
                    val v = expression() ?: return null
                    if (peek() != ')') return null
                    pos++
                    v
                }
                c == '-' -> {
                    pos++
                    factor()?.let { -it }
                }
                c in 'A'..'H' -> {
                    pos++
                    bytes.getOrNull(c - 'A')?.toDouble()
                }
                c.isDigit() || c == '.' -> {
                    val start = pos
                    while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
                    src.substring(start, pos).toDoubleOrNull()
                }
                else -> null
            }
        }
    }
}

/** Reading a candidate's reply. Pure, unit-tested. */
object PidProbe {
    /**
     * The value in [reply] for [c], or why there is none: silence, a negative
     * response ("7F 22 31" = request out of range), an unreadable reply, or a
     * value outside what the reading can be.
     */
    fun read(c: PidCandidate, reply: String?): ProbeResult {
        val text = frames(reply)
        val bytes = ObdParser.dataBytes(text, c.replyHeader)
        if (bytes == null) {
            if (text.isEmpty() || text.contains("NO DATA", ignoreCase = true) || text.contains("UNABLE", ignoreCase = true)) {
                return ProbeResult(c, ProbeVerdict.NO_ANSWER, reply = reply)
            }
            val hex = text.uppercase(Locale.ROOT).replace(Regex("[^0-9A-F]"), "")
            if (hex.startsWith("7F") || hex.contains("7F" + c.request.take(2))) return ProbeResult(c, ProbeVerdict.REFUSED, reply = reply)
            return ProbeResult(c, ProbeVerdict.UNREADABLE, reply = reply)
        }
        val value = Formula.eval(c.formula, bytes) ?: return ProbeResult(c, ProbeVerdict.UNREADABLE, reply = reply)
        if (value < c.min || value > c.max) return ProbeResult(c, ProbeVerdict.IMPLAUSIBLE, value, reply)
        return ProbeResult(c, ProbeVerdict.OK, value, reply)
    }

    /**
     * The reply's bytes as one line. A long answer (maker blocks such as
     * "2181" run to dozens of bytes) comes from the adapter split over lines:
     * the byte count first ("01B"), then each frame numbered ("0: 61 81 ...",
     * "1: ..."). Those counters aren't data and would shift every byte after
     * them, so they go; a "7F 22 78" (busy, answer follows) line goes too.
     */
    internal fun frames(reply: String?): String {
        val lines = reply.orEmpty().split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val multi = lines.any { MULTI_FRAME_LINE.containsMatchIn(it) }
        return lines
            .filterNot { multi && LENGTH_LINE.matches(it) }
            .filterNot { it.uppercase(Locale.ROOT).replace(" ", "").let { l -> l.length == 6 && l.startsWith("7F") && l.endsWith("78") } }
            .joinToString(" ") { if (multi) MULTI_FRAME_LINE.replace(it, "") else it }
            .trim()
    }

    private val MULTI_FRAME_LINE = Regex("^[0-9A-F]:\\s*", RegexOption.IGNORE_CASE)
    private val LENGTH_LINE = Regex("^[0-9A-F]{3}$", RegexOption.IGNORE_CASE)

    /**
     * Diagnostic sessions a candidate may open: default and extended ones
     * only (UDS 01/03, KWP 81/92, PSA's C0). Never 02 or 85, the programming
     * sessions, which can stop a running engine.
     */
    val SAFE_SESSIONS = setOf("1001", "1003", "1081", "1092", "10C0")

    /**
     * Two reads must both be plausible and agree within [tolerance] of the
     * range: a byte that isn't what we think jumps about, or reads FF.
     */
    fun stable(first: ProbeResult, second: ProbeResult, tolerance: Double = 0.25): ProbeResult {
        if (first.verdict != ProbeVerdict.OK) return first
        if (second.verdict != ProbeVerdict.OK) return second
        val c = first.candidate
        val span = (c.max - c.min).takeIf { it.isFinite() && it > 0 } ?: 1.0
        val a = first.value ?: return first
        val b = second.value ?: return second
        return if (kotlin.math.abs(a - b) <= span * tolerance) second else ProbeResult(c, ProbeVerdict.UNSTABLE, b, second.reply)
    }

    /** Standard (SAE J1979) requests every car may support; tried before asking the AI. */
    val STANDARD: List<PidCandidate> = listOf(
        PidCandidate(ExtraReading.OIL_TEMP, null, "015C", "A-40", -30.0, 150.0, fromAi = false),
        PidCandidate(ExtraReading.DPF_TEMP, null, "017C", "(A*256+B)/10-40", -30.0, 900.0, fromAi = false)
    )

    val SCHEMA: JSONObject
        get() {
            fun str() = JSONObject().put("type", "STRING")
            fun num() = JSONObject().put("type", "NUMBER")
            val item = JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("reading", str().put("enum", JSONArray(ExtraReading.entries.map { it.name })))
                    .put("header", str())
                    .put("replyAddress", str())
                    .put("session", str())
                    .put("request", str())
                    .put("formula", str())
                    .put("min", num())
                    .put("max", num()))
                .put("required", JSONArray(listOf("reading", "header", "request", "formula", "min", "max")))
            return JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put("items", JSONObject().put("type", "ARRAY").put("items", item).put("maxItems", 20)))
                .put("required", JSONArray(listOf("items")))
        }

    fun prompt(car: String): String = buildString {
        appendLine("You are an automotive diagnostics reference. For this exact car, as sold in Europe: \"$car\", list the manufacturer-specific diagnostic requests, sent through an ELM327 adapter on the CAN bus, that read these values from the engine control unit:")
        appendLine(ExtraReading.entries.joinToString("\n") { "- ${it.name}" + if (it.unit.isNotEmpty()) " (${it.unit})" else " (0 = no, 1 = yes)" })
        appendLine("For each: header = the CAN request address to set with ATSH; replyAddress = the CAN address the computer answers from; session = the diagnostic session request to send first (\"10C0\", \"1003\", \"1081\"...), or empty when none is needed; request = the request bytes as hex without spaces (UDS ReadDataByIdentifier \"22\" + 2-byte DID, or the older \"21\" + local identifier); formula = how to turn the data bytes that follow the positive response echo into the value, using the letters A, B, C, D for those bytes (e.g. \"(A*256+B)/10-40\" or \"A*100/255\"); min and max = the plausible range of the value.")
        appendLine("Use the addresses and session the manufacturer's own workshop tool uses for this computer, not the generic OBD ones (7E0 answering on 7E8), unless this car really serves these requests there: many makers don't. For example, PSA (Peugeot, Citroën, DS) engine computers are reached on their own diagnostic addresses and need a diagnostic session opened first. If a value lives in another computer (e.g. the particle-filter additive module), give that computer's addresses.")
        appendLine("Give several candidates for a value when the identifier differs between software versions. Only give requests you have real grounds for (workshop tool data, community-documented PIDs for this engine family); leave out readings you don't know. Every candidate is tested on the car and rejected if it doesn't answer sensibly, so a wrong guess costs little, but do not invent identifiers.")
    }

    fun readCandidates(raw: String): List<PidCandidate> {
        val o = JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val items = o.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val it = items.optJSONObject(i) ?: return@mapNotNull null
            val reading = ExtraReading.entries.firstOrNull { r -> r.name == it.optString("reading") } ?: return@mapNotNull null
            val request = it.optString("request").uppercase(Locale.ROOT).replace(Regex("[^0-9A-F]"), "")
            if (request.length < 4 || request.length % 2 != 0) return@mapNotNull null
            // Only reads: modes 01 (live data), 21 and 22 (maker data). Nothing that writes or resets.
            if (request.take(2) !in setOf("01", "21", "22")) return@mapNotNull null
            val header = it.optString("header").uppercase(Locale.ROOT).replace(Regex("[^0-9A-F]"), "").takeIf { h -> h.length in 3..8 }
            val replyAddress = it.optString("replyAddress").uppercase(Locale.ROOT).replace(Regex("[^0-9A-F]"), "")
                .takeIf { a -> header != null && (a.length == 3 || a.length == 8) }
            val session = it.optString("session").uppercase(Locale.ROOT).replace(Regex("[^0-9A-F]"), "").takeIf { s -> s.isNotEmpty() }
            // Anything but a known-safe session is dropped along with its request: it may only answer inside it.
            if (session != null && (session !in SAFE_SESSIONS || header == null)) return@mapNotNull null
            val formula = it.optString("formula").takeIf { f -> f.isNotBlank() } ?: return@mapNotNull null
            if (Formula.eval(formula, List(8) { 1 }) == null) return@mapNotNull null
            PidCandidate(
                reading, header, request, formula, it.optDouble("min", Double.NEGATIVE_INFINITY), it.optDouble("max", Double.POSITIVE_INFINITY),
                fromAi = true, replyAddress = replyAddress, session = session
            )
        }.distinctBy { listOf(it.header, it.replyAddress, it.session, it.request) }
    }
}

/** Runs the search, keeps what the car confirmed, and reads it while connected. */
object PidExplorer {
    private const val PREFS = "pid_explorer"
    private const val POLL_MS = 8_000L
    private const val PROBE_TIMEOUT_MS = 3_000L
    private const val BUDGET_MS = 90_000L

    data class State(
        /** Requests the car confirmed, one per reading. */
        val verified: List<PidCandidate> = emptyList(),
        val searching: Boolean = false,
        /** Each candidate tried in the current or last search, in order. */
        val results: List<ProbeResult> = emptyList(),
        val error: String? = null,
        /** The search ran to the end (and [results] is its whole story). */
        val searched: Boolean = false
    )

    private var appContext: Context? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _readings = MutableStateFlow<Map<ExtraReading, ExtraValue>>(emptyMap())
    /** The latest value of each verified reading; empty until the adapter connects. */
    val readings: StateFlow<Map<ExtraReading, ExtraValue>> = _readings.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pollJob: Job? = null
    private var verifiedFor: Int = 0

    fun setContext(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
        scope.launch {
            ObdBluetoothManager.connectionState.collect { s ->
                if (s == ObdConnectionState.CONNECTED) startPolling() else stopPolling()
            }
        }
    }

    /** The soot load, when the car gave it up: the particle-filter tile's real reading. */
    val sootLoad: Double? get() = _readings.value[ExtraReading.SOOT_LOAD]?.value

    /**
     * Tries the standard requests, then the AI's, on the connected adapter.
     * Only readings not yet verified are hunted for; the car's answer decides.
     */
    suspend fun search(): Result<Unit> = mutex.withLock {
        val context = appContext ?: return Result.failure(IllegalStateException("no context"))
        if (DemoMode.isOn) return Result.failure(IllegalStateException(context.getString(R.string.explore_not_in_demo)))
        if (ObdBluetoothManager.connectionState.value != ObdConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException(context.getString(R.string.vehicle_obd_not_connected)))
        }
        _state.value = _state.value.copy(searching = true, results = emptyList(), error = null, searched = false)
        stopPolling()
        try {
            val car = CarProfileStore.current
            val have = _state.value.verified.map { it.reading }.toMutableSet()
            val results = mutableListOf<ProbeResult>()
            fun publish() { _state.value = _state.value.copy(results = results.toList()) }

            for (c in PidProbe.STANDARD) {
                if (c.reading in have) continue
                val r = probe(c)
                results += r
                publish()
                if (r.verdict == ProbeVerdict.OK) keep(c, car)
                if (r.verdict == ProbeVerdict.OK) have += c.reading
            }

            val config = AiSettings.load(context)
            if (config.apiKey.isBlank()) {
                _state.value = _state.value.copy(searching = false, searched = true, error = context.getString(R.string.car_fetch_no_key))
                return Result.success(Unit)
            }
            GeminiClient.reachGoogle().exceptionOrNull()?.let {
                _state.value = _state.value.copy(searching = false, searched = true, error = AiMechanic.describe(context, it))
                return Result.success(Unit)
            }
            val candidates = GeminiClient.generate(config.apiKey, PidProbe.prompt(car.promptDescription()), PidProbe.SCHEMA, budgetMs = BUDGET_MS)
                .mapCatching { runCatching { PidProbe.readCandidates(it.text) }.getOrElse { throw UnreadableAnswerException() } }
                .getOrElse {
                    _state.value = _state.value.copy(searching = false, searched = true, error = AiMechanic.describe(context, it))
                    return Result.success(Unit)
                }
            for (c in candidates) {
                if (c.reading in have) continue
                val r = probe(c)
                results += r
                publish()
                if (r.verdict == ProbeVerdict.OK) {
                    keep(c, car)
                    have += c.reading
                }
            }
            _state.value = _state.value.copy(searching = false, searched = true)
            Result.success(Unit)
        } finally {
            if (_state.value.searching) _state.value = _state.value.copy(searching = false, searched = true)
            if (ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED) startPolling()
        }
    }

    /** Forgets every confirmed request (and the values read from them). */
    fun forget() {
        _state.value = State()
        _readings.value = emptyMap()
        save()
    }

    /** Two reads a moment apart; both must make sense and agree. */
    private suspend fun probe(c: PidCandidate): ProbeResult {
        val first = PidProbe.read(c, ObdBluetoothManager.query(c.header, c.request, PROBE_TIMEOUT_MS, c.replyAddress, c.session))
        if (first.verdict != ProbeVerdict.OK) return first
        delay(400)
        val second = PidProbe.read(c, ObdBluetoothManager.query(c.header, c.request, PROBE_TIMEOUT_MS, c.replyAddress, c.session))
        return PidProbe.stable(first, second)
    }

    private fun keep(c: PidCandidate, car: CarProfile) {
        _state.value = _state.value.copy(verified = _state.value.verified.filter { it.reading != c.reading } + c)
        verifiedFor = car.promptDescription().hashCode()
        save()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val list = _state.value.verified
                if (list.isNotEmpty() && !DemoMode.isOn && !_state.value.searching &&
                    ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED
                ) {
                    val now = System.currentTimeMillis()
                    val fresh = _readings.value.toMutableMap()
                    for (c in list) {
                        val r = PidProbe.read(c, ObdBluetoothManager.query(c.header, c.request, PROBE_TIMEOUT_MS, c.replyAddress, c.session))
                        if (r.verdict == ProbeVerdict.OK && r.value != null) fresh[c.reading] = ExtraValue(r.value, now)
                    }
                    _readings.value = fresh
                }
                delay(POLL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        _readings.value = emptyMap()
    }

    private fun load() {
        val p = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        verifiedFor = p.getInt("for", 0)
        val raw = p.getString("verified", null) ?: return
        val list = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { PidCandidate.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        _state.value = State(verified = list)
    }

    private fun save() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putInt("for", verifiedFor)
            ?.putString("verified", JSONArray(_state.value.verified.map { it.toJson() }).toString())
            ?.apply()
    }
}
