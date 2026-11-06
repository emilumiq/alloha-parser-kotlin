package org.emilumiq.alloha.parser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.emilumiq.alloha.log.AllohaLog
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.random.Random

class AllohaSessionOpener(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val webViewPool = AllohaWebViewPool()

    private val sessionUserAgent: String by lazy { desktopUserAgent() }
    private val identityRejected = AtomicBoolean(false)
    private var lastIdentityResetAtMs = 0L

    sealed interface OpenResult {
        data class Ready(val session: AllohaSession) : OpenResult
        data object Unavailable : OpenResult
        data object Failed : OpenResult
    }

    suspend fun open(
        iframeUrl: String,
        preferredQualityLabel: String? = null,
    ): OpenResult = withContext(Dispatchers.Main) {
        openSessionViaWebView(iframeUrl, preferredQualityLabel)
    }

    fun destroy() {
        scope.cancel()
        webViewPool.destroy()
    }

    private suspend fun openSessionViaWebView(
        iframeUrl: String,
        preferredQualityLabel: String?,
    ): OpenResult = suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        var delivered = false
        var streamReady = false
        var refreshedMasterReady = false
        var pendingHostChangeMaster: String? = null
        var wrapperReloads = 0
        var hostChangeRestarts = 0
        var reloadWrapper: (() -> Unit)? = null

        val liveSession = AllohaSession(handler, iframeUrl)
        resetIdentityIfRejected()

        fun finish(result: OpenResult) {
            if (delivered) return
            delivered = true
            handler.removeCallbacksAndMessages(null)
            liveSession.close()
            if (continuation.isActive) continuation.resume(result)
        }

        fun resumeWithProxy() {
            scope.launch {
                val started = runCatching { liveSession.startProxy() }.isSuccess
                if (!started) liveSession.close()
                if (continuation.isActive) {
                    continuation.resume(if (started) OpenResult.Ready(liveSession) else OpenResult.Failed)
                } else {
                    liveSession.close()
                }
            }
        }

        fun deliverWhenReady() {
            if (delivered || !streamReady || !refreshedMasterReady) return
            delivered = true
            handler.removeCallbacksAndMessages(null)
            AllohaLog.d("Opener") { "session ready ttl=${liveSession.expiresInMs()?.div(1000) ?: "none"}s" }
            resumeWithProxy()
        }

        val hostChangeFallback = Runnable {
            if (pendingHostChangeMaster != null) {
                pendingHostChangeMaster = null
                AllohaLog.d("Opener") { "host-change config_update timed out, restarting session" }
                liveSession.refresh()
            }
        }

        lateinit var timeout: Runnable
        lateinit var noSignalTimeout: Runnable

        val masterWaitTimeout = Runnable {
            if (!delivered && streamReady) {
                AllohaLog.d("Opener") { "refreshed master timeout, using captured quality playlist" }
                delivered = true
                handler.removeCallbacks(timeout)
                resumeWithProxy()
            }
        }

        noSignalTimeout = Runnable {
            if (delivered || streamReady) return@Runnable
            if (wrapperReloads >= MAX_WRAPPER_RELOADS) return@Runnable
            wrapperReloads++
            AllohaLog.d("Opener") {
                "no signal after ${NO_SIGNAL_TIMEOUT_MS}ms, reloading wrapper " +
                    "attempt=$wrapperReloads/$MAX_WRAPPER_RELOADS"
            }
            reloadWrapper?.invoke()
            handler.postDelayed(noSignalTimeout, NO_SIGNAL_TIMEOUT_MS)
        }
        handler.postDelayed(noSignalTimeout, NO_SIGNAL_TIMEOUT_MS)

        timeout = Runnable {
            if (delivered) return@Runnable
            AllohaLog.d("Opener") {
                "session timed out after ${TIMEOUT_MS}ms streamReady=$streamReady " +
                    "refreshedMaster=$refreshedMasterReady"
            }
            finish(OpenResult.Failed)
        }
        handler.postDelayed(timeout, TIMEOUT_MS)

        val bridge = AllohaSessionBridge(
            handler = handler,
            scope = scope,
            sourcesSink = { sources, headers ->
                AllohaLog.d("Opener") { "bnsi parsed audio=${sources.audioTracks.size} subtitles=${sources.subtitles.size}" }
                CookieManager.getInstance().flush()
                liveSession.initialize(sources, headers)
                liveSession.preselectQuality(preferredQualityLabel)
                streamReady = true
                handler.removeCallbacks(noSignalTimeout)
                deliverWhenReady()
                if (!delivered) {
                    handler.removeCallbacks(masterWaitTimeout)
                    handler.postDelayed(masterWaitTimeout, MASTER_WAIT_TIMEOUT_MS)
                }
            },
            sourcesFailureSink = { error ->
                AllohaLog.w("Opener", "bnsi parse failed: ${error.message}", error)
                finish(
                    if (error is AllohaSourceUnavailableException) OpenResult.Unavailable
                    else OpenResult.Failed
                )
            },
            configUpdateSink = { edgeHash, ttlSeconds, headers ->
                liveSession.updateHeaders(headers + ("accepts-controls" to edgeHash))
                liveSession.updateExpiry(ttlSeconds)
                val pendingMaster = pendingHostChangeMaster
                if (pendingMaster != null) {
                    handler.removeCallbacks(hostChangeFallback)
                    liveSession.updateMasterUrl(pendingMaster)
                    pendingHostChangeMaster = null
                    AllohaLog.d("Opener") { "host change confirmed by fresh config_update" }
                }
            },
            masterSink = { masterUrl, headers ->
                val previousHost = liveSession.masterHost()
                val newHost = runCatching { URL(masterUrl).host }.getOrNull()
                liveSession.updateHeaders(headers)
                when {
                    liveSession.isRotating -> {
                        liveSession.updateMasterUrl(masterUrl)
                        AllohaLog.d("Opener") { "master refreshed for staged rotation host=$newHost" }
                    }

                    refreshedMasterReady && previousHost != null && newHost != null &&
                        previousHost != newHost -> handleHostChange(
                        liveSession = liveSession,
                        masterUrl = masterUrl,
                        hostChangeRestarts = hostChangeRestarts,
                        onRestart = {
                            hostChangeRestarts++
                            liveSession.refresh()
                        },
                        onPending = { pendingMaster ->
                            pendingHostChangeMaster = pendingMaster
                            handler.removeCallbacks(hostChangeFallback)
                            handler.postDelayed(hostChangeFallback, HOST_CHANGE_CONFIG_WAIT_MS)
                        },
                    )

                    else -> {
                        liveSession.updateMasterUrl(masterUrl)
                        refreshedMasterReady = true
                        AllohaLog.d("Opener") { "master refreshed host=$newHost" }
                        deliverWhenReady()
                    }
                }
            },
            streamHeadersSink = { headers ->
                CookieManager.getInstance().flush()
                liveSession.updateHeaders(headers)
            },
            dubbingUnavailableSink = {
                AllohaLog.w("Opener", "site rendered a dubbing-unavailable message")
                finish(OpenResult.Unavailable)
            },
            logSink = { message -> AllohaLog.d("WebView") { message } },
        )

        val parsedUrl = URL(iframeUrl)
        val baseUrl = "${parsedUrl.protocol}://${parsedUrl.host.lowercase(Locale.ROOT)}/"
        val html = wrapperHtml(iframeUrl)
        val webView = webViewPool.acquire(appContext, sessionUserAgent).apply {
            removeJavascriptInterface(BRIDGE_NAME)
            addJavascriptInterface(bridge, BRIDGE_NAME)
            onResume()
            resumeTimers()
            loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        }
        reloadWrapper = {
            webView.settings.userAgentString = sessionUserAgent
            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        }
        liveSession.attach(
            webView = webView,
            refresh = {
                webView.settings.userAgentString = sessionUserAgent
                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            },
            release = webViewPool::release,
        )
        liveSession.onIdentityRejected = { marker ->
            if (identityRejected.compareAndSet(false, true)) {
                AllohaLog.d("Opener") { "identity rejected marker=$marker" }
            }
        }

        continuation.invokeOnCancellation { handler.post { liveSession.close() } }
    }

    private fun handleHostChange(
        liveSession: AllohaSession,
        masterUrl: String,
        hostChangeRestarts: Int,
        onRestart: () -> Unit,
        onPending: (String) -> Unit,
    ) {
        if (!liveSession.hasSeenConfigUpdate && hostChangeRestarts < MAX_HOST_CHANGE_RESTARTS) {
            AllohaLog.d("Opener") { "master host changed without config_update, restarting session" }
            onRestart()
        } else if (!liveSession.hasSeenConfigUpdate) {
            liveSession.updateMasterUrl(masterUrl)
        } else {
            onPending(masterUrl)
        }
    }

    private fun resetIdentityIfRejected() {
        if (!identityRejected.getAndSet(false)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastIdentityResetAtMs < IDENTITY_RESET_MIN_INTERVAL_MS) return
        lastIdentityResetAtMs = now
        AllohaLog.d("Opener") { "previous identity was rejected, resetting cookies and storage" }
        webViewPool.resetIdentity()
    }

    private fun desktopUserAgent(): String {
        val os = DESKTOP_OS.random()
        val version = Random.nextInt(130, 136)
        return "Mozilla/5.0 ($os) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$version.0.0.0 Safari/537.36"
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val NO_SIGNAL_TIMEOUT_MS = 10_000L
        const val MAX_WRAPPER_RELOADS = 2
        const val MASTER_WAIT_TIMEOUT_MS = 6_000L
        const val HOST_CHANGE_CONFIG_WAIT_MS = 10_000L
        const val MAX_HOST_CHANGE_RESTARTS = 3
        const val IDENTITY_RESET_MIN_INTERVAL_MS = 30_000L

        val DESKTOP_OS = listOf(
            "Windows NT 10.0; Win64; x64",
            "Windows NT 11.0; Win64; x64",
            "Macintosh; Intel Mac OS X 10_15_7",
            "Macintosh; Intel Mac OS X 14_4_1",
            "X11; Linux x86_64",
            "X11; Ubuntu; Linux x86_64",
        )
    }
}
