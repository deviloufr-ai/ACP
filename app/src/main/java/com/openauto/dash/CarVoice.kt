package com.openauto.dash

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Speaks short sentences through the head unit's text-to-speech, the way a
 * navigation app gives directions: music ducks while it talks, then returns.
 * Sentences asked for before the engine is up are queued, not lost.
 */
object CarVoice {

    private const val TAG = "CarVoice"

    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var appContext: Context? = null
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queued = mutableListOf<Pair<String, Locale>>()
    private var focus: AudioFocusRequest? = null
    private var nextId = 0
    // Utterances handed to the engine and not finished yet (main thread).
    private var talking = 0
    // What was asked to be said lately, newest last, so one feature doesn't repeat another.
    private val recent = ArrayDeque<Pair<Long, String>>()
    private const val RECENT_MS = 10 * 60_000L

    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    /** Says [text] in [locale]; silently skipped when the unit has no voice for that language. */
    fun speak(text: String, locale: Locale) {
        // Noted when asked, not when spoken, so a check right after already sees it.
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.addLast(now to text)
            while (recent.first().first < now - RECENT_MS) recent.removeFirst()
        }
        main.post {
            if (text.isBlank()) return@post
            val engine = engine() ?: return@post
            if (!ready) {
                queued.add(text to locale)
                return@post
            }
            say(engine, text, locale)
        }
    }

    /** What was asked to be said since [time] (the last ten minutes at most). */
    fun saidSince(time: Long): List<String> =
        synchronized(recent) { recent.filter { it.first >= time }.map { it.second } }

    /**
     * Whether the unit can speak [locale]: true / false, or null while the
     * engine is still starting. Main thread.
     */
    fun canSpeak(locale: Locale): Boolean? {
        val engine = engine() ?: return false
        if (!ready) return null
        return voiceFor(engine, locale) != null
    }

    /** [locale] itself, else any voice of the same language (a unit may only have en-US or fr-CA). */
    private fun voiceFor(engine: TextToSpeech, locale: Locale): Locale? =
        listOf(locale, Locale(locale.language)).firstOrNull {
            engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE
        }

    private fun engine(): TextToSpeech? {
        tts?.let { return it }
        val context = appContext ?: return null
        return TextToSpeech(context) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.let { engine -> queued.forEach { (t, l) -> say(engine, t, l) } }
                } else {
                    Log.w(TAG, "Text-to-speech failed to start ($status)")
                    tts = null
                }
                queued.clear()
            }
        }.also { engine ->
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = releaseFocusWhenQuiet(finished = 1)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = releaseFocusWhenQuiet(finished = 1)
            })
            tts = engine
        }
    }

    private fun say(engine: TextToSpeech, text: String, locale: Locale) {
        val voice = voiceFor(engine, locale)
        if (voice == null || engine.setLanguage(voice) < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "No ${locale.displayLanguage} voice installed; not speaking")
            return
        }
        requestFocus()
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, "carvoice-${nextId++}") == TextToSpeech.SUCCESS) {
            talking++
        } else {
            releaseFocusWhenQuiet(finished = 0)
        }
    }

    private fun requestFocus() {
        if (focus != null) return
        val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        audio.requestAudioFocus(request)
        focus = request
    }

    // Utterance callbacks arrive on a binder thread; hop to main and only let
    // the music back up once nothing else is queued.
    private fun releaseFocusWhenQuiet(finished: Int) {
        main.post {
            talking = (talking - finished).coerceAtLeast(0)
            if (talking > 0) return@post
            val request = focus ?: return@post
            (appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocusRequest(request)
            focus = null
        }
    }
}
