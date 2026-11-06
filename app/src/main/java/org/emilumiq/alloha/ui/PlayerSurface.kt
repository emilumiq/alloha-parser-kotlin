@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.emilumiq.alloha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@Composable
fun PlayerSurface(
    player: ExoPlayer,
    modifier: Modifier,
    onFullscreenToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val toggleState = rememberUpdatedState(onFullscreenToggle)
    val playerView = remember {
        PlayerView(context).apply {
            useController = true
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setFullscreenButtonClickListener { enabled -> toggleState.value(enabled) }
        }
    }
    DisposableEffect(playerView) {
        onDispose { playerView.player = null }
    }
    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { playerView },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
