package com.openauto.dash

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Decides when a spoken question is over from the microphone's loudness: once
 * the driver has spoken, a stretch of quiet sends it; nothing said at all
 * gives up. Pure, so it's unit-tested. Car noise can keep the level up, hence
 * the hard limit (and the Send button).
 */
internal class SilenceDetector(
    private val speechLevel: Int = 2_500,
    private val quietLevel: Int = 1_500,
    private val quietMs: Long = 1_500,
    private val waitMs: Long = 6_000,
    private val maxMs: Long = 15_000
) {
    enum class Verdict { LISTEN, DONE, NOTHING_HEARD }

    private var startedAt: Long? = null
    private var spoke = false
    private var quietSince: Long? = null

    /** One loudness reading (0..32767, as MediaRecorder gives it) at [now]. */
    fun feed(amplitude: Int, now: Long): Verdict {
        val start = startedAt ?: now.also { startedAt = it }
        when {
            amplitude >= speechLevel -> {
                spoke = true
                quietSince = null
            }
            spoke && amplitude < quietLevel -> {
                val since = quietSince ?: now.also { quietSince = it }
                if (now - since >= quietMs) return Verdict.DONE
            }
            else -> quietSince = null
        }
        if (now - start >= maxMs) return if (spoke) Verdict.DONE else Verdict.NOTHING_HEARD
        if (!spoke && now - start >= waitMs) return Verdict.NOTHING_HEARD
        return Verdict.LISTEN
    }
}

/** One question and its answer. */
data class Exchange(val heard: String, val answer: String)

/** The spoken question for Gemini, with what the mechanic already said as context. Pure, so it's unit-tested. */
internal object QuestionPrompt {

    val SCHEMA: JSONObject
        get() = JSONObject().put("type", "OBJECT").put(
            "properties",
            JSONObject()
                .put("heard", JSONObject().put("type", "STRING"))
                .put("answer", JSONObject().put("type", "STRING"))
        ).put("required", JSONArray(listOf("heard", "answer")))

    fun build(
        car: String,
        language: AiLanguage,
        codes: List<String>,
        diagnosis: Diagnosis?,
        focus: String,
        previous: Exchange?
    ): String = buildString {
        appendLine(MechanicPersona.of(car) + " You are talking with its driver.")
        appendLine("Its stored fault codes: ${codes.joinToString(", ")}. The driver is looking at $focus.")
        diagnosis?.let { d ->
            appendLine("What you already told the driver: ${d.summary} ${d.overview}".trim())
            d.codes.forEach { c ->
                val facts = listOfNotNull(
                    c.meaning.ifBlank { null },
                    c.causes.takeIf { it.isNotEmpty() }?.let { "likely causes: " + it.joinToString("; ") },
                    c.checkFirst.ifBlank { null }?.let { "check first: $it" },
                    c.repair.ifBlank { null }?.let { "usual repair: $it" },
                    c.cost.ifBlank { null }?.let { "cost: $it" },
                    c.driving.ifBlank { null }?.let { "driving: $it" }
                )
                appendLine("- ${c.code}: ${facts.joinToString(". ")}")
            }
        }
        previous?.let { appendLine("The driver's previous question was \"${it.heard}\" and you answered \"${it.answer}\".") }
        appendLine()
        appendLine("The attached recording is the driver asking you a question out loud.")
        appendLine("Answer in ${language.promptName}, with correct spelling and all accents, the way a mechanic would in person: 2 to 4 short sentences, concrete and specific to this car. The answer is read aloud, so no lists and no symbols.")
        appendLine("- heard: the driver's question as you understood it, written in ${language.promptName}.")
        appendLine("- answer: your answer. If the recording holds no clear question, say so in one sentence.")
        append("If you are not sure, say so rather than guess, and never tell the driver a fault is safe to ignore without good grounds.")
    }

    /** The question as heard and the answer; null when unusable. */
    fun parse(text: String): Exchange? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
        val answer = json.optString("answer").trim().ifEmpty { return null }
        return Exchange(json.optString("heard").trim(), answer)
    }
}

/** Records the question to a small AAC file, the format Gemini takes as is. */
internal class QuestionRecorder(private val context: Context) {
    private val file = File(context.cacheDir, "question.aac")
    private var recorder: MediaRecorder? = null

    fun start() {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
    }

    /** Loudest level since the last call, 0..32767. */
    fun amplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** Stops and returns the recording; null when nothing usable was captured. */
    fun stop(): ByteArray? {
        val r = recorder ?: return null
        recorder = null
        // stop() throws when nothing was recorded yet.
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val audio = if (ok) runCatching { file.readBytes() }.getOrNull() else null
        file.delete()
        return audio
    }

    fun release() {
        stop()
    }
}

/**
 * A question asked out loud about a fault, answered by the AI mechanic and
 * read back. The recording goes to Gemini as is, which hears it itself, so it
 * doesn't depend on the head unit having speech recognition.
 */
object AskMechanic {

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data object Thinking : State
        data class Answered(val exchange: Exchange) : State
        data object NothingHeard : State
        /** [reason] is already worded for the screen. */
        data class Failed(val reason: String) : State
    }

    private const val BUDGET_MS = 60_000L
    // Shorter than this is a click, not a question.
    private const val MIN_AUDIO_BYTES = 2_000

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recorder: QuestionRecorder? = null
    private var job: Job? = null
    private var question: Question? = null
    // The last exchange about a code, so "and how much?" makes sense.
    private var previous: Pair<String, Exchange>? = null

    private class Question(val context: Context, val focus: String, val codes: List<String>, val diagnosis: Diagnosis?)

    /** Starts listening for a question about [focus]; while listening, sends what was said. */
    fun listen(context: Context, focus: String, codes: List<String>, diagnosis: Diagnosis?) {
        when (_state.value) {
            State.Listening -> return send()
            State.Thinking -> return
            else -> Unit
        }
        val app = context.applicationContext
        val config = AiSettings.load(app)
        if (config.apiKey.isBlank()) {
            _state.value = State.Failed(AiMechanic.noteText(app, AiMechanic.Note.NoKey))
            return
        }
        val r = QuestionRecorder(app)
        try {
            r.start()
        } catch (e: Exception) {
            _state.value = State.Failed(config.language.resources(app).getString(R.string.ai_ask_mic_error))
            return
        }
        recorder = r
        question = Question(app, focus, codes, diagnosis)
        _state.value = State.Listening
        job = scope.launch {
            val detector = SilenceDetector()
            while (isActive) {
                delay(100)
                when (detector.feed(r.amplitude(), System.currentTimeMillis())) {
                    SilenceDetector.Verdict.LISTEN -> Unit
                    SilenceDetector.Verdict.DONE -> return@launch finish(heard = true)
                    SilenceDetector.Verdict.NOTHING_HEARD -> return@launch finish(heard = false)
                }
            }
        }
    }

    /** Sends what was said so far (the Send button). */
    fun send() {
        if (_state.value != State.Listening) return
        job?.cancel()
        finish(heard = true)
    }

    /** The microphone permission was refused. */
    fun micDenied(context: Context) {
        _state.value = State.Failed(AiSettings.load(context).language.resources(context).getString(R.string.ai_ask_mic_denied))
    }

    /** The sheet closed: stop listening or waiting, forget what was on screen. */
    fun cancel() {
        job?.cancel()
        recorder?.release()
        recorder = null
        _state.value = State.Idle
    }

    /** Says the last answer again. */
    fun replay(context: Context) {
        val answered = _state.value as? State.Answered ?: return
        val language = AiSettings.load(context).language
        CarVoice.speak(answered.exchange.answer, language.locale)
    }

    private fun finish(heard: Boolean) {
        val audio = recorder?.stop()
        recorder = null
        val q = question ?: return
        if (!heard || audio == null || audio.size < MIN_AUDIO_BYTES) {
            _state.value = State.NothingHeard
            return
        }
        _state.value = State.Thinking
        job = scope.launch {
            val config = AiSettings.load(q.context)
            val prompt = QuestionPrompt.build(
                CarProfileStore.current.promptDescription(), config.language, q.codes, q.diagnosis, q.focus,
                previous?.takeIf { it.first == q.focus }?.second
            )
            val reply = GeminiClient.generate(
                config.apiKey, prompt, QuestionPrompt.SCHEMA, budgetMs = BUDGET_MS, audio = audio, audioMime = "audio/aac"
            )
            val exchange = reply.getOrNull()?.let { QuestionPrompt.parse(it.text) }
            if (exchange != null) {
                previous = q.focus to exchange
                _state.value = State.Answered(exchange)
                CarVoice.speak(exchange.answer, config.language.locale)
            } else {
                val why = reply.exceptionOrNull()?.let { AiMechanic.describe(q.context, it) }
                    ?: q.context.getString(R.string.ai_error_unreadable)
                _state.value = State.Failed(config.language.resources(q.context).getString(R.string.ai_ask_failed, why))
            }
        }
    }
}
