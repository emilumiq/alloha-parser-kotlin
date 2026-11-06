package org.emilumiq.alloha.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AllohaApiClient(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun fetchCatalogJson(token: String, kp: String): String {
        val url = API_BASE.toHttpUrl().newBuilder()
            .addQueryParameter("token", token)
            .addQueryParameter("kp", kp)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val body = try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    throw AllohaApiException("Сервис ответил HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        } catch (error: IOException) {
            throw AllohaApiException("Нет связи с API: ${error.message}", error)
        }
        return body.takeIf(String::isNotBlank) ?: throw AllohaApiException("Пустой ответ API")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, error: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(error)
            }
        })
        continuation.invokeOnCancellation { cancel() }
    }

    private companion object {
        const val API_BASE = "https://api.alloha.tv/"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
