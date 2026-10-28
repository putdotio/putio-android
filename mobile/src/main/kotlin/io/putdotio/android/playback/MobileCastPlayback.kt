package io.putdotio.android.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.onKeyEvent
import androidx.media3.common.util.Util
import io.putdotio.android.MobileKeyCommand
import io.putdotio.android.MobileShortcutScope
import io.putdotio.android.mobileKeyCommand
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.putdotio.android.R
import io.putdotio.sdk.files.PlaybackSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The receiver's form of the file on screen: from its resolved source, or, given none, from a fresh
 * resolution that leaves the screen's own state alone; then the subtitle to show first.
 */
internal typealias MobileCastMediaLoader =
    suspend (PlaybackSource?, String?) -> PlaybackRepositoryResult<PlaybackCastMedia>

/** [MobileCastMediaLoader] for [target], resolving it again when no source is at hand. */
internal suspend fun PlaybackRepository.castMediaFor(
    target: PlaybackTarget,
    source: PlaybackSource?,
    subtitleKey: String?,
): PlaybackRepositoryResult<PlaybackCastMedia> {
    val resolved = source?.let { PlaybackRepositoryResult.Success(PlaybackResolution.Ready(it)) } ?: resolve(target)
    val ready = (resolved as? PlaybackRepositoryResult.Success)?.value as? PlaybackResolution.Ready
    return when {
        ready != null -> castMedia(target, ready.source, subtitleKey)
        resolved is PlaybackRepositoryResult.Failure -> resolved
        else -> PlaybackRepositoryResult.Failure(PlaybackFailure.MediaUnsupported(UncastableResolution()))
    }
}

/** A file that resolved to a conversion or an unsupported type has nothing a receiver can play yet. */
private class UncastableResolution : IllegalStateException("The file resolved to nothing a receiver plays")

/** The player screen's Cast: its top-bar button, and the session it casts to while one runs. */
internal class PlayerCast(
    val controller: MobileCastController?,
    val button: @Composable (Modifier) -> Unit,
    val casting: CastingTarget?,
) {
    /** Whether a session's receiver plays [fileId], so the player attaches instead of asking to resume. */
    fun attaches(fileId: Long): Boolean = casting?.controller?.holds(fileId) == true

    /** Whether a session that ended left [fileId] a place to resume, which answers the resume prompt. */
    fun handsBack(fileId: Long): Boolean = controller?.hasHandover(fileId) == true
}

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
    val status by (cast?.status ?: NoStatus).collectAsState()
    if (cast == null || castMedia == null) return NoPlayerCast
    // A session reconnecting still owns playback while the receiver's last status stands.
    val device = availability.connectedDevice
        ?: (availability as? CastAvailability.Connecting)?.takeIf { status != null }?.let { it.deviceName.orEmpty() }
    return PlayerCast(
        controller = cast,
        button = { modifier ->
            MobileCastButton(availability, onOpenPicker, cast.gateway::localNetworkAnswered, modifier)
        },
        casting = if (device == null) null else CastingTarget(cast, castMedia, device),
    )
}

private val NoCast: StateFlow<CastAvailability> = MutableStateFlow(CastAvailability.Unsupported)
private val NoStatus: StateFlow<CastRemoteStatus?> = MutableStateFlow(null)
private val NoPlayerCast = PlayerCast(controller = null, button = {}, casting = null)

/**
 * Where the local player picks [fileId] up after a Cast session ended, taken once. It moves into the
 * preferences at once, which then answer as local playback moves on, so a player recreated later
 * starts where local playback got to rather than where the receiver stopped.
 */
@Composable
internal fun rememberCastHandover(
    playerCast: PlayerCast,
    fileId: Long,
    preferences: RetainedPlayerPreferences,
): CastHandover? {
    var handover by remember(fileId) { mutableStateOf(playerCast.controller?.takeHandover(fileId)) }
    LaunchedEffect(handover) {
        handover?.let {
            preferences.retainPlayback(RetainedPlayback(it.positionMillis, it.playing))
            preferences.playbackSpeed = it.speed
            handover = null
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
    autoplayNextVideo: Boolean,
    onPlaybackEnded: () -> Unit,
) {
    val context = LocalContext.current
    val target = state.target
    val fileId = target.fileId.value
    val source = (state.content as? PlaybackContent.Ready)?.source
    var failed by remember(fileId) { mutableStateOf(false) }
    // Try again reruns the handover: a lookup that failed for a moment never needs a new session.
    var attempt by remember(fileId) { mutableIntStateOf(0) }
    val currentPolicy by rememberUpdatedState(subtitleStartupPolicy)
    val currentOnSourceRequired by rememberUpdatedState(onSourceRequired)
    // A session reconnecting holds the handover until it answers again.
    val connected = cast.availability.collectAsState().value is CastAvailability.Connected
    LaunchedEffect(fileId, source, connected, attempt) {
        if (!connected) return@LaunchedEffect
        failed = false
        // One player at a time: session audio stops once the receiver takes over, for a video too.
        playerFactory.stopAudio(context)
        val load: suspend (PlaybackSource?) -> PlaybackRepositoryResult<PlaybackCastMedia> = { source ->
            val selection = preferences.subtitleSelection
            val loaded = castMedia(source, source?.let { castSubtitleKey(it, selection, currentPolicy) })
            val playlistSubtitle = source?.let { castPlaylistSubtitle(it, selection, currentPolicy) }
            if (loaded is PlaybackRepositoryResult.Success && playlistSubtitle != null) {
                PlaybackRepositoryResult.Success(loaded.value.copy(playlistSubtitle = playlistSubtitle))
            } else {
                loaded
            }
        }
        failed = !cast.handOver(state, load, preferences, playerFactory, currentOnSourceRequired)
    }
    // A file that finished on the receiver ends as one that finished here: the next one is cast in turn.
    val currentAutoplayNextVideo by rememberUpdatedState(autoplayNextVideo)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    LaunchedEffect(cast, fileId) {
        cast.finished.collect { if (it == fileId && currentAutoplayNextVideo) currentOnPlaybackEnded() }
    }

    val player = cast.remotePlayer
    val status by cast.status.collectAsState()
    val current = status?.takeIf { it.fileId == fileId }
    // A receiver that failed to load the file no longer holds it, so another handover sends it again.
    val castFailed = failed || current?.state == CastRemoteState.Failed
    val label = stringResource(
        if (castFailed) R.string.mobile_cast_failed else R.string.mobile_cast_playing_on,
        deviceName,
    )
    val onRetry: (() -> Unit)? = if (castFailed) ({ attempt++ }) else null
    val seekEnabled = current?.seekable == true
    val intervals = if (target.mediaType == PlaybackMediaType.AUDIO) AudioSeekIntervals else VideoSeekIntervals
    val seek = { direction: SeekDirection -> player.seekBy(intervals, direction) }

    // Space and the arrows drive the receiver as they drive the local player.
    val keyFocus = remember { FocusRequester() }
    LaunchedEffect(keyFocus) { keyFocus.requestFocus() }
    val keys = Modifier
        .onKeyEvent { event -> playerKeyCommand(event.nativeKeyEvent) { player.runCastKey(it, seek, onBack) } }
        .focusRequester(keyFocus)
        .focusTarget()
        .focusGroup()
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
            onCastRetry = onRetry,
            modifier = Modifier.zIndex(2f).then(keys),
        )
    } else {
        MobileCastVideo(player, target.name, label, onRetry, seekEnabled, seek, onBack, castButton, keys)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun MobileCastVideo(
    player: CastRemotePlayer,
    title: String,
    label: String,
    onRetry: (() -> Unit)?,
    seekEnabled: Boolean,
    seek: (SeekDirection) -> Unit,
    onBack: () -> Unit,
    castButton: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
    val options = rememberPlayerOptionsSnapshot(player)
    var speedSheet by rememberSaveable { mutableStateOf(false) }
    // Back from the keyboard closes the Speed sheet before it leaves the player, as on local video.
    val closeSheet = Modifier.onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        val back = mobileKeyCommand(MobileShortcutScope.Player, key.keyCode, key.metaState) == MobileKeyCommand.Back
        (speedSheet && back).also { closes ->
            if (closes && key.action == android.view.KeyEvent.ACTION_DOWN) speedSheet = false
        }
    }
    Box(closeSheet.then(modifier).fillMaxSize()) {
        MobileCastFrameLabel(label, onRetry)
        MobilePlayerChrome(
            player = player,
            title = title,
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

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun androidx.media3.common.Player.runCastKey(
    command: MobileKeyCommand,
    seek: (SeekDirection) -> Unit,
    onBack: () -> Unit,
) {
    when (command) {
        MobileKeyCommand.PlayPause -> Util.handlePlayPauseButtonAction(this)
        MobileKeyCommand.SeekBack -> seek(SeekDirection.Backward)
        MobileKeyCommand.SeekForward -> seek(SeekDirection.Forward)
        MobileKeyCommand.Back -> onBack()
        else -> Unit
    }
}

/**
 * Sends the file to the receiver from where local playback stood, attaches to it when the receiver
 * already plays it, or asks for a source first. False when the file cannot go to a receiver.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private suspend fun MobileCastController.handOver(
    state: PlaybackState,
    castMedia: suspend (PlaybackSource?) -> PlaybackRepositoryResult<PlaybackCastMedia>,
    preferences: RetainedPlayerPreferences,
    playerFactory: MobilePlayerFactory,
    onSourceRequired: (Long?) -> Unit,
): Boolean {
    val target = state.target
    val fileId = target.fileId.value
    val ready = state.content as? PlaybackContent.Ready
    // Unknown stays unknown, so resolving a source still finds put.io's saved position.
    val retained = preferences.positionMillis ?: state.resumePositionMillis
    // Session audio reopens without a source; its lease rests on the account's resume setting alone.
    val item = playerFactory.reportableItem(
        ready?.source?.toMediaItem(target.name, target.mediaType) ?: bareItem(fileId, target.name),
        ready?.useStartFrom ?: true,
    )
    val holds = holds(fileId)
    if (!holds && ready == null) {
        onSourceRequired(retained)
        return true
    }
    // The controls follow this file at once, so a failed lookup never leaves them driving another file;
    // the media resolves after, for Play to send again once the file has finished there.
    attach(item)
    val loaded = castMedia(ready?.source) as? PlaybackRepositoryResult.Success
    when {
        holds -> attach(item, loaded?.value)
        loaded != null -> cast(
            loaded.value,
            item,
            CastHandover(
                positionMillis = retained ?: ready?.source?.startFromSeconds?.toPlaybackMillis() ?: 0L,
                playing = preferences.resumeAfterLifecyclePause,
                speed = preferences.playbackSpeed,
            ),
        )
    }
    return holds || loaded != null
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
