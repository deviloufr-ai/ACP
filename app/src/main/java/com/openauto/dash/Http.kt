package com.openauto.dash

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The one OkHttp client of the app. Each feature derives its own timeouts with
 * `Http.client.newBuilder()`, which shares the connection pool and threads
 * instead of starting a new set per feature (or per request).
 */
internal object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
