package org.emilumiq.alloha.parser

import android.os.Handler
import android.webkit.WebView
import org.emilumiq.alloha.log.AllohaLog
import org.emilumiq.alloha.proxy.AllohaStreamProxy
import org.emilumiq.alloha.proxy.AllohaStreamState
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val STAGED_COMMIT_TIMEOUT_MS = 8_000L

class AllohaSession(
    private val handler: Handler,
    val iframeUrl: String,
) {
    private val headers = ConcurrentHashMap<String, String>()
    private val masterUrl = AtomicReference("")
    private val generation = AtomicLong(0L)
    private val expiry = AtomicLong(0L)
    private val view = AtomicReference<WebView?>(null)
    private val refreshAction = AtomicReference<(() -> Unit)?>(null)
    private val releaseAction = AtomicReference<((WebView) -> Unit)?>(null)
    private val audioTracks = AtomicReference<List<AllohaParsedAudioTrack>>(emptyList())
    private val subtitles = AtomicReference<List<AllohaSubtitleTrack>>(emptyList())
    private val selectedAudioId = AtomicReference<String?>(null)
    private val selectedQuality = AtomicReference<String?>(null)
    private val proxy = AtomicReference<AllohaStreamProxy?>(null)
    private val streamStateLock = Any()

    var onIdentityRejected: ((marker: String) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null

    private class StagedState {
        val headers = mutableMapOf<String, String>()
        var masterUrl: String? = null
        var expiry: Long = 0L
        var sources: AllohaParsedSources? = null
        var hasReady = false
        var hasRefreshedMaster = false
        var hasConfigUpdate = false

        fun isComplete(requireConfigUpdate: Boolean): Boolean =
            hasReady && hasRefreshedMaster && (!requireConfigUpdate || hasConfigUpdate)
    }

    private var staging: StagedState? = null
    private var sawConfigUpdate = false

    private val stagedCommitTimeout = Runnable {
        synchronized(streamStateLock) {
            if (staging == null) return@Runnable
            AllohaLog.d("Session") {
                "staged commit timed out ready=${staging?.hasReady} " +
                    "master=${staging?.hasRefreshedMaster} config=${staging?.hasConfigUpdate}"
            }
            commitStagedLocked()
        }
    }

    val isRotating: Boolean get() = synchronized(streamStateLock) { staging != null }
    val hasSeenConfigUpdate: Boolean get() = synchronized(streamStateLock) { sawConfigUpdate }
    val hasProxy: Boolean get() = proxy.get() != null

    fun attach(webView: WebView, refresh: () -> Unit, release: (WebView) -> Unit) {
        view.set(webView)
        refreshAction.set(refresh)
        releaseAction.set(release)
    }

    fun initialize(sources: AllohaParsedSources, streamHeaders: Map<String, String>) {
        synchronized(streamStateLock) {
            val staged = staging
            if (staged != null) {
                staged.sources = sources
                staged.masterUrl = sources.audioTracks.firstOrNull()?.qualities?.values?.lastOrNull()
                staged.headers.clear()
                staged.headers.putAll(streamHeaders.mapKeys { it.key.lowercase() })
                staged.hasReady = true
                commitStagedIfReadyLocked()
                return
            }
            applySourcesLocked(sources)
            masterUrl.set(masterFor(selectedAudioId.get(), selectedQuality.get()).orEmpty())
            headers.clear()
            headers.putAll(streamHeaders.mapKeys { it.key.lowercase() })
            generation.incrementAndGet()
        }
        notifyStateChanged()
    }

    private fun applySourcesLocked(sources: AllohaParsedSources) {
        audioTracks.set(sources.audioTracks)
        subtitles.set(sources.subtitles)
        val previousAudio = selectedAudioId.get()
        selectedAudioId.set(
            previousAudio?.takeIf { id -> sources.audioTracks.any { it.track.id == id } }
                ?: sources.audioTracks.firstOrNull { it.track.isDefault }?.track?.id
                ?: sources.audioTracks.firstOrNull()?.track?.id
        )
        val available = currentTrack()?.qualities.orEmpty()
        selectedQuality.set(selectedQuality.get()?.takeIf(available::containsKey))
    }

    fun preselectQuality(label: String?) {
        synchronized(streamStateLock) {
            if (label == null) return
            if (currentTrack()?.qualities?.containsKey(label) == true) {
                selectedQuality.set(label)
            }
        }
    }

    fun startProxy() {
        if (proxy.get() != null) return
        AllohaLog.d("Session") { "starting loopback proxy" }
        proxy.compareAndSet(
            null,
            AllohaStreamProxy(
                streamStateProvider = ::currentStreamState,
                masterProvider = ::masterFor,
                requestSessionRefresh = ::refresh,
                onIdentityRejected = { marker -> onIdentityRejected?.invoke(marker) },
            )
        )
    }

    fun updateHeaders(value: Map<String, String>) {
        synchronized(streamStateLock) {
            val staged = staging
            if (staged != null) {
                staged.headers.putAll(value.mapKeys { it.key.lowercase() })
                commitStagedIfReadyLocked()
            } else {
                headers.putAll(value.mapKeys { it.key.lowercase() })
            }
        }
    }

    fun updateMasterUrl(value: String) {
        synchronized(streamStateLock) {
            if (value.isBlank()) return
            val staged = staging
            if (staged != null) {
                staged.masterUrl = value
                staged.hasRefreshedMaster = true
                commitStagedIfReadyLocked()
            } else {
                masterUrl.set(value)
            }
        }
    }

    fun updateExpiry(ttlSeconds: Int) {
        synchronized(streamStateLock) {
            sawConfigUpdate = true
            val value = System.currentTimeMillis() + ttlSeconds * 1_000L
            val staged = staging
            if (staged != null) {
                staged.expiry = value
                staged.hasConfigUpdate = true
                commitStagedIfReadyLocked()
            } else {
                expiry.set(value)
            }
        }
    }

    private fun commitStagedIfReadyLocked() {
        if (staging?.isComplete(requireConfigUpdate = sawConfigUpdate) == true) commitStagedLocked()
    }

    private fun commitStagedLocked() {
        val staged = staging ?: return
        staging = null
        handler.removeCallbacks(stagedCommitTimeout)

        staged.sources?.let(::applySourcesLocked)
        staged.masterUrl?.takeIf(String::isNotBlank)?.let(masterUrl::set)
        if (staged.expiry > 0L) expiry.set(staged.expiry)
        if (staged.headers.isNotEmpty()) {
            headers.clear()
            headers.putAll(staged.headers)
        }
        generation.incrementAndGet()
        AllohaLog.d("Session") { "staged commit generation=${generation.get()} host=${masterHost()}" }
        notifyStateChanged()
    }

    private fun currentStreamState(): AllohaStreamState = synchronized(streamStateLock) {
        AllohaStreamState(
            headers = headers.toMap(),
            masterUrl = masterUrl.get(),
            generation = generation.get(),
            expiresAtMs = expiry.get().takeIf { it > 0L },
        )
    }

    fun expiresAtMs(): Long? = expiry.get().takeIf { it > 0L }

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        val expiresAt = expiresAtMs() ?: return false
        return expiresAt <= now
    }

    fun expiresInMs(now: Long = System.currentTimeMillis()): Long? =
        expiresAtMs()?.let { it - now }

    fun refresh() {
        synchronized(streamStateLock) {
            if (staging != null) return
            staging = StagedState()
        }
        handler.post {
            handler.removeCallbacks(stagedCommitTimeout)
            handler.postDelayed(stagedCommitTimeout, STAGED_COMMIT_TIMEOUT_MS)
            refreshAction.get()?.invoke()
        }
    }

    fun selectQuality(label: String) {
        synchronized(streamStateLock) {
            if (currentTrack()?.qualities?.containsKey(label) == true) {
                selectedQuality.set(label)
            }
        }
        notifyStateChanged()
    }

    fun selectAudioTrack(id: String) {
        synchronized(streamStateLock) {
            if (audioTracks.get().none { it.track.id == id }) return
            selectedAudioId.set(id)
            val available = currentTrack()?.qualities.orEmpty()
            selectedQuality.set(selectedQuality.get()?.takeIf(available::containsKey))
            masterFor(id, selectedQuality.get())?.let(masterUrl::set)
        }
        AllohaLog.d("Session") { "audio selected id=$id" }
        notifyStateChanged()
    }

    fun currentAudioId(): String? = selectedAudioId.get()

    fun currentQualityLabel(): String? = selectedQuality.get()

    fun audioTracks(): List<AllohaAudioTrack> = audioTracks.get().map(AllohaParsedAudioTrack::track)

    fun qualities(audioId: String? = selectedAudioId.get()): LinkedHashMap<String, String> {
        val tracks = audioTracks.get()
        val track = tracks.firstOrNull { it.track.id == audioId } ?: currentTrack() ?: return linkedMapOf()
        return LinkedHashMap(track.qualities)
    }

    fun currentAudioLabel(): String? = currentTrack()?.track?.label

    fun subtitles(): List<AllohaSubtitleTrack> = subtitles.get()

    fun playbackUrl(audioId: String? = selectedAudioId.get(), quality: String? = selectedQuality.get()): String {
        val activeProxy = proxy.get() ?: error("Proxy is not started")
        return activeProxy.streamUrl(audioId, quality)
    }

    fun proxiedSubtitles(): List<AllohaSubtitleTrack> {
        val activeProxy = proxy.get() ?: return emptyList()
        return subtitles.get().map { it.copy(url = activeProxy.sideloadUrl(it.url)) }
    }

    fun masterHost(): String? = runCatching { URL(masterUrl.get()).host }.getOrNull()

    fun close() {
        handler.removeCallbacks(stagedCommitTimeout)
        synchronized(streamStateLock) { staging = null }
        handler.post {
            proxy.getAndSet(null)?.close()
            refreshAction.set(null)
            val release = releaseAction.getAndSet(null)
            view.getAndSet(null)?.let {
                it.removeJavascriptInterface(BRIDGE_NAME)
                it.stopLoading()
                if (release != null) {
                    it.onPause()
                    release(it)
                } else {
                    it.destroy()
                }
            }
        }
    }

    private fun currentTrack(): AllohaParsedAudioTrack? {
        val tracks = audioTracks.get()
        val id = selectedAudioId.get()
        return tracks.firstOrNull { it.track.id == id }
            ?: tracks.firstOrNull { it.track.isDefault }
            ?: tracks.firstOrNull()
    }

    private fun masterFor(audioId: String?, quality: String?): String? {
        val tracks = audioTracks.get()
        val track = tracks.firstOrNull { it.track.id == audioId } ?: currentTrack() ?: return null
        val label = quality ?: selectedQuality.get()
        return label?.let(track.qualities::get) ?: track.qualities.values.lastOrNull()
    }

    private fun notifyStateChanged() {
        val callback = onStateChanged ?: return
        handler.post { callback() }
    }
}
