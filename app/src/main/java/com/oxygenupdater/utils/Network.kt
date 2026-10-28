package com.oxygenupdater.utils

import android.content.Context
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.os.storage.StorageManager
import androidx.annotation.VisibleForTesting
import androidx.core.content.getSystemService
import com.oxygenupdater.BuildConfig
import com.oxygenupdater.apis.DownloadApi
import com.oxygenupdater.apis.ServerApi
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.HttpLoggingInterceptor.Level
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

private const val TAG = "OxygenUpdaterNetwork"
private const val UserAgentTag = "User-Agent"
private const val DefaultCacheSize = 10L * 1024 * 1024 // 10 MB

const val AppUserAgent = "Oxygen_updater_" + BuildConfig.VERSION_NAME
const val ApiBaseUrl = BuildConfig.SERVER_DOMAIN + BuildConfig.SERVER_API_BASE

var okHttpCache: Cache? = null

/** Allows per-request read timeout override via application interceptor */
const val HeaderReadTimeout = "X-Read-Timeout"

@VisibleForTesting
val json = Json {
    ignoreUnknownKeys = true

    // Can't use this globally because it's applied both ways, i.e. serialization & deserialization,
    // and it isn't possible to selectively turn it off after the fact. We require this strategy
    // only when deserializing responses from the server (we send some requests with camelCase fields).
    // So, @JsonNames is used on each field that requires it.
    // @Suppress("OPT_IN_USAGE") namingStrategy = JsonNamingStrategy.SnakeCase
}

fun createServerApi(context: Context) = Retrofit.Builder()
    .baseUrl(ApiBaseUrl)
    .client(httpClient(createOkHttpCache(context)))
    .addConverterFactory(
        json.asConverterFactory("application/json; charset=utf-8".toMediaType())
    ).build().create<ServerApi>()

fun createDownloadApi() = Retrofit.Builder()
    .baseUrl(ApiBaseUrl).apply {
        client(
            OkHttpClient.Builder().apply {
                // Chunks are downloaded in parallel to get around per-connection throttling (see DownloadWorker),
                // which needs separate connections. HTTP/2 would multiplex all requests over a single one.
                protocols(listOf(Protocol.HTTP_1_1))

                // Use a network interceptor to log all requests, even intermediary ones like redirects
                if (BuildConfig.DEBUG) addNetworkInterceptor(HttpLoggingInterceptor().setLevel(Level.BASIC)).build()

                // Use an application interceptor to use the platform-default user agent
                addInterceptor { chain ->
                    // This is what Android's HttpURLConnectionImpl uses
                    val default = System.getProperty("http.agent") ?: "Java${System.getProperty("java.version")}"

                    chain.proceed(chain.request().newBuilder().header(UserAgentTag, default).build())
                }
            }.build()
        )
    }.build().create<DownloadApi>()

/**
 * Create a [Cache] for [OkHttpClient].
 *
 * Limit cache size to stay under quota if device is Oreo and above.
 * Otherwise, default to [DefaultCacheSize]
 */
private fun createOkHttpCache(context: Context) = if (SDK_INT >= VERSION_CODES.O) {
    context.getSystemService<StorageManager>()?.run {
        try {
            val quota = getCacheQuotaBytes(getUuidForPath(context.cacheDir))
            if (quota > 0L) quota else DefaultCacheSize
        } catch (e: Exception) {
            DefaultCacheSize
        }
    } ?: DefaultCacheSize
} else {
    DefaultCacheSize
}.let {
    Cache(context.cacheDir, it)
}.also { okHttpCache = it }

private fun httpClient(cache: Cache) = OkHttpClient.Builder().apply {
    cache(cache)

    // Use a network interceptor to log all requests, even intermediary ones like redirects.
    // Note that this won't be invoked for cached responses as they short-circuit the network.
    if (BuildConfig.DEBUG) addNetworkInterceptor(HttpLoggingInterceptor().setLevel(Level.BASIC))

    // Use an application interceptor to add a custom user agent
    addInterceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder().header(UserAgentTag, AppUserAgent)
        val readTimeout = request.header(HeaderReadTimeout)?.let {
            builder.removeHeader(HeaderReadTimeout)
            it.toIntOrNull()
        }

        chain.run {
            if (readTimeout != null) withReadTimeout(readTimeout, TimeUnit.SECONDS).also {
                logDebug(TAG, "custom readTimeout = ${readTimeout}s")
            } else logDebug(TAG, "default readTimeout = ${chain.readTimeoutMillis() / 1000}s")

            proceed(builder.build())
        }
    }
}.build()
