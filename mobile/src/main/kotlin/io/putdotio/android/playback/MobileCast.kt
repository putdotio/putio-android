package io.putdotio.android.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSubtitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where Cast stands on this device; the Cast button shows only once a receiver is in reach. */
internal sealed interface CastAvailability {
    /** No Google Play services, or Cast failed to start: no button. */
    data object Unsupported : CastAvailability

    data object NoDevices : CastAvailability

    data object Available : CastAvailability

    data class Connecting(val deviceName: String?) : CastAvailability

    data class Connected(val deviceName: String) : CastAvailability
}

internal val CastAvailability.showsButton: Boolean
    get() = this != CastAvailability.Unsupported && this != CastAvailability.NoDevices

internal val CastAvailability.connectedDevice: String?
    get() = (this as? CastAvailability.Connected)?.deviceName

internal enum class CastRemoteState { Idle, Buffering, Playing, Paused, Ended, Failed }

/** What the receiver last said about its media; [fileId] is null for media this app did not send. */
internal data class CastRemoteStatus(
    val fileId: Long?,
    val state: CastRemoteState,
    val positionMillis: Long,
    val durationMillis: Long?,
    val speed: Float = 1f,
    val speedSupported: Boolean = false,
    val seekable: Boolean = true,
    /** The clock reading the position was taken at; a playing position moves on from it. */
    val sampledAtMillis: Long,
    /** Whether play was last asked for; a loading or buffering receiver plays only if so. */
    val playIntent: Boolean? = null,
    /** The title and kind the media was sent with, so the app can reopen it without a screen's help. */
    val title: String? = null,
    val audio: Boolean = false,
    /** When the session ended, for a last status: the position moves on no further than this. */
    val endedAtMillis: Long? = null,
) {
    val playing: Boolean
        get() = state == CastRemoteState.Playing || (state == CastRemoteState.Buffering && playIntent != false)

    fun positionAt(nowMillis: Long): Long {
        val until = endedAtMillis?.coerceAtMost(nowMillis) ?: nowMillis
        val moved = if (state == CastRemoteState.Playing) ((until - sampledAtMillis) * speed).toLong() else 0L
        val position = (positionMillis + moved.coerceAtLeast(0L)).coerceAtLeast(0L)
        return durationMillis?.let(position::coerceAtMost) ?: position
    }
}

internal enum class CastDeviceKind { Tv, Speaker, Other }

/** A receiver in the picker: [model] names the device, [audioOnly] one that has no screen. */
internal data class CastDevice(
    val id: String,
    val name: String,
    val model: String?,
    val audioOnly: Boolean,
    val kind: CastDeviceKind,
    val selected: Boolean,
)

/** Local playback as it hands over: where, whether it plays, and at what speed. */
internal data class CastHandover(
    val positionMillis: Long,
    val playing: Boolean,
    val speed: Float = 1f,
)

/** The Cast SDK, behind what the app needs of it. Calls and callbacks run on the main thread. */
internal interface CastGateway : CastRoutes, CastRemote {
    val availability: StateFlow<CastAvailability>

    /** Ends the session and stops the receiver's playback. */
    fun stopCasting()
}

/** The receivers the picker lists. */
internal interface CastRoutes {
    /** The receivers in reach while [startDiscovery] runs. */
    val devices: StateFlow<List<CastDevice>>

    fun startDiscovery()

    fun stopDiscovery()

    fun select(deviceId: String)
}

/** The connected receiver's media: its status, and the commands that drive it. */
internal interface CastRemote {
    /** Null without a session. */
    val status: StateFlow<CastRemoteStatus?>

    /**
     * The last status of the session that ended most recently, set before [status] clears. A
     * collector may see only the clearing, so the handback reads this instead.
     */
    val endedStatus: CastRemoteStatus?

    fun load(media: PlaybackCastMedia, startMillis: Long, autoplay: Boolean, speed: Float)

    fun play()

    fun pause()

    fun seekTo(positionMillis: Long)

    fun setSpeed(speed: Float)
}

/**
 * Hands playback between this device and a Cast receiver. Process-wide, as the Cast session is: it
 * keeps the receiver's last word on each file, so the local player resumes where the receiver
 * stopped once the session ends, whichever screen is open then.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileCastController(
    val gateway: CastGateway,
    scope: CoroutineScope,
    private val clock: () -> Long,
) {
    val availability: StateFlow<CastAvailability> get() = gateway.availability
    val status: StateFlow<CastRemoteStatus?> get() = gateway.status

    /** The receiver as a Media3 player, for the player screen's controls and position reporting. */
    val remotePlayer: CastRemotePlayer = CastRemotePlayer(gateway, scope, clock) { replay(it) }

    private val handovers = mutableMapOf<Long, CastHandover>()
    private var lastMedia: PlaybackCastMedia? = null

    /** The ended session whose position has been handed back, so it is handed back once. */
    private var handedBack: CastRemoteStatus? = null

    /** Set when signing out ends the session, whose position then goes nowhere. */
    private var discardNextEnd = false

    init {
        scope.launch {
            gateway.status.collect { status ->
                // The status clears only as the session ends: freeze where the receiver stood, for
                // the file's next local start. A file that finished hands back stopped.
                if (status == null) takeEnded()?.let { (fileId, handover) -> handovers[fileId] = handover }
            }
        }
    }

    /**
     * Whether the receiver plays [fileId] now, so a screen opened on it attaches instead of loading.
     * Media that finished or failed there is sent again.
     */
    fun holds(fileId: Long): Boolean =
        gateway.status.value?.let { it.fileId == fileId && it.state in LIVE_STATES } == true

    /**
     * Sends [media] to the receiver from where local playback stood, unless the receiver already
     * plays it; [item] is the local item with its reporting lease, so the receiver's positions
     * reach put.io. Does nothing without a session.
     */
    fun cast(media: PlaybackCastMedia, item: MediaItem, local: CastHandover): Boolean {
        if (gateway.availability.value !is CastAvailability.Connected) return false
        remotePlayer.adopt(item)
        val loads = !holds(media.fileId)
        if (loads) {
            handovers.remove(media.fileId)
            lastMedia = media
            gateway.load(media, local.positionMillis.coerceAtLeast(0L), local.playing, local.speed)
        }
        return loads
    }

    /**
     * Ties the receiver's controls to [item] without loading it: the receiver already plays it.
     * [media], when known, is what Play sends again once the file has finished there.
     */
    fun attach(item: MediaItem, media: PlaybackCastMedia? = null) {
        remotePlayer.adopt(item)
        if (media != null) lastMedia = media
    }

    /**
     * The file the receiver plays for this app, named as it was sent; null without one. Read from the
     * receiver itself, so it answers in a process that never opened a player.
     */
    fun castingTarget(): PlaybackTarget? {
        val status = gateway.status.value?.takeIf { gateway.availability.value is CastAvailability.Connected }
        val fileId = status?.fileId ?: return null
        return PlaybackTarget(
            FilesItemId(fileId),
            status.title.orEmpty(),
            if (status.audio) PlaybackMediaType.AUDIO else PlaybackMediaType.VIDEO,
        )
    }

    /** Signing out ends the session: the receiver holds the account's download token. */
    fun end() {
        lastMedia = null
        handovers.clear()
        // The session this ends hands nothing back: the next account must not resume the last one's,
        // nor one that ended a moment ago and is not handed back yet.
        handedBack = gateway.endedStatus
        discardNextEnd = gateway.availability.value.let {
            it is CastAvailability.Connected || it is CastAvailability.Connecting
        }
        gateway.stopCasting()
    }

    /** Where the local player picks [fileId] up after a session ended; taken once, null while casting. */
    fun takeHandover(fileId: Long): CastHandover? {
        if (gateway.availability.value is CastAvailability.Connected) return null
        // The session may have ended a moment ago, before the status collector ran.
        if (gateway.status.value == null && gateway.endedStatus?.fileId == fileId) {
            takeEnded()?.let { (endedFileId, handover) -> handovers[endedFileId] = handover }
        }
        return handovers.remove(fileId)
    }

    private fun takeEnded(): Pair<Long, CastHandover>? {
        val ended = gateway.endedStatus?.takeIf { it !== handedBack } ?: return null
        handedBack = ended
        val discarded = discardNextEnd
        discardNextEnd = false
        return ended.fileId?.takeUnless { discarded }
            ?.let { it to CastHandover(ended.positionAt(clock()), ended.playing, ended.speed) }
    }

    // A file that finished or failed has left the receiver; playing it again sends it once more.
    private fun replay(startMillis: Long) {
        val media = lastMedia ?: return
        if (gateway.status.value?.fileId != media.fileId) return
        gateway.load(media, startMillis, autoplay = true, speed = gateway.status.value?.speed ?: 1f)
    }
}

/** Receiver states with the file still playable there. */
private val LIVE_STATES = setOf(CastRemoteState.Buffering, CastRemoteState.Playing, CastRemoteState.Paused)

/**
 * The subtitle the receiver shows first, by the same rules as the local player: none when the
 * account hides subtitles or the viewer turned them off, the viewer's pick, else put.io's default
 * (the list's first) when the account selects one.
 */
internal fun castSubtitleKey(
    source: PlaybackSource,
    selection: SubtitleSelection?,
    policy: SubtitleStartupPolicy?,
): String? {
    val keys = (source.subtitles as? PlaybackSubtitles.Sidecar)?.tracks.orEmpty().map { it.key }
    if (keys.isEmpty() || policy?.showSubtitles == false) return null
    return when (selection) {
        SubtitleSelection.Off -> null
        is SubtitleSelection.Track -> selection.identity.id?.let { id ->
            // Media3 may prefix a sidecar track's id with its source's index.
            keys.firstOrNull { key -> id == key || id.endsWith(":$key") }
        }
        SubtitleSelection.Automatic -> keys.first()
        null -> keys.first().takeIf { policy?.autoSelectSubtitles != false }
    }
}

/**
 * The receiver as a Media3 [Player]: the player screen's transport, scrubber and speed sheet drive
 * it, and the position observer reports its positions. A command shows at once and settles with
 * the receiver's next status.
 */
@UnstableApi
internal class CastRemotePlayer(
    private val gateway: CastGateway,
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val replay: (Long) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var item: MediaItem? = null
    private var status: CastRemoteStatus? = gateway.status.value
    private val pending = mutableListOf<SettableFuture<Any?>>()
    private val handler = Handler(Looper.getMainLooper())

    /** A finished file sent again, until the receiver reports on it. */
    private var replaying = false

    init {
        scope.launch {
            gateway.status.collect {
                status = it
                replaying = false
                settle()
                invalidateState()
            }
        }
    }

    /** Keeps an item that reports positions over a bare one for the same file. */
    fun adopt(item: MediaItem) {
        val current = this.item
        if (current?.mediaId == item.mediaId && current.reportsPositions && !item.reportsPositions) return
        this.item = item
        invalidateState()
    }

    override fun getState(): State {
        val current = status
        val adopted = item?.takeIf { current?.fileId != null && it.mediaId == current.fileId.toString() }
        val builder = State.Builder()
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).build())
            .setAvailableCommands(commands(current?.takeIf { adopted != null }))
        if (current == null || adopted == null) return builder.setPlaybackState(STATE_IDLE).build()
        val duration = current.durationMillis?.takeIf { it > 0L }
        return builder
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(adopted.mediaId)
                        .setMediaItem(adopted)
                        .setDurationUs(duration?.let { it * MICROS_PER_MILLI } ?: C.TIME_UNSET)
                        .setIsSeekable(current.seekable)
                        .build(),
                ),
            )
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(
                when (current.state) {
                    CastRemoteState.Buffering -> STATE_BUFFERING
                    CastRemoteState.Playing, CastRemoteState.Paused -> STATE_READY
                    CastRemoteState.Ended -> STATE_ENDED
                    CastRemoteState.Idle, CastRemoteState.Failed -> STATE_IDLE
                },
            )
            .setPlayWhenReady(current.playing, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackParameters(PlaybackParameters(current.speed))
            .setContentPositionMs(
                if (current.state == CastRemoteState.Playing) {
                    PositionSupplier.getExtrapolating(current.positionAt(clock()), current.speed)
                } else {
                    PositionSupplier.getConstant(current.positionAt(clock()))
                },
            )
            .build()
    }

    private fun commands(current: CastRemoteStatus?): Player.Commands {
        val builder = Player.Commands.Builder()
        if (current == null) return builder.build()
        builder.addAll(
            COMMAND_PLAY_PAUSE,
            COMMAND_GET_CURRENT_MEDIA_ITEM,
            COMMAND_GET_TIMELINE,
            COMMAND_GET_METADATA,
        )
        if (current.seekable) builder.addAll(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_DEFAULT_POSITION)
        if (current.speedSupported) builder.add(COMMAND_SET_SPEED_AND_PITCH)
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        when {
            // Play after a finish seeks to the start first, which already sent the file again.
            playWhenReady && replaying -> Unit
            playWhenReady && status?.left == true -> replayFrom(0L)
            playWhenReady -> gateway.play()
            else -> gateway.pause()
        }
        return awaitStatus()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val position = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        if (status?.left == true && !replaying) replayFrom(position) else gateway.seekTo(position)
        return awaitStatus()
    }

    private fun replayFrom(positionMillis: Long) {
        replaying = true
        replay(positionMillis)
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        gateway.setSpeed(playbackParameters.speed)
        return awaitStatus()
    }

    // The placeholder state holds until the receiver answers, or for a while when it does not.
    private fun awaitStatus(): ListenableFuture<*> {
        val future = SettableFuture.create<Any?>()
        pending += future
        handler.postDelayed({ if (pending.remove(future)) future.set(null) }, STATUS_WAIT_MILLIS)
        return future
    }

    private fun settle() {
        val settled = pending.toList()
        pending.clear()
        settled.forEach { it.set(null) }
    }

    private val CastRemoteStatus.left: Boolean
        get() = state == CastRemoteState.Ended || state == CastRemoteState.Failed

    private val MediaItem.reportsPositions: Boolean
        get() = mediaMetadata.extras?.containsKey(PLAYBACK_REPORTING_LEASE_KEY) == true

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
        const val STATUS_WAIT_MILLIS = 5_000L
    }
}
