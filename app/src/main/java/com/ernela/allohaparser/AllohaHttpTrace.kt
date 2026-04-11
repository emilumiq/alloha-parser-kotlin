package com.ernela.allohaparser

import android.content.Context
import android.util.Log
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full HTTP trace for ExoPlayer/OkHttp (JSON Lines) + JS header snapshots.
 * Pull file: adb exec-out run-as com.ernela.allohaparser cat files/alloha_http_trace.jsonl > trace.jsonl
 */
object AllohaHttpTrace {
    const val FILE_NAME = "alloha_http_trace.jsonl"
    private const val TAG = "AllohaHttpTrace"

    private val seq = AtomicInteger(0)
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "alloha-http-trace").apply { isDaemon = true }
    }

    /** Last CDN tokens from JS pushes (skip duplicate stream_push lines). */
    private var lastStreamAuth: String? = null
    private var lastStreamAccepts: String? = null

    fun traceFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    fun reset(context: Context) {
        seq.set(0)
        lastStreamAuth = null
        lastStreamAccepts = null
        try {
            traceFile(context).writeText(
                JSONObject().apply {
                    put("kind", "session_start")
                    put("ts", System.currentTimeMillis())
                    put("file", FILE_NAME)
                }.toString() + "\n",
                Charsets.UTF_8
            )
        } catch (e: Exception) {
            Log.w(TAG, "reset failed", e)
        }
    }

    private fun headersToJson(headers: Headers): JSONObject =
        JSONObject().apply {
            for (i in 0 until headers.size) {
                put(headers.name(i), headers.value(i))
            }
        }

    private fun appendLineAsync(context: Context, line: String) {
        val app = context.applicationContext
        writer.execute {
            try {
                traceFile(app).appendText(line + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "append failed", e)
            }
        }
    }

    private fun logLongDebug(line: String) {
        val chunk = 3800
        var i = 0
        var part = 0
        while (i < line.length) {
            val end = minOf(i + chunk, line.length)
            Log.d(TAG, "[part ${part++}] ${line.substring(i, end)}")
            i = end
        }
    }

    /**
     * One OkHttp request → response row (called from network interceptor; do not consume body).
     */
    fun logOkHttpRoundTrip(
        context: Context,
        request: Request,
        response: Response?,
        error: String?,
    ) {
        val id = seq.incrementAndGet()
        val row = JSONObject().apply {
            put("kind", "okhttp")
            put("id", id)
            put("ts", System.currentTimeMillis())
            put("url", request.url.toString())
            put("method", request.method)
            put("requestHeaders", headersToJson(request.headers))
            if (error != null) put("error", error)
            if (response != null) {
                put("code", response.code)
                put("message", response.message)
                put("responseHeaders", headersToJson(response.headers))
            }
        }
        val line = row.toString()
        Log.i(TAG, "[$id] ${request.method} ${request.url} -> ${response?.code ?: "ERR"} ${error ?: ""}")
        logLongDebug(line)
        appendLineAsync(context, line)
    }

    /**
     * Snapshot of headers captured in WebView (for diff vs OkHttp).
     * [source]: onReady, config_update, m3u8_refresh, stream_push
     */
    fun logJsHeaders(context: Context, source: String, headers: Map<String, String>) {
        if (source == "stream_push") {
            val a = headers["authorizations"]
            val c = headers["accepts-controls"]
            if (a == lastStreamAuth && c == lastStreamAccepts) return
            lastStreamAuth = a
            lastStreamAccepts = c
        }

        val id = seq.incrementAndGet()
        val rh = JSONObject()
        headers.forEach { (k, v) -> rh.put(k, v) }
        val row = JSONObject().apply {
            put("kind", "js_headers")
            put("id", id)
            put("ts", System.currentTimeMillis())
            put("source", source)
            put("headers", rh)
        }
        val line = row.toString()
        Log.i(TAG, "[$id] js_headers $source keys=${headers.keys.joinToString(",")}")
        logLongDebug(line)
        appendLineAsync(context, line)
    }
}
