package io.putdotio.android.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.putdotio.android.R
import io.putdotio.sdk.files.PlaybackSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Hands a resolved source to the receiver: the source and the subtitle to show first. */
internal typealias MobileCastMediaLoader =
    suspend (PlaybackSource, String?) -> PlaybackRepositoryResult<PlaybackCastMedia>

/** The player screen's Cast: its top-bar button, and the session it casts to while one runs. */
internal class PlayerCast(
    val controller: MobileCastController?,
    val button: @Composable (Modifier) -> Unit,
    val casting: CastingTarget?,
)

internal class CastingTarget(
    val controller: MobileCastController,
    val media: MobileCastMediaLoader,
    val deviceName: String,
)

/** No Cast without a controller (no Google Play services) or a way to load media. */
@Composable
internal fun rememberPlayerCast(
    cast: MobileCastController?,
    castMedia: MobileCastMediaLoader?,
    onOpenPicker: () -> Unit,
): PlayerCast {
    val availability by (cast?.availability ?: NoCast).collectAsState()
    if (cast == null || castMedia == null) return NoPlayerCast
    val device = availability.connectedDevice
    return PlayerCast(
        controller = cast,
        button = { modifier -> MobileCastButton(availability, onOpenPicker, modifier) },
        casting = if (device == null) null else CastingTarget(cast, castMedia, device),
    )
}

private val NoCast: StateFlow<CastAvailability> = MutableStateFlow(CastAvailability.Unsupported)
private val NoPlayerCast = PlayerCast(controller = null, button = {}, casting = null)

/**
 * Where the local player picks [fileId] up after a Cast session ended, taken once; the preferences
 * keep it so a recreated player starts there too.
 */
@Composable
internal fun rememberCastHandover(
    playerCast: PlayerCast,
    fileId: Long,
    preferences: RetainedPlayerPreferences,
): CastHandover? {
    val handover = remember(fileId) { playerCast.controller?.takeHandover(fileId) }
    LaunchedEffect(handover) {
        if (handover != null) {
            preferences.retainPlayback(RetainedPlayback(handover.positionMillis, handover.playing))
            preferences.playbackSpeed = handover.speed
        }
    }
    return handover
}

/** The device picker over the whole player, except in the picture-in-picture window. */
@Composable
internal fun MobileCastPickerHost(
    cast: MobileCastController?,
    open: MutableState<Boolean>,
    pictureInPicture: Boolean,
    modifier: Modifier = Modifier,
) {
    if (cast == null || !open.value || pictureInPicture) return
    MobileCastPicker(cast.gateway, onDismiss = { open.value = false }, modifier = modifier)
}

/**
 * The player while a Cast session runs. Local playback has stopped: the video's own player left with
 * the local screen, and session audio stops here. The file goes to the receiver from where local
 * playback stood, unless the receiver already plays it; the transport, scrubber and speed drive the
 * receiver. Ending the session brings the local player back where the receiver stopped.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun MobileCastPlayback(
    state: PlaybackState,
    cast: MobileCastController,
    castMedia: MobileCastMediaLoader,
    deviceName: String,
    preferences: RetainedPlayerPreferences,
    playerFactory: MobilePlayerFactory,
    subtitleStartupPolicy: SubtitleStartupPolicy?,
    castButton: @Composable (Modifier) -> Unit,
    onBack: () -> Unit,
    onSourceRequired: (Long?) -> Unit,
    neighbours: PlaybackNeighbours,
    onPlay: (PlaybackTarget) -> Unit,
) {
    val context = LocalContext.current
    val target = state.target
    val fileId = target.fileId.value
    val source = (state.content as? PlaybackContent.Ready)?.source
    var failed by remember(fileId) { mutableStateOf(false) }
    val currentPolicy by rememberUpdatedState(subtitleStartupPolicy)
    val currentOnSourceRequired by rememberUpdatedState(onSourceRequired)
    LaunchedEffect(fileId, source) {
        failed = false
        // One player at a time: session audio stops once the receiver takes over.
        if (target.mediaType == PlaybackMediaType.AUDIO) playerFactory.stopAudio(context)
        val load: suspend (PlaybackSource) -> PlaybackRepositoryResult<PlaybackCastMedia> = { source ->
            castMedia(source, castSubtitleKey(source, preferences.subtitleSelection, currentPolicy))
        }
        failed = !cast.handOver(state, load, preferences, playerFactory, currentOnSourceRequired)
    }

    val player = cast.remotePlayer
    val status by cast.status.collectAsState()
    val current = status?.takeIf { it.fileId == fileId }
    val label = stringResource(
        if (failed || current?.state == CastRemoteState.Failed) {
            R.string.mobile_cast_failed
        } else {
            R.string.mobile_cast_playing_on
        },
        deviceName,
    )
    val seekEnabled = current?.seekable == true
    val intervals = if (target.mediaType == PlaybackMediaType.AUDIO) AudioSeekIntervals else VideoSeekIntervals
    val seek = { direction: SeekDirection -> player.seekBy(intervals, direction) }

    if (target.mediaType == PlaybackMediaType.AUDIO) {
        MobileNowPlayingScreen(
            player = player,
            title = target.name,
            neighbours = neighbours,
            seekEnabled = seekEnabled,
            onSeek = seek,
            onScrub = {},
            onPlay = onPlay,
            onAudioSelectionChanged = {},
            castButton = castButton,
            castLabel = label,
            modifier = Modifier.zIndex(2f),
        )
        return
    }
    val options = rememberPlayerOptionsSnapshot(player)
    var speedSheet by rememberSaveable(fileId) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        MobileCastFrameLabel(label)
        MobilePlayerChrome(
            player = player,
            title = target.name,
            visible = !speedSheet,
            seekEnabled = seekEnabled,
            onSeek = seek,
            onScrub = {},
            onBack = onBack,
            modifier = Modifier.zIndex(2f),
            topActions = { castButton(Modifier) },
            settings = {
                if (options.canChangeSpeed) MobileSpeedChip(options, { speedSheet = true })
            },
        )
        MobilePlayerSideSheet(
            visible = speedSheet,
            title = stringResource(R.string.mobile_playback_speed),
            onClose = { speedSheet = false },
            modifier = Modifier.zIndex(2.5f),
        ) {
            MobileSpeedSheetContent(player, options)
        }
    }
}

/**
 * Sends the file to the receiver from where local playback stood, attaches to it when the receiver
 * already plays it, or asks for a source first. False when the file cannot go to a receiver.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private suspend fun MobileCastController.handOver(
    state: PlaybackState,
    castMedia: suspend (PlaybackSource) -> PlaybackRepositoryResult<PlaybackCastMedia>,
    preferences: RetainedPlayerPreferences,
    playerFactory: MobilePlayerFactory,
    onSourceRequired: (Long?) -> Unit,
): Boolean {
    val target = state.target
    val fileId = target.fileId.value
    val ready = state.content as? PlaybackContent.Ready
    val source = ready?.source
    val local = CastHandover(
        positionMillis = preferences.positionMillis ?: state.resumePositionMillis
            ?: source?.startFromSeconds?.toPlaybackMillis() ?: 0L,
        playing = preferences.resumeAfterLifecyclePause,
        speed = preferences.playbackSpeed,
    )
    val item = ready?.let {
        playerFactory.reportableItem(it.source.toMediaItem(target.name, target.mediaType), it.useStartFrom)
    } ?: bareItem(fileId, target.name)
    val media = when {
        holds(fileId) -> null
        source == null -> null.also { onSourceRequired(local.positionMillis) }
        else -> castMedia(source)
    }
    when (media) {
        null -> if (holds(fileId)) attach(item)
        is PlaybackRepositoryResult.Success -> cast(media.value, item, local)
        is PlaybackRepositoryResult.Failure -> Unit
    }
    return media !is PlaybackRepositoryResult.Failure
}

/** [intervals]' step from where the receiver is, within the file. */
private fun androidx.media3.common.Player.seekBy(intervals: SeekIntervals, direction: SeekDirection) {
    val step = intervals.millis(direction)
    val position = currentPosition + if (direction == SeekDirection.Backward) -step else step
    seekTo(position.coerceIn(0L, duration.takeIf { it > 0L } ?: Long.MAX_VALUE))
}

/** A receiver already playing [fileId], reached without a source: controls only, no reporting lease. */
private fun bareItem(fileId: Long, title: String): MediaItem =
    MediaItem.Builder()
        .setMediaId(fileId.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()
