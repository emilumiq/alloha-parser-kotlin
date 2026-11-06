package org.emilumiq.alloha.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import org.emilumiq.alloha.api.AllohaApiClient
import org.emilumiq.alloha.api.AllohaApiException
import org.emilumiq.alloha.api.AllohaCatalog
import org.emilumiq.alloha.api.AllohaCatalogParser
import org.emilumiq.alloha.log.AllohaLog
import org.emilumiq.alloha.parser.AllohaSession
import org.emilumiq.alloha.parser.AllohaSessionOpener
import org.emilumiq.alloha.player.AllohaPlayerController
import org.emilumiq.alloha.player.PlaybackSnapshot
import org.emilumiq.alloha.player.PlaybackStore

class AllohaViewModel(application: Application) : AndroidViewModel(application) {
    private val apiClient = AllohaApiClient()
    private val opener = AllohaSessionOpener(application)
    private val store = PlaybackStore(application)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var playerControllerInstance: AllohaPlayerController? = null
    private val playerController: AllohaPlayerController
        get() = playerControllerInstance ?: AllohaPlayerController(getApplication()).apply {
            onPlaybackError = ::handlePlaybackError
            onPlaybackStateChanged = ::publishPlaybackState
            playerControllerInstance = this
        }

    private val _uiState = MutableStateFlow(AllohaUiState())
    val uiState: StateFlow<AllohaUiState> = _uiState.asStateFlow()

    val player get() = playerController.player

    private var session: AllohaSession? = null
    private var openJob: Job? = null
    private var catalogJob: Job? = null
    private var tickerJob: Job? = null

    private var prefetchJob: Deferred<AllohaSession?>? = null
    private var prefetchedSession: AllohaSession? = null
    private var prefetchIframeUrl: String? = null

    private var backgroundedAtElapsed = 0L
    private var resumeOnForeground = false
    private var appInForeground = true
    private var recoveryInFlight = false
    private var consecutiveRecoveries = 0
    private var ticksSinceSnapshot = 0

    private var lastIframeUrl = ""
    private var lastTitle = ""
    private var lastEpisodeLabel: String? = null

    init {
        val savedQuery = store.loadQuery()
        _uiState.value = AllohaUiState(
            token = savedQuery?.first ?: DEFAULT_TOKEN,
            kp = savedQuery?.second ?: DEFAULT_KP,
            resumeCandidate = store.load()?.takeIf(::isResumable),
        )
        loadCachedCatalog()
        startMaintenanceLoop()
    }

    fun onScreenReady() {
        prefetchSelection(PREFETCH_START_DELAY_MS)
    }

    fun updateToken(value: String) {
        _uiState.update { it.copy(token = value) }
    }

    fun updateKp(value: String) {
        _uiState.update { it.copy(kp = value.filter(Char::isDigit)) }
    }

    fun fetchCatalog() {
        val token = _uiState.value.token.trim()
        val kp = _uiState.value.kp.trim()
        if (token.isBlank() || kp.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Укажите token и KP ID") }
            return
        }
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            _uiState.update { it.copy(catalogLoading = true, errorMessage = null) }
            try {
                applyCatalog(loadCatalog(token, kp))
                prefetchSelection()
                _uiState.update { it.copy(catalogLoading = false, catalogStale = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AllohaApiException) {
                _uiState.update { it.copy(catalogLoading = false, errorMessage = error.message) }
            }
        }
    }

    private fun refreshCatalogSilently() {
        val token = _uiState.value.token.trim()
        val kp = _uiState.value.kp.trim()
        if (token.isBlank() || kp.isBlank()) return
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            try {
                applyCatalog(loadCatalog(token, kp))
                _uiState.update { it.copy(catalogStale = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AllohaApiException) {
                AllohaLog.w("ViewModel", "background catalog refresh failed: ${error.message}", error)
            }
        }
    }

    private suspend fun loadCatalog(token: String, kp: String): AllohaCatalog {
        val rawJson = apiClient.fetchCatalogJson(token, kp)
        val catalog = AllohaCatalogParser.parse(rawJson)
        store.saveQuery(token, kp)
        store.saveCatalog(token, kp, rawJson)
        return catalog
    }

    private fun loadCachedCatalog() {
        val state = _uiState.value
        val cached = store.loadCatalog(state.token, state.kp) ?: return
        val catalog = runCatching { AllohaCatalogParser.parse(cached.rawJson) }.getOrNull() ?: return
        applyCatalog(catalog)
        val stale = System.currentTimeMillis() - cached.savedAtMs > CATALOG_STALE_MS
        _uiState.update { it.copy(catalogStale = stale) }
        if (stale) refreshCatalogSilently()
    }

    private fun applyCatalog(catalog: AllohaCatalog) {
        _uiState.update {
            it.copy(
                catalog = catalog,
                seasonIndex = 0,
                episodeIndex = 0,
                translationIndex = 0,
                status = if (catalog.isSerial) {
                    "${catalog.title} · сезонов: ${catalog.seasons.size}"
                } else {
                    catalog.title
                },
                errorMessage = null,
            )
        }
    }

    fun selectSeason(index: Int) {
        _uiState.update { it.copy(seasonIndex = index, episodeIndex = 0, translationIndex = 0) }
        prefetchSelection()
    }

    fun selectEpisode(index: Int) {
        _uiState.update { it.copy(episodeIndex = index, translationIndex = 0) }
        prefetchSelection()
    }

    fun selectTranslation(index: Int) {
        _uiState.update { it.copy(translationIndex = index) }
        prefetchSelection()
    }

    fun playSelected() {
        val state = _uiState.value
        val catalog = state.catalog ?: return
        val iframeUrl = selectedIframeUrl(state)
        if (iframeUrl.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "Для выбранного элемента нет источника") }
            return
        }
        startSession(
            SessionRequest(
                iframeUrl = iframeUrl,
                title = catalog.title,
                episodeLabel = if (catalog.isSerial) buildEpisodeLabel(state) else null,
                startPositionMs = 0L,
                audioId = null,
                qualityLabel = null,
                phase = SessionPhase.OPENING,
                status = "Получаем поток…",
                playWhenReady = true,
            )
        )
    }

    fun selectAudioTrack(id: String) {
        val current = session ?: return
        current.selectAudioTrack(id)
        publishSessionState()
        if (_uiState.value.phase == SessionPhase.READY) {
            playerController.switchStream(current, id, current.currentQualityLabel())
        }
        saveSnapshot()
    }

    fun selectQuality(label: String) {
        val current = session ?: return
        current.selectQuality(label)
        publishSessionState()
        if (_uiState.value.phase == SessionPhase.READY) {
            playerController.switchStream(current, current.currentAudioId(), label)
        }
        saveSnapshot()
    }

    fun selectSubtitle(index: Int?) {
        playerController.selectSubtitles(index)
        _uiState.update { it.copy(selectedSubtitleIndex = index) }
    }

    fun togglePlayPause() {
        if (playerController.wantsPlay) {
            playerController.pause()
        } else {
            playerController.resume()
        }
        publishPlaybackState()
        saveSnapshot()
    }

    fun seekTo(positionMs: Long) {
        if (_uiState.value.durationMs <= 0L) return
        playerController.seekTo(positionMs)
        publishPlaybackState()
        saveSnapshot()
    }

    fun retry() {
        val snapshot = buildSnapshot(wasPlaying = true) ?: store.load()
        if (snapshot != null) {
            restorePlayback(snapshot, playWhenReady = true)
        } else {
            fetchCatalog()
        }
    }

    fun resumeFromCandidate() {
        val snapshot = _uiState.value.resumeCandidate ?: return
        _uiState.update { it.copy(resumeCandidate = null) }
        restorePlayback(snapshot, playWhenReady = snapshot.wasPlaying)
    }

    fun dismissResumeCandidate() {
        _uiState.update { it.copy(resumeCandidate = null) }
        store.clear()
    }

    fun onAppBackgrounded() {
        if (!appInForeground) return
        appInForeground = false
        backgroundedAtElapsed = SystemClock.elapsedRealtime()
        clearPrefetch()
        if (session == null) return
        resumeOnForeground = playerController.wantsPlay
        playerController.pause()
        saveSnapshot(wasPlaying = resumeOnForeground)
        publishPlaybackState()
    }

    fun onAppForegrounded() {
        if (appInForeground) return
        appInForeground = true
        val awayMs = if (backgroundedAtElapsed > 0L) {
            SystemClock.elapsedRealtime() - backgroundedAtElapsed
        } else {
            0L
        }
        backgroundedAtElapsed = 0L
        val shouldResume = resumeOnForeground
        resumeOnForeground = false

        val current = session
        if (current == null || !_uiState.value.playerVisible) return

        val sessionDead = awayMs >= SESSION_KEEP_ALIVE_MS ||
            current.isExpired() ||
            current.isRotating ||
            playerController.player.playerError != null

        if (sessionDead) {
            AllohaLog.d("ViewModel") { "rebuilding session after ${awayMs}ms in background" }
            val snapshot = buildSnapshot(wasPlaying = shouldResume) ?: return
            restorePlayback(snapshot, playWhenReady = shouldResume)
        } else {
            if (shouldResume) playerController.resume()
            publishPlaybackState()
            refreshSessionIfExpiring()
        }
    }

    private fun refreshSessionIfExpiring() {
        val current = session ?: return
        val expiresIn = current.expiresInMs() ?: return
        if (expiresIn in 0..SESSION_REFRESH_LEAD_MS && !current.isRotating) {
            AllohaLog.d("ViewModel") { "refreshing session, expires in ${expiresIn / 1000}s" }
            current.refresh()
        }
    }

    private fun prefetchSelection(delayMs: Long = PREFETCH_DEBOUNCE_MS) {
        val state = _uiState.value
        if (!appInForeground || session != null || openJob?.isActive == true) return
        if (state.phase != SessionPhase.IDLE && state.phase != SessionPhase.ERROR) return
        val iframeUrl = selectedIframeUrl(state) ?: return
        schedulePrefetch(iframeUrl, delayMs)
    }

    private fun schedulePrefetch(iframeUrl: String, delayMs: Long) {
        if (session != null || prefetchIframeUrl == iframeUrl) return
        clearPrefetch()
        prefetchIframeUrl = iframeUrl
        AllohaLog.d("ViewModel") { "prefetch scheduled" }
        prefetchJob = viewModelScope.async {
            delay(delayMs)
            if (session != null || !appInForeground) return@async null
            val result = opener.open(iframeUrl)
            val session = (result as? AllohaSessionOpener.OpenResult.Ready)?.session
            prefetchedSession = session
            AllohaLog.d("ViewModel") { "prefetch finished ready=${session != null}" }
            session
        }
    }

    private fun clearPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchIframeUrl = null
        prefetchedSession?.close()
        prefetchedSession = null
    }

    private suspend fun obtainSession(iframeUrl: String, qualityLabel: String?): AllohaSessionOpener.OpenResult {
        val pending = prefetchJob
        if (pending != null && prefetchIframeUrl == iframeUrl) {
            prefetchJob = null
            prefetchIframeUrl = null
            prefetchedSession = null
            val prefetched = withTimeoutOrNull(PREFETCH_AWAIT_MS) { pending.await() }
            if (prefetched == null) {
                pending.cancel()
            } else if (prefetched.hasProxy && !prefetched.isExpired()) {
                AllohaLog.d("ViewModel") { "using prefetched session" }
                return AllohaSessionOpener.OpenResult.Ready(prefetched)
            } else {
                prefetched.close()
            }
        }
        clearPrefetch()
        return opener.open(iframeUrl, qualityLabel)
    }

    private fun selectedIframeUrl(state: AllohaUiState): String? {
        val catalog = state.catalog ?: return null
        return if (catalog.isSerial) state.selectedTranslation?.iframeUrl else catalog.movieIframeUrl
    }

    private fun startSession(request: SessionRequest) {
        AllohaLog.d("ViewModel") { "session requested iframe=${request.iframeUrl.take(80)}" }
        if (request.startPositionMs == 0L) saveSnapshot()
        closeSession()
        playerController.stop()
        openJob?.cancel()
        lastIframeUrl = request.iframeUrl
        lastTitle = request.title
        lastEpisodeLabel = request.episodeLabel
        _uiState.update {
            it.copy(
                phase = request.phase,
                playerVisible = false,
                isPlaying = false,
                errorMessage = null,
                resumeCandidate = null,
                subtitleTracks = emptyList(),
                selectedSubtitleIndex = null,
                status = request.status,
            )
        }
        openJob = viewModelScope.launch {
            val result = try {
                obtainSession(request.iframeUrl, request.qualityLabel)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AllohaLog.w("ViewModel", "session open failed", error)
                AllohaSessionOpener.OpenResult.Failed
            }
            when (result) {
                is AllohaSessionOpener.OpenResult.Ready -> startPlayback(result.session, request)
                AllohaSessionOpener.OpenResult.Unavailable -> failSession(
                    "У выбранной озвучки нет доступного источника. Попробуйте другую озвучку."
                )

                AllohaSessionOpener.OpenResult.Failed -> failSession(
                    "Не удалось получить поток. Проверьте соединение и попробуйте ещё раз."
                )
            }
        }
    }

    private fun startPlayback(opened: AllohaSession, request: SessionRequest) {
        clearPrefetch()
        session = opened
        opened.onStateChanged = ::publishSessionState
        consecutiveRecoveries = 0

        val trackIds = opened.audioTracks().map { it.id }
        val audioId = request.audioId?.takeIf(trackIds::contains) ?: opened.currentAudioId()
        if (audioId != null && audioId != opened.currentAudioId()) {
            opened.selectAudioTrack(audioId)
        }
        val available = opened.qualities(audioId)
        val quality = request.qualityLabel?.takeIf(available::containsKey)
            ?: opened.currentQualityLabel()
            ?: available.keys.lastOrNull()
        quality?.let(opened::selectQuality)

        publishSessionState()
        playerController.play(
            session = opened,
            audioId = audioId,
            qualityLabel = quality,
            startPositionMs = request.startPositionMs,
            playWhenReady = request.playWhenReady,
        )
        _uiState.update { it.copy(phase = SessionPhase.READY, playerVisible = true) }
        startTicker()
        saveSnapshot()
        AllohaLog.d("ViewModel") {
            "playback started audio=$audioId quality=$quality " +
                "position=${request.startPositionMs}ms subtitles=${opened.subtitles().size}"
        }
    }

    private fun failSession(message: String) {
        closeSession()
        playerController.stop()
        _uiState.update {
            it.copy(
                phase = SessionPhase.ERROR,
                playerVisible = false,
                isPlaying = false,
                errorMessage = message,
                status = message,
            )
        }
    }

    private fun publishSessionState() {
        val current = session ?: return
        val audioId = current.currentAudioId()
        val qualities = current.qualities(audioId)
        _uiState.update {
            it.copy(
                audioTracks = current.audioTracks(),
                selectedAudioId = audioId,
                qualities = qualities.keys.toList().asReversed(),
                selectedQuality = current.currentQualityLabel(),
                subtitleTracks = current.subtitles(),
                status = buildString {
                    append(current.currentAudioLabel() ?: "Поток")
                    current.currentQualityLabel()?.let { label -> append(" · ").append(label) }
                },
            )
        }
    }

    private fun publishPlaybackState() {
        val controller = playerControllerInstance ?: return
        _uiState.update {
            it.copy(
                isPlaying = controller.player.isPlaying,
                positionMs = controller.positionMs,
                durationMs = controller.durationMs,
            )
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        ticksSinceSnapshot = 0
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                if (session == null) continue
                publishPlaybackState()
                if (playerControllerInstance?.wantsPlay != true || !appInForeground) continue
                ticksSinceSnapshot++
                if (ticksSinceSnapshot >= TICKS_PER_SNAPSHOT) {
                    ticksSinceSnapshot = 0
                    saveSnapshot()
                }
            }
        }
    }

    private fun closeSession() {
        tickerJob?.cancel()
        tickerJob = null
        session?.onStateChanged = null
        session?.close()
        session = null
    }

    private fun handlePlaybackError(message: String) {
        if (recoveryInFlight || session == null) return
        if (consecutiveRecoveries >= MAX_RECOVERIES) {
            _uiState.update {
                it.copy(
                    phase = SessionPhase.ERROR,
                    errorMessage = "Поток недоступен: $message",
                    status = "Поток недоступен",
                    playerVisible = false,
                )
            }
            return
        }
        consecutiveRecoveries++
        recoveryInFlight = true
        AllohaLog.d("ViewModel") { "playback error, recovery $consecutiveRecoveries/$MAX_RECOVERIES" }
        _uiState.update {
            it.copy(phase = SessionPhase.RESTORING, status = "Переподключаемся…", errorMessage = null)
        }
        val snapshot = buildSnapshot(wasPlaying = true)
        viewModelScope.launch {
            delay(RECOVERY_DELAY_MS)
            recoveryInFlight = false
            if (snapshot != null) restorePlayback(snapshot, playWhenReady = true)
        }
    }

    private fun restorePlayback(snapshot: PlaybackSnapshot, playWhenReady: Boolean) {
        if (snapshot.iframeUrl.isBlank()) return
        startSession(
            SessionRequest(
                iframeUrl = snapshot.iframeUrl,
                title = snapshot.title,
                episodeLabel = snapshot.episodeLabel,
                startPositionMs = snapshot.positionMs,
                audioId = snapshot.audioId,
                qualityLabel = snapshot.qualityLabel,
                phase = SessionPhase.RESTORING,
                status = "Восстанавливаем поток…",
                playWhenReady = playWhenReady,
            )
        )
    }

    private data class SessionRequest(
        val iframeUrl: String,
        val title: String,
        val episodeLabel: String?,
        val startPositionMs: Long,
        val audioId: String?,
        val qualityLabel: String?,
        val phase: SessionPhase,
        val status: String,
        val playWhenReady: Boolean,
    )

    private fun buildSnapshot(wasPlaying: Boolean): PlaybackSnapshot? {
        if (lastIframeUrl.isBlank()) return null
        val controller = playerControllerInstance
        return PlaybackSnapshot(
            title = lastTitle,
            episodeLabel = lastEpisodeLabel,
            iframeUrl = lastIframeUrl,
            audioId = session?.currentAudioId(),
            audioLabel = session?.currentAudioLabel(),
            qualityLabel = session?.currentQualityLabel(),
            positionMs = controller?.positionMs ?: 0L,
            durationMs = controller?.durationMs ?: 0L,
            wasPlaying = wasPlaying,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun saveSnapshot(wasPlaying: Boolean = playerControllerInstance?.wantsPlay ?: false) {
        val snapshot = buildSnapshot(wasPlaying) ?: return
        if (snapshot.positionMs <= 0L && snapshot.durationMs <= 0L) return
        store.save(snapshot)
    }

    private fun isResumable(snapshot: PlaybackSnapshot): Boolean {
        if (snapshot.iframeUrl.isBlank()) return false
        if (System.currentTimeMillis() - snapshot.updatedAtMs > SNAPSHOT_TTL_MS) return false
        if (snapshot.positionMs <= 0L) return false
        val duration = snapshot.durationMs
        return duration <= 0L || snapshot.positionMs < duration - 5_000L
    }

    private fun buildEpisodeLabel(state: AllohaUiState): String? {
        val season = state.selectedSeason ?: return null
        val episode = state.selectedEpisode ?: return null
        val translation = state.selectedTranslation?.name
        return buildString {
            append("S${season.number} E${episode.number}")
            translation?.let { append(" · ").append(it) }
        }
    }

    private fun startMaintenanceLoop() {
        viewModelScope.launch {
            while (isActive) {
                delay(MAINTENANCE_INTERVAL_MS)
                if (!appInForeground || _uiState.value.phase != SessionPhase.READY) continue
                refreshSessionIfExpiring()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveSnapshot()
        clearPrefetch()
        closeSession()
        opener.destroy()
        playerControllerInstance?.release()
        playerControllerInstance = null
    }

    private companion object {
        const val DEFAULT_TOKEN = "ffbd312217e27c4245f2678afe1881"
        const val DEFAULT_KP = "464963"
        const val CATALOG_STALE_MS = 15 * 60 * 1000L
        const val SNAPSHOT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val SESSION_KEEP_ALIVE_MS = 45_000L
        const val SESSION_REFRESH_LEAD_MS = 25_000L
        const val MAINTENANCE_INTERVAL_MS = 5_000L
        const val RECOVERY_DELAY_MS = 1_200L
        const val MAX_RECOVERIES = 3
        const val TICKS_PER_SNAPSHOT = 5
        const val PREFETCH_DEBOUNCE_MS = 700L
        const val PREFETCH_START_DELAY_MS = 2_500L
        const val PREFETCH_AWAIT_MS = 4_000L
    }
}
