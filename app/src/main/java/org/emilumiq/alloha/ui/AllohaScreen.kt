package org.emilumiq.alloha.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.emilumiq.alloha.ui.theme.AllohaTheme

@Composable
fun AllohaScreen(viewModel: AllohaViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var sourceExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.onScreenReady() }

    fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        activity?.requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val playerContent = remember {
        movableContentOf<Modifier> { modifier ->
            PlayerSurface(
                player = viewModel.player,
                modifier = modifier,
                onFullscreenToggle = { enabled -> setFullscreen(enabled) },
            )
        }
    }

    BackHandler(enabled = fullscreen) { setFullscreen(false) }

    AllohaTheme {
        if (fullscreen) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                playerContent(Modifier.fillMaxSize())
            }
        } else {
            AllohaContent(
                state = state,
                playerContent = playerContent,
                viewModel = viewModel,
                sourceExpanded = sourceExpanded || state.catalog == null,
                onSourceExpandedChange = { sourceExpanded = it },
                onEnterFullscreen = { setFullscreen(true) },
            )
        }
    }
}

@Composable
private fun AllohaContent(
    state: AllohaUiState,
    playerContent: @Composable (Modifier) -> Unit,
    viewModel: AllohaViewModel,
    sourceExpanded: Boolean,
    onSourceExpandedChange: (Boolean) -> Unit,
    onEnterFullscreen: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppHeader(playerVisible = state.playerVisible, isPlaying = state.isPlaying)

            state.resumeCandidate?.let { candidate ->
                ResumeSection(
                    snapshot = candidate,
                    onResume = viewModel::resumeFromCandidate,
                    onDismiss = viewModel::dismissResumeCandidate,
                )
            }

            if (state.playerVisible) {
                playerContent(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                PlaybackSection(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    isPlaying = state.isPlaying,
                    onSeek = viewModel::seekTo,
                    onTogglePlay = viewModel::togglePlayPause,
                    onEnterFullscreen = onEnterFullscreen,
                )
                StreamSection(
                    audioTracks = state.audioTracks,
                    qualities = state.qualities,
                    subtitles = state.subtitleTracks,
                    selectedAudioId = state.selectedAudioId,
                    selectedQuality = state.selectedQuality,
                    selectedSubtitleIndex = state.selectedSubtitleIndex,
                    onAudioSelected = viewModel::selectAudioTrack,
                    onQualitySelected = viewModel::selectQuality,
                    onSubtitleSelected = viewModel::selectSubtitle,
                )
            }

            if (state.phase == SessionPhase.OPENING || state.phase == SessionPhase.RESTORING) {
                LoadingSection(status = state.status)
            }

            state.errorMessage?.let { message ->
                ErrorSection(message = message, onRetry = viewModel::retry)
            }

            SourceSection(
                token = state.token,
                kp = state.kp,
                loading = state.catalogLoading,
                stale = state.catalogStale,
                expanded = sourceExpanded,
                onToggleExpanded = onSourceExpandedChange,
                onTokenChange = viewModel::updateToken,
                onKpChange = viewModel::updateKp,
                onLoad = viewModel::fetchCatalog,
            )

            CatalogSection(
                state = state,
                onSeasonSelected = viewModel::selectSeason,
                onEpisodeSelected = viewModel::selectEpisode,
                onTranslationSelected = viewModel::selectTranslation,
                onWatch = viewModel::playSelected,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
