package com.openauto.dash

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Asking the Gemini models: best first, the next one only when the first is slow or refuses. */
class GeminiHedgeTest {

    private val models = listOf("best", "second", "lite")

    @Test
    fun theBestModelIsAskedFirstAndTheLiteOnesLast() {
        assertEquals("gemini-flash-latest", GeminiClient.MODELS.first())
        assertTrue(GeminiClient.MODELS.takeLast(2).all { it.contains("lite") })
    }

    @Test
    fun aQuickAnswerFromTheBestModelAsksNoOther() = runBlocking {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val r = GeminiClient.hedge(models, hedgeMs = 200) { m -> asked += m; delay(20); "from $m" }
        assertEquals("best" to "from best", r.getOrThrow())
        delay(500)
        assertEquals(listOf("best"), asked.toList())
    }

    @Test
    fun aRefusalLetsTheNextModelInAtOnce() = runBlocking {
        val start = System.currentTimeMillis()
        val r = GeminiClient.hedge(models, hedgeMs = 5_000) { m ->
            if (m == "best") throw GeminiException("high demand", 503)
            "from $m"
        }
        assertEquals("second" to "from second", r.getOrThrow())
        assertTrue(System.currentTimeMillis() - start < 2_000)
    }

    @Test
    fun aSlowModelGetsCompanyAfterTheHedgeAndIsCancelledWhenItLoses() = runBlocking {
        val bestCancelled = CompletableDeferred<Unit>()
        val start = System.currentTimeMillis()
        val r = GeminiClient.hedge(models, hedgeMs = 150) { m ->
            if (m == "best") {
                try {
                    awaitCancellation()
                } finally {
                    bestCancelled.complete(Unit)
                }
            }
            "from $m"
        }
        assertEquals("second" to "from second", r.getOrThrow())
        assertTrue(System.currentTimeMillis() - start >= 150)
        bestCancelled.await()
    }

    @Test
    fun whenEveryModelFailsTheMostTellingReasonComesBack() = runBlocking {
        val badKey = GeminiException("API key not valid", 400)
        val r = GeminiClient.hedge(models, hedgeMs = 5_000) { m ->
            throw if (m == "second") badKey else GeminiException("high demand", 503)
        }
        assertEquals(badKey, r.exceptionOrNull())
    }
}
