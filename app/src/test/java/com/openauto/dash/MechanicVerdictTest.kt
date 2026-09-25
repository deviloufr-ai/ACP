package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car's own rules around the AI's verdict: how serious a fault may be rated, and who the model answers as. */
class MechanicVerdictTest {

    private val line: (Severity) -> String = { "rules say ${it.name}" }
    private fun verdict(severity: Severity, vararg codes: String) =
        Diagnosis(severity, "The model's sentence.", codes.map { CodeAdvice(it, "", emptyList(), "") })

    @Test
    fun aMisfireIsNeverFineWhateverTheModelSays() {
        val floor = SeverityFloor.of(listOf("P0302"), coolantC = 88, hotC = 90)
        assertEquals(Severity.SOON, floor)
        val d = SeverityFloor.apply(verdict(Severity.OK, "P0302"), floor, line)
        assertEquals(Severity.SOON, d.severity)
        assertTrue(d.raisedByRules)
        // The spoken sentence follows the verdict, never "keep driving" under an amber band.
        assertEquals("rules say SOON", d.summary)
    }

    @Test
    fun lowOilPressureAndAnOverheatingEngineMeanStop() {
        assertEquals(Severity.STOP, SeverityFloor.of(listOf("P0420", "p0524 "), coolantC = 85, hotC = 90))
        assertEquals(Severity.STOP, SeverityFloor.of(listOf("P0217"), coolantC = 0, hotC = 90))
        assertEquals(Severity.STOP, SeverityFloor.of(listOf("P0420"), coolantC = 112, hotC = 90))
    }

    @Test
    fun overheatingIsJudgedAgainstTheEnginesOwnTemperature() {
        assertEquals(110, Overheat.alarmC(90))
        assertEquals(100, Overheat.clearC(90))
        // An engine that runs at 105 °C isn't overheating at 112.
        assertEquals(Severity.OK, SeverityFloor.of(listOf("P0420"), coolantC = 112, hotC = 105))
    }

    @Test
    fun brakesStabilityAndAirbagsAreNeverFine() {
        assertEquals(Severity.SOON, SeverityFloor.of(listOf("C0035"), coolantC = 0, hotC = 90))
        assertEquals(Severity.SOON, SeverityFloor.of(listOf("B0012"), coolantC = 0, hotC = 90))
        assertEquals(Severity.SOON, SeverityFloor.of(listOf("U0100"), coolantC = 0, hotC = 90))
    }

    @Test
    fun codesTheRulesDontKnowAreLeftToTheModel() {
        val floor = SeverityFloor.of(listOf("P0420", "P1352", "B1234"), coolantC = 90, hotC = 90)
        assertEquals(Severity.OK, floor)
        val d = verdict(Severity.OK, "P0420")
        assertSame(d, SeverityFloor.apply(d, floor, line))
    }

    @Test
    fun theRulesNeverLowerTheModelsVerdict() {
        val d = verdict(Severity.STOP, "P0302")
        val bounded = SeverityFloor.apply(d, Severity.SOON, line)
        assertEquals(Severity.STOP, bounded.severity)
        assertEquals("The model's sentence.", bounded.summary)
        assertFalse(bounded.raisedByRules)
    }

    @Test
    fun theMechanicKnowsTheDriversCarNotOneMakeWrittenIn() {
        val persona = MechanicPersona.of("Renault Clio IV 1.5 dCi 90 (diesel, 90 hp)")
        assertTrue(persona.contains("Renault Clio IV 1.5 dCi 90"))
        assertFalse(persona.contains("Citroën"))
    }
}
