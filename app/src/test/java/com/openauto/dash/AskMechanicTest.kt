package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.util.Base64

/** Asking the mechanic out loud: when a question is over, what Gemini is told, and reading its answer. */
class AskMechanicTest {

    private fun run(detector: SilenceDetector, levels: List<Int>, stepMs: Long = 100): SilenceDetector.Verdict {
        var verdict = SilenceDetector.Verdict.LISTEN
        levels.forEachIndexed { i, level ->
            verdict = detector.feed(level, i * stepMs)
            if (verdict != SilenceDetector.Verdict.LISTEN) return verdict
        }
        return verdict
    }

    @Test
    fun aQuestionIsSentAfterASecondAndAHalfOfQuiet() {
        val speech = List(20) { 6_000 }
        assertEquals(SilenceDetector.Verdict.LISTEN, run(SilenceDetector(), speech + List(14) { 400 }))
        assertEquals(SilenceDetector.Verdict.DONE, run(SilenceDetector(), speech + List(16) { 400 }))
    }

    @Test
    fun aPauseBetweenWordsDoesNotCutTheQuestion() {
        val levels = List(10) { 6_000 } + List(8) { 400 } + List(10) { 6_000 } + List(10) { 400 }
        assertEquals(SilenceDetector.Verdict.LISTEN, run(SilenceDetector(), levels))
    }

    @Test
    fun silenceGivesUpAndNoiseStopsAtTheLimit() {
        assertEquals(SilenceDetector.Verdict.NOTHING_HEARD, run(SilenceDetector(), List(61) { 300 }))
        // A loud cabin never goes quiet: the question still goes after 15 s.
        assertEquals(SilenceDetector.Verdict.DONE, run(SilenceDetector(), List(151) { 3_000 }))
    }

    @Test
    fun thePromptCarriesTheCarTheCodesWhatWasSaidAndTheLastExchange() {
        val advice = CodeAdvice("P1352", "Relais de préchauffage", listOf("Boîtier défectueux"), "Le fusible", cost = "100 à 250 €")
        val d = Diagnosis(Severity.SOON, "Préchauffage à vérifier.", listOf(advice))
        val prompt = QuestionPrompt.build(
            CarProfile.PRESET.promptDescription(), AiLanguage.FRENCH, listOf("P1352"), d, "P1352",
            Exchange("Je peux rouler ?", "Oui, prudemment.")
        )
        assertTrue(prompt.contains("1.6 HDi 110"))
        assertTrue(prompt.contains("P1352: Relais de préchauffage. likely causes: Boîtier défectueux. check first: Le fusible. cost: 100 à 250 €"))
        assertTrue(prompt.contains("previous question was \"Je peux rouler ?\""))
        assertTrue(prompt.contains("Answer in French"))
        assertTrue(prompt.contains("attached recording"))
    }

    @Test
    fun theAnswerIsReadBack() {
        assertEquals(
            Exchange("Combien ça coûte ?", "Entre cent et deux cent cinquante euros."),
            QuestionPrompt.parse("""{"heard":"Combien ça coûte ?","answer":"Entre cent et deux cent cinquante euros."}""")
        )
        assertNull(QuestionPrompt.parse("""{"heard":"?","answer":"  "}"""))
        assertNull(QuestionPrompt.parse("no json"))
    }

    @Test
    fun theRecordingTravelsWithTheQuestion() {
        val body = JSONObject(GeminiClient.requestBody("Question ?", QuestionPrompt.SCHEMA, byteArrayOf(1, 2, 3), "audio/aac"))
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("Question ?", parts.getJSONObject(0).getString("text"))
        val audio = parts.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("audio/aac", audio.getString("mimeType"))
        assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), audio.getString("data"))
        assertEquals("application/json", body.getJSONObject("generationConfig").getString("responseMimeType"))
    }
}
