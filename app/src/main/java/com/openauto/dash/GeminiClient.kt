package com.openauto.dash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
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
 * Models are tried in order: the newest Flash, then Flash-Lite, which has its
 * own free quota. Only a spent quota, an overloaded or retired model moves on;
 * a bad key or no network fails straight away.
 */
object GeminiClient {

    // "-latest" aliases follow Google's newest release, so no version to bump.
    val MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    private val JSON_TYPE = "application/json".toMediaType()
    private val TRY_NEXT_MODEL = setOf(404, 429, 500, 503)

    /** How long one [generate] may take in all, models and retries included, unless told otherwise. */
    const val BUDGET_MS = 90_000L

    private val client = OkHttpClient.Builder()
        .dns(Ipv4First)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Asks [prompt]; with a [schema], the answer is JSON matching it. Gives up
     * once [budgetMs] is spent, whatever the link, so nothing waits forever.
     */
    suspend fun generate(
        apiKey: String,
        prompt: String,
        schema: JSONObject? = null,
        budgetMs: Long = BUDGET_MS
    ): Result<GeminiReply> =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + budgetMs
            var failure: Throwable = IllegalStateException("No Gemini model configured")
            for (model in MODELS) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try {
                    return@withContext Result.success(GeminiReply(model, call(apiKey, model, prompt, schema, left)))
                } catch (e: GeminiException) {
                    failure = e
                    if (e.status !in TRY_NEXT_MODEL) break
                } catch (e: IOException) {
                    failure = e
                    break
                }
            }
            Result.failure(failure)
        }

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

    private fun call(apiKey: String, model: String, prompt: String, schema: JSONObject?, timeoutMs: Long): String {
        val body = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            )
        )
        if (schema != null) {
            body.put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema)
            )
        }
        val request = Request.Builder()
            .url("$BASE/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val call = client.newCall(request)
        // One limit for the whole exchange, name lookup and slow answer included.
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw GeminiException(errorMessage(text) ?: "HTTP ${resp.code}", resp.code)
            return answerText(text) ?: throw GeminiException("Gemini gave an empty answer", resp.code)
        }
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
