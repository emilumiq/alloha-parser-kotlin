package org.emilumiq.alloha.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import org.emilumiq.alloha.parser.AllohaSession
import org.emilumiq.alloha.parser.AllohaSubtitleTrack

@UnstableApi
class AllohaPlayerController(context: Context) {
    private val appContext = context.applicationContext

    private val trackSelector = DefaultTrackSelector(appContext).apply {
        setParameters(
            buildUponParameters()
                .setPreferredAudioLanguage("ru")
                .setPreferredTextLanguage("ru")
        )
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setTrackSelector(trackSelector)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 60_000, 1_000, 2_500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    var onPlaybackError: ((String) -> Unit)? = null
    var onPlaybackStateChanged: (() -> Unit)? = null

    private var audioOverrideApplied = false
    private var desiredSubtitleIndex: Int? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError?.invoke(error.message ?: "Ошибка воспроизведения")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onPlaybackStateChanged?.invoke()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                onPlaybackStateChanged?.invoke()
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyAudioTrackPreference(tracks)
                applySubtitleSelection(tracks)
            }
        })
    }

    val positionMs: Long get() = player.currentPosition

    val durationMs: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L

    val wantsPlay: Boolean get() = player.playWhenReady

    fun play(
        session: AllohaSession,
        audioId: String?,
        qualityLabel: String?,
        startPositionMs: Long,
        playWhenReady: Boolean = true,
    ) {
        val item = buildMediaItem(session, audioId, qualityLabel)
        audioOverrideApplied = false
        desiredSubtitleIndex = null
        player.setMediaItem(item, startPositionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    fun switchStream(
        session: AllohaSession,
        audioId: String?,
        qualityLabel: String?,
    ) {
        val resumePosition = player.currentPosition
        val resumePlayWhenReady = player.playWhenReady
        val item = buildMediaItem(session, audioId, qualityLabel)
        audioOverrideApplied = false
        player.setMediaItem(item, resumePosition.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = resumePlayWhenReady
    }

    fun selectSubtitles(index: Int?) {
        desiredSubtitleIndex = index
        applySubtitleSelection(player.currentTracks)
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun resume() {
        player.playWhenReady = true
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        player.release()
    }

    private fun buildMediaItem(
        session: AllohaSession,
        audioId: String?,
        qualityLabel: String?,
    ): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(session.playbackUrl(audioId, qualityLabel))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
        val subtitles = session.proxiedSubtitles()
            .mapNotNull(::toSubtitleConfiguration)
        if (subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitles)
        }
        return builder.build()
    }

    private fun toSubtitleConfiguration(track: AllohaSubtitleTrack): MediaItem.SubtitleConfiguration? {
        val mimeType = when (track.format?.lowercase()) {
            "vtt" -> "text/vtt"
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            else -> return null
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
            .setMimeType(mimeType)
            .setLabel(track.label)
            .apply { track.language?.let { setLanguage(it) } }
            .build()
    }

    private fun applySubtitleSelection(tracks: Tracks) {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val index = desiredSubtitleIndex
        val parameters = trackSelector.buildUponParameters()
        if (index == null || index !in groups.indices) {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            parameters.setOverrideForType(TrackSelectionOverride(groups[index].mediaTrackGroup, 0))
        }
        trackSelector.setParameters(parameters)
    }

    private fun applyAudioTrackPreference(tracks: Tracks) {
        if (audioOverrideApplied) return
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.size <= 1) return
        audioOverrideApplied = true
        val group = audioGroups.first()
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                .build()
        )
    }
}
