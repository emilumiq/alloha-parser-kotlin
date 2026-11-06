package org.emilumiq.alloha.proxy

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.emilumiq.alloha.log.AllohaLog
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class AllohaStreamState(
    val headers: Map<String, String>,
    val masterUrl: String,
    val generation: Long,
    val expiresAtMs: Long?,
)

class AllohaStreamProxy(
    private val streamStateProvider: () -> AllohaStreamState,
    private val masterProvider: (audioId: String?, quality: String?) -> String?,
    private val requestSessionRefresh: () -> Unit,
    private val onIdentityRejected: (marker: String) -> Unit = {},
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val server = ServerSocket(0, 8, InetAddress.getByName(LOOPBACK_HOST))
    private val sessionToken = UUID.randomUUID().toString()

    private val verbose = Log.isLoggable(LOG_TAG, Log.DEBUG)

    private val lastSeenGeneration = AtomicLong(-1L)
    private val rejectedSessionGeneration = AtomicLong(-1L)
    private val lastRejectionMarker = AtomicReference<String?>(null)

    @Volatile
    private var connectionPool = buildConnectionPool()

    @Volatile
    private var client = buildClient()

    private val sessionRefreshLock = Any()
    private val closed = AtomicBoolean(false)
    private val backgroundRefreshScheduled = AtomicBoolean(false)
    private val backgroundRefreshConsumedSinceSuccess = AtomicBoolean(false)

    @Volatile
    private var sessionRefreshInProgress = false

    private val cacheLock = Any()
    private val segmentCache =
        object : LinkedHashMap<String, ByteArray>(CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
                size > CACHE_CAPACITY
        }
    private val inFlightSegments = ConcurrentHashMap<String, Deferred<FetchResult?>>()

    @Volatile
    private var recentSegments: List<String> = emptyList()

    val playbackUrl = "http://$LOOPBACK_HOST:${server.localPort}/master.m3u8?token=$sessionToken"

    init {
        AllohaLog.d("Proxy") { "started port=${server.localPort} ${streamStateProvider().shortSummary()}" }
        scope.launch {
            while (isActive && !server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull()
                if (socket == null) {
                    delay(ACCEPT_RETRY_DELAY_MS)
                    continue
                }
                runCatching { socket.soTimeout = REQUEST_READ_TIMEOUT_MS }
                launch {
                    try {
                        handle(socket)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        AllohaLog.w("Proxy", "connection error: ${error.message}")
                    }
                }
            }
        }
    }

    fun streamUrl(audioId: String?, quality: String?): String = buildString {
        append(playbackUrl)
        val params = buildList {
            audioId?.takeIf(String::isNotBlank)
                ?.let { add("audio=${URLEncoder.encode(it, "UTF-8")}") }
            quality?.takeIf(String::isNotBlank)
                ?.let { add("quality=${URLEncoder.encode(it, "UTF-8")}") }
        }
        if (params.isNotEmpty()) append("&").append(params.joinToString("&"))
    }

    fun sideloadUrl(url: String): String {
        val encoded = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "http://$LOOPBACK_HOST:${server.localPort}/proxy?url=$encoded&token=$sessionToken"
    }

    private fun buildConnectionPool(): ConnectionPool = ConnectionPool(5, 20, TimeUnit.SECONDS)

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun noteActiveState(state: AllohaStreamState) {
        if (state.generation <= 0L) return
        while (true) {
            val previous = lastSeenGeneration.get()
            if (state.generation <= previous) return
            if (lastSeenGeneration.compareAndSet(previous, state.generation)) {
                if (previous >= 0L) onSessionRotated(state)
                return
            }
        }
    }

    private fun onSessionRotated(state: AllohaStreamState) {
        val oldPool = connectionPool
        connectionPool = buildConnectionPool()
        client = buildClient()
        oldPool.evictAll()
        AllohaLog.d("Proxy") { "session transport reset ${state.shortSummary()}" }
    }

    private suspend fun handle(socket: Socket) {
        socket.use {
            val reader = it.getInputStream().bufferedReader()
            val requestLine = reader.readLineBounded(MAX_REQUEST_LINE_CHARS) ?: return
            val requestHeaders = buildMap {
                while (true) {
                    val line = reader.readLineBounded(MAX_REQUEST_LINE_CHARS) ?: break
                    if (line.isBlank()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) put(
                        line.substring(0, separator).trim().lowercase(),
                        line.substring(separator + 1).trim(),
                    )
                }
            }
            val path = requestLine.split(' ').getOrNull(1) ?: return
            val params = AllohaProxyUrls.parseQuery(path)

            if (params["token"] != sessionToken) {
                send(it.getOutputStream(), 404, "text/plain", byteArrayOf())
                return
            }

            val state = streamStateProvider()
            noteActiveState(state)
            val target = if (path.substringBefore('?') == "/master.m3u8") {
                masterProvider(params["audio"], params["quality"]) ?: state.masterUrl
            } else {
                params["url"]?.let { encoded ->
                    runCatching { String(Base64.decode(encoded, Base64.URL_SAFE)) }.getOrNull()
                }.orEmpty()
            }
            if (!AllohaProxyUrls.isHttp(target)) {
                send(it.getOutputStream(), 404, "text/plain", byteArrayOf())
                return
            }
            serve(target, requestHeaders, it.getOutputStream())
        }
    }

    private suspend fun serve(
        url: String,
        requestHeaders: Map<String, String>,
        output: OutputStream,
    ) {
        if (AllohaProxyUrls.isPlaylist(url)) {
            servePlaylist(url, output)
        } else {
            serveSegment(url, requestHeaders, output)
        }
    }

    private suspend fun servePlaylist(url: String, output: OutputStream) {
        val result = fetchWithRecovery(url)
        if (result == null) {
            send(output, 503, "text/plain", byteArrayOf())
            return
        }
        val text = result.bytes.toString(Charsets.UTF_8)
        if (verbose) logPlaylistAudioState(text)
        val rewritten = rewritePlaylist(text, result.finalUrl)
        send(output, 200, "application/vnd.apple.mpegurl", rewritten.toByteArray())
    }

    private suspend fun serveSegment(
        url: String,
        requestHeaders: Map<String, String>,
        output: OutputStream,
    ) {
        val hasRange = requestHeaders.containsKey("range") || requestHeaders.containsKey("if-range")
        if (hasRange) {
            val result = fetchWithRecovery(url, requestHeaders, allowRange = true)
            if (result == null) {
                send(output, 503, "text/plain", byteArrayOf())
            } else {
                sendBytes(output, result)
            }
            return
        }

        val cached = synchronized(cacheLock) { segmentCache[url] }
        val result = if (cached != null) {
            FetchResult(cached, 200, AllohaProxyUrls.contentType(url), url)
        } else {
            getOrFetch(url)
        }

        if (result == null) {
            if (url.contains("-a1.ts") || url.contains("-a2.ts")) {
                AllohaLog.d("Proxy") { "audio TS unrecoverable, serving empty packet ${url.safeTarget()}" }
                sendBytes(output, FetchResult(EMPTY_TS_PACKET, 200, "video/MP2T", url))
            } else {
                AllohaLog.d("Proxy") { "media segment unrecoverable ${url.safeTarget()}" }
                send(output, 503, "text/plain", byteArrayOf())
            }
            return
        }
        if (result.bytes.size < MIN_SEGMENT_BYTES_HINT) {
            AllohaLog.d("Proxy") { "small segment (${result.bytes.size}b): ${url.safeTarget()}" }
        }
        sendBytes(output, result)
        prefetchUpcoming(url)
    }

    private suspend fun getOrFetch(url: String): FetchResult? {
        synchronized(cacheLock) { segmentCache[url] }?.let {
            return FetchResult(it, 200, AllohaProxyUrls.contentType(url), url)
        }
        val deferred = inFlightSegments.computeIfAbsent(url) {
            scope.async {
                fetchWithRecovery(url)?.also { result ->
                    synchronized(cacheLock) { segmentCache[url] = result.bytes }
                }
            }
        }
        return try {
            deferred.await()
        } catch (_: CancellationException) {
            if (scope.isActive) fetchWithRecovery(url) else null
        } finally {
            inFlightSegments.remove(url, deferred)
        }
    }

    private fun prefetchUpcoming(url: String) {
        val list = recentSegments
        val idx = list.indexOf(url)
        if (idx < 0) return
        for (i in 1..PREFETCH_COUNT) {
            val next = list.getOrNull(idx + i) ?: break
            if (synchronized(cacheLock) { segmentCache.containsKey(next) }) continue
            if (inFlightSegments.containsKey(next)) continue
            scope.launch { getOrFetch(next) }
        }
    }

    private data class FetchResult(
        val bytes: ByteArray,
        val statusCode: Int,
        val contentType: String,
        val finalUrl: String,
        val contentRange: String? = null,
        val acceptRanges: String? = null,
    )

    private suspend fun fetchWithRecovery(
        url: String,
        requestHeaders: Map<String, String> = emptyMap(),
        allowRange: Boolean = false,
    ): FetchResult? {
        val initialState = streamStateProvider()
        noteActiveState(initialState)

        var rejectedGeneration = -1L
        var rejectedMarker: String? = null

        fun execute(
            target: String,
            forwardRange: Boolean = allowRange,
        ): FetchResult? {
            var statusCode: Int? = null
            val state = streamStateProvider()
            noteActiveState(state)
            val currentHeaders = state.headers
            val cookie = sequenceOf(target, currentHeaders["origin"], currentHeaders["referer"])
                .filterNotNull()
                .mapNotNull {
                    runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull()
                }
                .firstOrNull(String::isNotBlank)
            val request = Request.Builder().url(target).apply {
                currentHeaders.forEach { (name, value) ->
                    if (name.lowercase() !in HEADERS_NOT_REPLAYED) header(name, value)
                }
                if (currentHeaders.keys.none { it.equals("accept", ignoreCase = true) }) {
                    header("Accept", "*/*")
                }
                if (currentHeaders.keys.none { it.equals("accept-language", ignoreCase = true) }) {
                    header("Accept-Language", DEFAULT_ACCEPT_LANGUAGE)
                }
                val presentedUa = currentHeaders["user-agent"].orEmpty()
                CHROME_VERSION.find(presentedUa)?.groupValues?.get(1)?.let { major ->
                    header(
                        "sec-ch-ua",
                        "\"Chromium\";v=\"$major\", \"Google Chrome\";v=\"$major\", \"Not_A Brand\";v=\"24\"",
                    )
                    header("sec-ch-ua-mobile", "?0")
                    header("sec-ch-ua-platform", platformHintFor(presentedUa))
                }
                cookie?.let { header("Cookie", it) }
                if (forwardRange) {
                    requestHeaders["range"]?.let { header("Range", it) }
                    requestHeaders["if-range"]?.let { header("If-Range", it) }
                }
            }.build()

            if (verbose) {
                AllohaLog.d("Proxy") {
                    "outgoing ${request.headers.names().joinToString(",")} " +
                        "| ua=${request.header("User-Agent")?.take(48)} " +
                        "| lang=${request.header("Accept-Language") ?: "MISSING"} " +
                        "| chUa=${request.header("sec-ch-ua") ?: "MISSING"} " +
                        "| cookie=${if (request.header("Cookie") != null) "yes" else "no"} " +
                        "| target=${target.safeTarget()}"
                }
            }

            val requestClient = client
            val requestPool = connectionPool

            fun perform(requestToSend: Request): FetchResult? =
                runCatching {
                    requestClient.newCall(requestToSend).execute().use { response ->
                        statusCode = response.code
                        if (response.code !in SUCCESS_CODES) {
                            val xVd = response.header("X-VD").orEmpty()
                            if (xVd in SESSION_REJECTION_MARKERS) {
                                rejectedGeneration = maxOf(rejectedGeneration, state.generation)
                                rejectedMarker = xVd
                            }
                            AllohaLog.d("Proxy") {
                                "CDN failure code=${response.code} xVd=$xVd " +
                                    "setCookie=${response.headers("Set-Cookie")} " +
                                    "target=${target.safeTarget()}"
                            }
                            null
                        } else {
                            backgroundRefreshConsumedSinceSuccess.set(false)
                            val rawBytes = response.body?.bytes() ?: return@runCatching null
                            val bytes = if (response.code == 200 && AllohaProxyUrls.isAssSubtitle(target)) {
                                AllohaProxyUrls.fixAssBytes(rawBytes)
                            } else {
                                rawBytes
                            }
                            FetchResult(
                                bytes = bytes,
                                statusCode = response.code,
                                contentType = response.header("Content-Type").orEmpty(),
                                finalUrl = response.request.url.toString(),
                                contentRange = response.header("Content-Range"),
                                acceptRanges = response.header("Accept-Ranges"),
                            )
                        }
                    }
                }.onFailure {
                    AllohaLog.d("Proxy") { "CDN request failed: ${it.message} target=${target.safeTarget()}" }
                }.getOrNull()

            perform(request)?.let { return it }
            if (statusCode != 403) return null

            requestPool.evictAll()
            AllohaLog.d("Proxy") { "retrying 403 with Connection: close ${target.safeTarget()}" }
            val retryRequest = request.newBuilder().header("Connection", "close").build()
            val retryResult = perform(retryRequest)
            if (retryResult == null && statusCode == 403 && rejectedGeneration >= state.generation) {
                markSessionRejected(state, rejectedMarker)
            }
            return retryResult
        }

        val initialGeneration = initialState.generation
        val canHoldForRefresh = !allowRange

        suspend fun holdForRefreshThenRetry(): FetchResult? {
            if (lastRejectionMarker.get() == TOKEN_DECRYPT_MARKER) return null
            if (!scheduleBackgroundSessionRefresh(initialGeneration)) return null
            if (!canHoldForRefresh) return null
            val deadline = System.currentTimeMillis() + SEGMENT_HOLD_FOR_REFRESH_MS
            while (System.currentTimeMillis() < deadline) {
                delay(SESSION_REFRESH_POLL_MS)
                val state = streamStateProvider()
                if (state.generation > initialGeneration) {
                    noteActiveState(state)
                    AllohaLog.d("Proxy") { "retrying held segment on refreshed session ${url.safeTarget()}" }
                    val target = AllohaProxyUrls.rewriteToCurrentPath(url, state.masterUrl) ?: url
                    return execute(target)
                }
            }
            AllohaLog.d("Proxy") { "held segment timed out ${url.safeTarget()}" }
            return null
        }

        if (rejectedSessionGeneration.get() >= initialGeneration) {
            return holdForRefreshThenRetry()
        }

        execute(url)?.let { return it }

        if (!AllohaProxyUrls.isPlaylist(url)) {
            AllohaProxyUrls.rewriteToCurrentPath(url, streamStateProvider().masterUrl)?.let { rewritten ->
                AllohaLog.d("Proxy") { "retrying segment on current session path ${rewritten.safeTarget()}" }
                execute(rewritten)?.let { return it }
            }
        }

        return holdForRefreshThenRetry()
    }

    private fun markSessionRejected(state: AllohaStreamState, marker: String?) {
        while (true) {
            val previous = rejectedSessionGeneration.get()
            if (state.generation <= previous) return
            if (rejectedSessionGeneration.compareAndSet(previous, state.generation)) {
                lastRejectionMarker.set(marker)
                AllohaLog.d("Proxy") { "session generation rejected marker=$marker" }
                if (marker != null && marker in IDENTITY_REJECTION_MARKERS) onIdentityRejected(marker)
                return
            }
        }
    }


    private fun sendBytes(output: OutputStream, result: FetchResult) {
        val reason = if (result.statusCode == 206) "Partial Content" else "OK"
        val headers = buildString {
            append("HTTP/1.1 ${result.statusCode} $reason\r\n")
            append("Content-Type: ${result.contentType.ifBlank { "video/MP2T" }}\r\n")
            result.contentRange?.let { append("Content-Range: $it\r\n") }
            result.acceptRanges?.let { append("Accept-Ranges: $it\r\n") }
            append("Content-Length: ${result.bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(headers.toByteArray())
            output.write(result.bytes)
            output.flush()
        }
    }

    private suspend fun refreshSessionOnce(previousGeneration: Long): Boolean {
        if (streamStateProvider().generation > previousGeneration) return true
        val isLeader = synchronized(sessionRefreshLock) {
            if (sessionRefreshInProgress) {
                false
            } else {
                sessionRefreshInProgress = true
                true
            }
        }
        if (isLeader) {
            try {
                AllohaLog.d("Proxy") { "requesting session refresh generation=$previousGeneration" }
                requestSessionRefresh()
                val deadline = System.currentTimeMillis() + SESSION_REFRESH_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    val state = streamStateProvider()
                    if (state.generation > previousGeneration) {
                        noteActiveState(state)
                        AllohaLog.d("Proxy") { "session refresh completed ${state.shortSummary()}" }
                        return true
                    }
                    delay(SESSION_REFRESH_POLL_MS)
                }
            } finally {
                synchronized(sessionRefreshLock) { sessionRefreshInProgress = false }
            }
        } else {
            val deadline = System.currentTimeMillis() + SESSION_REFRESH_WAIT_MS
            while (sessionRefreshInProgress && System.currentTimeMillis() < deadline) {
                if (streamStateProvider().generation > previousGeneration) return true
                delay(SESSION_REFRESH_POLL_MS)
            }
        }
        return streamStateProvider().generation > previousGeneration
    }

    private fun scheduleBackgroundSessionRefresh(previousGeneration: Long): Boolean {
        if (!backgroundRefreshScheduled.compareAndSet(false, true)) return true
        if (!backgroundRefreshConsumedSinceSuccess.compareAndSet(false, true)) {
            backgroundRefreshScheduled.set(false)
            return false
        }
        scope.launch {
            try {
                if (!refreshSessionOnce(previousGeneration)) {
                    AllohaLog.d("Proxy") { "background refresh timed out generation=$previousGeneration" }
                }
            } finally {
                backgroundRefreshScheduled.set(false)
            }
        }
        return true
    }

    private fun rewritePlaylist(content: String, playlistUrl: String): String {
        val base = URL(playlistUrl)
        val collectedSegments = mutableListOf<String>()

        fun proxy(value: String, trackForPrefetch: Boolean): String {
            val absolute = URL(base, value).toString()
            if (trackForPrefetch) collectedSegments += absolute
            val encoded =
                Base64.encodeToString(absolute.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            return "http://$LOOPBACK_HOST:${server.localPort}/proxy?url=$encoded&token=$sessionToken"
        }

        val rewritten = content.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> line.replace(URI_ATTRIBUTE) {
                    "URI=\"${proxy(it.groupValues[1], trackForPrefetch = false)}\""
                }

                else -> proxy(line.trim(), trackForPrefetch = true)
            }
        }
        if (collectedSegments.isNotEmpty()) {
            recentSegments = collectedSegments
            scope.launch { collectedSegments.take(PREFETCH_COUNT).forEach { getOrFetch(it) } }
        }
        return rewritten
    }

    private fun logPlaylistAudioState(content: String) {
        val audioEntries = content.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("#EXT-X-MEDIA:") && it.contains("TYPE=AUDIO") }
            .map { line ->
                AUDIO_LOG_ATTRIBUTE.findAll(line)
                    .associate { it.groupValues[1] to it.groupValues[2].trim('"') }
                    .filterKeys { it in AUDIO_LOG_ATTRIBUTES }
            }
            .toList()
        AllohaLog.d("Proxy") {
            "playlist master=${content.contains("#EXT-X-STREAM-INF")} audio=$audioEntries"
        }
    }

    private fun send(output: OutputStream, code: Int, type: String, bytes: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Service Unavailable"
        }
        val header = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            output.write(header.toByteArray())
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.launch {
            try {
                runCatching { server.close() }
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                synchronized(cacheLock) { segmentCache.clear() }
                inFlightSegments.clear()
            } finally {
                scope.cancel()
            }
        }
    }

    private companion object {
        const val LOG_TAG = "Alloha"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val SESSION_REFRESH_WAIT_MS = 20_000L
        const val SESSION_REFRESH_POLL_MS = 150L
        const val SEGMENT_HOLD_FOR_REFRESH_MS = 14_000L
        const val CACHE_CAPACITY = 4
        const val REQUEST_READ_TIMEOUT_MS = 15_000
        const val ACCEPT_RETRY_DELAY_MS = 250L
        const val MAX_REQUEST_LINE_CHARS = 32 * 1024
        const val PREFETCH_COUNT = 2
        const val MIN_SEGMENT_BYTES_HINT = 1000
        val SUCCESS_CODES = setOf(200, 206)

        val HEADERS_NOT_REPLAYED = setOf(
            "host",
            "connection",
            "content-length",
            "transfer-encoding",
            "accept-encoding",
            "cookie",
            "borth",
            "x-requested-with",
            "content-type",
        )
        const val DEFAULT_ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"
        val CHROME_VERSION = Regex("""Chrome/(\d+)""")

        fun platformHintFor(userAgent: String): String = when {
            userAgent.contains("Windows", ignoreCase = true) -> "\"Windows\""
            userAgent.contains("Macintosh", ignoreCase = true) -> "\"macOS\""
            else -> "\"Linux\""
        }

        const val TOKEN_DECRYPT_MARKER = "token_decrypt"
        val IDENTITY_REJECTION_MARKERS = setOf("session_blocked", TOKEN_DECRYPT_MARKER)
        val SESSION_REJECTION_MARKERS =
            setOf("session_blocked", TOKEN_DECRYPT_MARKER, "client_blocked")

        val EMPTY_TS_PACKET = ByteArray(188).also {
            it[0] = 0x47
            it[1] = 0x1F.toByte()
            it[2] = 0xFF.toByte()
            it[3] = 0x10
        }
        val URI_ATTRIBUTE = Regex("""URI="([^"]+)""", RegexOption.IGNORE_CASE)
        val AUDIO_LOG_ATTRIBUTE = Regex("""([A-Z-]+)=((?:"[^"]*")|[^,]*)""")
        val AUDIO_LOG_ATTRIBUTES = setOf("GROUP-ID", "NAME", "LANGUAGE", "DEFAULT", "AUTOSELECT")







        fun AllohaStreamState.shortSummary(): String {
            val host = runCatching { URL(masterUrl).host }.getOrDefault("unknown")
            return "generation=$generation host=$host"
        }


        fun String.safeTarget(): String = runCatching {
            val parsed = URL(this)
            "${parsed.host}/${parsed.path.substringAfterLast('/')}"
        }.getOrDefault(substringAfterLast('/').substringBefore('?'))
    }
}
