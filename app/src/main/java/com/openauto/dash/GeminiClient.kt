package com.openauto.dash

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit

/** What Gemini answered, and which model did. */
data class GeminiReply(val model: String, val text: String)

/** An HTTP-level refusal from Gemini, with the status code that decides whether another model is worth trying. */
class GeminiException(message: String, val status: Int) : Exception(message)

/** The network answered, but not the internet: a hotspot without data, or a Wi-Fi login page. */
class NoInternetAccessException(status: Int) : IOException("Connectivity check answered HTTP $status")

/**
 * IPv4 addresses first. Phone hotspots often hand out IPv6 addresses that go
 * nowhere, and trying those first can stall every connection until it times out.
 */
internal object Ipv4First : Dns {
    override fun lookup(hostname: String): List<InetAddress> = preferIpv4(Dns.SYSTEM.lookup(hostname))

    fun preferIpv4(addresses: List<InetAddress>): List<InetAddress> = addresses.sortedBy { it is Inet6Address }
}

/**
 * Google Gemini over plain REST (free key from aistudio.google.com). The key
 * travels in a header, never in the URL.
 *
 * All models are asked at once and the first good answer wins. On a busy free
 * tier one model often answers while the others refuse ("high demand") or
 * hang, so waiting on them in turn wasted the budget. Each model has its own
 * free quota, and a car asks only a few times a day.
 */
object GeminiClient {

    // "-latest" aliases follow Google's newest releases; the pinned versions
    // are extra chances on a busy day, and simply drop out once retired (404).
    val MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-3.6-flash", "gemini-3.1-flash-lite")

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    private val JSON_TYPE = "application/json".toMediaType()
    // Refusals that say nothing about the key or the request, only about the model.
    private val MODEL_TROUBLE = setOf(404, 429, 500, 503)

    /** How long one [generate] may take in all, models and retries included, unless told otherwise. */
    const val BUDGET_MS = 90_000L

    private val client = OkHttpClient.Builder()
        .dns(Ipv4First)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Asks [prompt]; with a [schema], the answer is JSON matching it, and with
     * [audio] a recording goes along (Gemini hears speech itself). Gives up
     * once [budgetMs] is spent, whatever the link, so nothing waits forever.
     */
    suspend fun generate(
        apiKey: String,
        prompt: String,
        schema: JSONObject? = null,
        budgetMs: Long = BUDGET_MS,
        audio: ByteArray? = null,
        audioMime: String = "audio/aac"
    ): Result<GeminiReply> =
        withTimeoutOrNull(budgetMs) { race(apiKey, requestBody(prompt, schema, audio, audioMime)) }
            ?: Result.failure(InterruptedIOException("No Gemini model answered within ${budgetMs / 1000} s"))

    /** Every model at once; the first answer wins and the rest are cancelled. */
    private suspend fun race(apiKey: String, body: String): Result<GeminiReply> = coroutineScope {
        val pending: MutableList<Deferred<Result<GeminiReply>>> = MODELS.map { model ->
            async {
                try {
                    Result.success(GeminiReply(model, call(apiKey, model, body)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }.toMutableList()
        val failures = mutableListOf<Throwable>()
        while (pending.isNotEmpty()) {
            val (done, result) = select { pending.forEach { d -> d.onAwait { d to it } } }
            pending.remove(done)
            if (result.isSuccess) {
                pending.forEach { it.cancel() }
                return@coroutineScope result
            }
            result.exceptionOrNull()?.let(failures::add)
        }
        Result.failure(mostTelling(failures))
    }

    /**
     * The failure worth showing when every model failed: a refused key or a
     * broken link before "overloaded", "quota" or a retired model.
     */
    internal fun mostTelling(failures: List<Throwable>): Throwable =
        failures.firstOrNull { it is GeminiException && it.status !in MODEL_TROUBLE }
            ?: failures.firstOrNull { it is IOException }
            ?: failures.firstOrNull { it is GeminiException && it.status == 503 }
            ?: failures.firstOrNull { it is GeminiException && it.status == 429 }
            ?: failures.firstOrNull()
            ?: IllegalStateException("No Gemini model configured")

    /**
     * Whether the unit reaches Google at all, through Google's own tiny
     * connectivity check: tells "no internet" apart from "Gemini is slow".
     */
    suspend fun reachGoogle(timeoutMs: Long = 6_000L): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val call = client.newCall(Request.Builder().url("https://www.gstatic.com/generate_204").build())
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            call.execute().use { if (it.code != 204) throw NoInternetAccessException(it.code) }
        }
    }

    /** The request, built once and sent to every model in the race. */
    internal fun requestBody(prompt: String, schema: JSONObject?, audio: ByteArray?, audioMime: String): String {
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (audio != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", audioMime).put("data", Base64.getEncoder().encodeToString(audio))
                )
            )
        }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        if (schema != null) {
            body.put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema)
            )
        }
        return body.toString()
    }

    private suspend fun call(apiKey: String, model: String, body: String): String {
        val request = Request.Builder()
            .url("$BASE/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        client.newCall(request).await().use { resp ->
            val text = withContext(Dispatchers.IO) { resp.body?.string().orEmpty() }
            if (!resp.isSuccessful) throw GeminiException(errorMessage(text) ?: "HTTP ${resp.code}", resp.code)
            return answerText(text) ?: throw GeminiException("Gemini gave an empty answer", resp.code)
        }
    }

    /** Runs the call without blocking a thread, and cancels it when the caller gives up (a lost race, the budget). */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    /** The answer's text parts joined, skipping any "thought" parts; null when there is none. */
    internal fun answerText(json: String): String? {
        val parts = runCatching {
            JSONObject(json).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
        }.getOrNull() ?: return null
        val text = (0 until parts.length())
            .map { parts.getJSONObject(it) }
            .filter { !it.optBoolean("thought", false) }
            .joinToString("") { it.optString("text", "") }
        return text.ifBlank { null }
    }

    /** Google's own explanation from an error body, e.g. "API key not valid". */
    internal fun errorMessage(json: String): String? =
        runCatching { JSONObject(json).getJSONObject("error").getString("message") }.getOrNull()?.ifBlank { null }
}
