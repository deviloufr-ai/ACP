package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI mechanic's pure parts: the question, reading the answer, and the live-reading rules. */
class AiMechanicTest {

    @Test
    fun promptNamesTheCarEngineCodesLanguageAndReadings() {
        val prompt = MechanicPrompt.build(
            listOf("P0128", "P0480"),
            CarEngine.HDI_16,
            AiLanguage.FRENCH,
            ObdData(rpm = 850, coolantTempC = 72, voltage = 14.2)
        )
        assertTrue(prompt.contains("C4 Picasso (2011)"))
        assertTrue(prompt.contains("1.6 HDi diesel"))
        assertTrue(prompt.contains("P0128, P0480"))
        assertTrue(prompt.contains("Answer in French"))
        assertTrue(prompt.contains("engine running at 850 rpm"))
        assertTrue(prompt.contains("coolant 72 °C"))
        assertTrue(prompt.contains("battery 14.2 V"))
    }

    @Test
    fun promptLeavesOutReadingsTheCarDidNotReport() {
        val prompt = MechanicPrompt.build(listOf("P0128"), CarEngine.HDI_16, AiLanguage.ENGLISH, ObdData())
        assertTrue(prompt.contains("engine not running"))
        assertFalse(prompt.contains("coolant"))
        assertFalse(prompt.contains("battery"))
    }

    @Test
    fun answerIsReadIncludingCodeFences() {
        val answer = """
            ```json
            {"severity":"soon","summary":"Le thermostat reste ouvert.",
             "codes":[{"code":"p0128","meaning":"Moteur trop froid","causes":["Thermostat bloqué ouvert"," Sonde de température "],"check_first":"Thermostat"}]}
            ```
        """.trimIndent()
        val d = MechanicPrompt.parse(answer)!!
        assertEquals(Severity.SOON, d.severity)
        assertEquals("Le thermostat reste ouvert.", d.summary)
        assertEquals(1, d.codes.size)
        assertEquals("P0128", d.codes[0].code)
        assertEquals(listOf("Thermostat bloqué ouvert", "Sonde de température"), d.codes[0].causes)
        assertEquals("Thermostat", d.codes[0].checkFirst)
    }

    @Test
    fun oddSeverityCountsAsSoonAndMissingFieldsAreTolerated() {
        val d = MechanicPrompt.parse("""{"severity":"maybe","summary":"Check it.","codes":[{"code":"P0101"},{}]}""")!!
        assertEquals(Severity.SOON, d.severity)
        assertEquals(listOf("P0101"), d.codes.map { it.code })
        assertTrue(d.codes[0].causes.isEmpty())
    }

    @Test
    fun unusableAnswersAreRejected() {
        assertNull(MechanicPrompt.parse("Sorry, I can't help."))
        assertNull(MechanicPrompt.parse("""{"severity":"ok","codes":[]}"""))
        assertNull(MechanicPrompt.parse("{not json"))
    }

    @Test
    fun geminiTextSkipsThoughtParts() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"{\"a\":"},{"text":"1}"}]}}]}"""
        assertEquals("{\"a\":1}", GeminiClient.answerText(body))
        assertNull(GeminiClient.answerText("""{"candidates":[]}"""))
        assertEquals(
            "API key not valid.",
            GeminiClient.errorMessage("""{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT"}}""")
        )
    }

    @Test
    fun overheatingIsSaidOnceUntilTheEngineCoolsDown() {
        val w = LiveWatch()
        var t = 0L
        fun sample(c: Int) = w.check(ObdData(rpm = 900, coolantTempC = c, voltage = 14.0), t).also { t += 500 }
        assertNull(sample(95))
        assertEquals(LiveWatch.Alert.OVERHEAT, sample(111))
        assertNull(sample(112))
        assertNull(sample(104)) // still hot: stays quiet
        assertNull(sample(98))  // cooled down: re-armed
        assertEquals(LiveWatch.Alert.OVERHEAT, sample(110))
    }

    @Test
    fun notChargingNeedsTwoMinutesOfLowVoltageWhileRunning() {
        val w = LiveWatch()
        var t = 0L
        while (t < LiveWatch.NOT_CHARGING_MS) {
            assertNull(w.check(ObdData(rpm = 900, voltage = 12.2), t))
            t += 500
        }
        assertEquals(LiveWatch.Alert.NOT_CHARGING, w.check(ObdData(rpm = 900, voltage = 12.2), t))
        assertNull(w.check(ObdData(rpm = 900, voltage = 12.2), t + 500))
    }

    @Test
    fun aGapInReadingsRestartsTheTimer() {
        val w = LiveWatch()
        assertNull(w.check(ObdData(rpm = 900, voltage = 12.2), 0))
        // Adapter dropped for a minute, then two more minutes minus a beat.
        var t = 60_000L
        while (t < 60_000L + LiveWatch.NOT_CHARGING_MS) {
            assertNull(w.check(ObdData(rpm = 900, voltage = 12.2), t))
            t += 500
        }
    }

    @Test
    fun crankingDipIsNotAWeakBattery() {
        val w = LiveWatch()
        assertNull(w.check(ObdData(rpm = 0, voltage = 12.5), 0))
        assertNull(w.check(ObdData(rpm = 0, voltage = 10.5), 500)) // cranking
        assertNull(w.check(ObdData(rpm = 800, voltage = 14.1), 1000))
    }

    @Test
    fun weakBatteryAtRestIsSaidAfterTenSeconds() {
        val w = LiveWatch()
        var t = 0L
        while (t < LiveWatch.WEAK_BATTERY_MS) {
            assertNull(w.check(ObdData(rpm = 0, voltage = 11.7), t))
            t += 500
        }
        assertEquals(LiveWatch.Alert.WEAK_BATTERY, w.check(ObdData(rpm = 0, voltage = 11.7), t))
    }

    @Test
    fun implausibleVoltsAreIgnored() {
        val w = LiveWatch()
        var t = 0L
        while (t <= LiveWatch.NOT_CHARGING_MS + 1000) {
            assertNull(w.check(ObdData(rpm = 900, voltage = 16.9), t))
            assertNull(w.check(ObdData(rpm = 0, voltage = 0.0), t + 1))
            t += 500
        }
    }

    @Test
    fun offlineLinesSpellCodesOutInTheChosenLanguage() {
        assertEquals(
            "Nouveau code défaut moteur : P 0 1 2 8. Détails à l'écran.",
            MechanicLines.newCodes(listOf("P0128"), AiLanguage.FRENCH)
        )
        assertEquals(
            "New engine fault codes: P 0 1 2 8, P 0 4 8 0. Details are on screen.",
            MechanicLines.newCodes(listOf("P0128", "P0480"), AiLanguage.ENGLISH)
        )
        assertTrue(
            MechanicLines.alert(LiveWatch.Alert.NOT_CHARGING, ObdData(voltage = 12.1), AiLanguage.FRENCH).contains("12,1 volts")
        )
    }
}
