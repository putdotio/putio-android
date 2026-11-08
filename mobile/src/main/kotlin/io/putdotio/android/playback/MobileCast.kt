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
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where Cast stands on this device; the Cast button shows once a receiver is in reach, or while
 * Android needs the local network allowed to find one.
 */
internal sealed interface CastAvailability {
    /** No Google Play services, or Cast failed to start: no button. */
    data object Unsupported : CastAvailability

    data object NoDevices : CastAvailability

    /**
     * Android 17 hides the receivers until the user allows the local network: the button shows, and
     * its tap asks.
     */
    data object NeedsLocalNetwork : CastAvailability

    data object Available : CastAvailability

    data class Connecting(val deviceName: String?) : CastAvailability

    data class Connected(val deviceName: String) : CastAvailability
}

internal val CastAvailability.showsButton: Boolean
    get() = this != CastAvailability.Unsupported && this != CastAvailability.NoDevices

internal val CastAvailability.connectedDevice: String?
    get() = (this as? CastAvailability.Connected)?.deviceName

internal val CastAvailability.casting: Boolean
    get() = this is CastAvailability.Connected || this is CastAvailability.Connecting

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

    val movesHere: StateFlow<Boolean>

    /** Ends the session and stops the receiver's playback. */
    fun stopCasting()

    /**
     * Ends the session for signing out: it hands nothing back, whenever it ends and whatever the
     * receiver reported by then, so the next account never resumes the last one's.
     */
    fun endForSignOut()

    /** The user answered the local network prompt, so availability may have changed. */
    fun localNetworkAnswered()
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

internal interface CastAccount {
    val autoplay: Boolean

    suspend fun next(target: PlaybackTarget): PlaybackTarget?

    suspend fun prepare(target: PlaybackTarget): CastPrepared?
}

internal data class CastPrepared(val media: PlaybackCastMedia, val item: MediaItem, val startMillis: Long)

internal sealed interface CastAdvance {
    val from: Long

    data class Next(override val from: Long, val target: PlaybackTarget) : CastAdvance

    data class End(override val from: Long) : CastAdvance
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class CastAutoplay(private val cast: MobileCastController) {
    private var lent: Pair<Any, CastAccount>? = null

    private val autoplayed = mutableSetOf<Long>()

    private val mutableAdvanced = MutableSharedFlow<CastAdvance>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val advanced: SharedFlow<CastAdvance> = mutableAdvanced

    val account: CastAccount? get() = lent?.second

    fun follow(session: Any, account: CastAccount) {
        lent = session to account
    }

    fun forget(session: Any?) {
        if (lent?.first == session) lent = null
    }

    fun restart() {
        autoplayed.clear()
    }

    suspend fun advance(fileId: Long) {
        val lent = account?.takeIf { it.autoplay } ?: return
        val media = cast.sentMedia?.takeIf { it.fileId == fileId && it.mediaType == PlaybackMediaType.VIDEO }
        if (media == null) mutableAdvanced.tryEmit(CastAdvance.End(fileId)) else advance(fileId, media, lent)
    }

    private suspend fun advance(fileId: Long, media: PlaybackCastMedia, lent: CastAccount) {
        autoplayed += fileId
        val loads = cast.loadsSent
        val next = lent.next(PlaybackTarget(FilesItemId(fileId), media.title, PlaybackMediaType.VIDEO))
            ?.takeUnless { it.fileId.value in autoplayed }
        val prepared = next?.let { lent.prepare(it) }
        val overtaken = cast.loadsSent != loads || cast.awaitsReceiver
        if (overtaken || cast.sentMedia?.fileId != fileId || cast.status.value?.fileId != fileId) return
        val sent = next != null && prepared != null &&
            cast.cast(prepared.media, prepared.item, CastHandover(prepared.startMillis, playing = true), chosen = false)
        mutableAdvanced.tryEmit(if (sent && next != null) CastAdvance.Next(fileId, next) else CastAdvance.End(fileId))
    }
}

/**
 * Hands playback between this device and a Cast receiver. Process-wide, as the Cast session is: it
 * keeps the receiver's last word on each file, so the local player resumes where the receiver
 * stopped once the session ends, whichever screen is open then.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileCastController(
    val gateway: CastGateway,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    val availability: StateFlow<CastAvailability> get() = gateway.availability
    val status: StateFlow<CastRemoteStatus?> get() = gateway.status

    /** The receiver as a Media3 player, for the player screen's controls and position reporting. */
    val remotePlayer: CastRemotePlayer = CastRemotePlayer(gateway, scope, clock) { startMillis ->
        // A file that finished or failed has left the receiver; playing it again sends it once more.
        sentMedia?.takeIf { gateway.status.value?.fileId == it.fileId }?.let { media ->
            loadsSent++
            gateway.load(media, startMillis, autoplay = true, speed = gateway.status.value?.speed ?: 1f)
        }
    }

    val autoplay: CastAutoplay = CastAutoplay(this)

    private val handovers = mutableMapOf<Long, CastHandover>()

    var sentMedia: PlaybackCastMedia? = null
        private set

    var loadsSent: Long = 0L
        private set

    /** Set while a session that sign-out ended winds down. */
    private var ending = false

    /** The ended session whose position has been handed back, so it is handed back once. */
    private var handedBack: CastRemoteStatus? = null

    /** The file the receiver was last seen playing; only its finish counts as one. */
    private var playingFileId: Long? = null

    private var sending: Long? = null

    private var transferring: Long? = null

    private var signOuts = 0

    private var handoff: SessionHandoff? = null

    val awaitsReceiver: Boolean get() = sending != null

    private val mutableFinished = MutableSharedFlow<Long>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Each file the receiver played to its end, once per play. A stop, a failure, a sign-out or a
     * session's end is no finish; one no collector hears is dropped, so none arrives late.
     */
    val finished: SharedFlow<Long> = mutableFinished

    init {
        scope.launch {
            gateway.status.collect { status ->
                // The status clears only as the session ends: freeze where the receiver stood, for
                // the file's next local start. A file that finished hands back stopped.
                if (status == null) takeEnded()?.let { (fileId, handover) -> handovers[fileId] = handover }
                if (status?.fileId == sending) sending = null
                noteFinish(status)
            }
        }
        scope.launch {
            // The departing session is gone once no session is connected or connecting.
            gateway.availability.collect {
                if (it !is CastAvailability.Connected) sending = null
                if (it !is CastAvailability.Connected && it !is CastAvailability.Connecting) ending = false
            }
        }
        scope.launch { finished.collect(autoplay::advance) }
    }

    /**
     * Whether the receiver plays [fileId] now, or is about to, so a screen opened on it attaches
     * instead of loading. Media that finished or failed there is sent again.
     */
    fun holds(fileId: Long): Boolean =
        sending == fileId || transferring == fileId || gateway.status.value?.takeIf { it.fileId == fileId }.live

    /**
     * Sends [media] to the receiver from where local playback stood, unless the receiver already
     * plays it; [item] is the local item with its reporting lease, so the receiver's positions
     * reach put.io. Does nothing without a session.
     */
    fun cast(media: PlaybackCastMedia, item: MediaItem, local: CastHandover, chosen: Boolean = true): Boolean {
        if (gateway.availability.value !is CastAvailability.Connected || ending) return false
        remotePlayer.adopt(item)
        val loads = !holds(media.fileId)
        sentMedia = media
        if (loads) {
            if (chosen) autoplay.restart()
            handovers.remove(media.fileId)
            sending = media.fileId
            loadsSent++
            gateway.load(media, local.positionMillis.coerceAtLeast(0L), local.playing, local.speed)
        }
        return loads
    }

    fun castFromSession(item: MediaItem, handover: CastHandover, local: Player, playerScope: CoroutineScope) {
        val fileId = item.mediaId.toLongOrNull() ?: return
        transferring = fileId
        val exits = signOuts
        val from = handoff?.takeIf { it.fileId == fileId }?.handover ?: handover
        val next = SessionHandoff(this, fileId, item, from, local) { signOuts != exits }
        handoff = next
        playerScope.launch {
            val prepared = try {
                next.prepare()
            } finally {
                if (handoff === next && transferring == fileId) transferring = null
            }
            next.settle(prepared) { handoff !== next }
            if (handoff === next) handoff = null
        }
    }

    /**
     * Ties the receiver's controls to [item] without loading it: the receiver already plays it.
     * [media], when known, is what Play sends again once the file has finished there.
     */
    fun attach(item: MediaItem, media: PlaybackCastMedia? = null) {
        if (ending) return
        remotePlayer.adopt(item)
        if (media != null) sentMedia = media
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
        signOuts++
        // Nothing goes to the departing session while it winds down.
        ending = gateway.availability.value.let {
            it is CastAvailability.Connected || it is CastAvailability.Connecting
        }
        sentMedia = null
        playingFileId = null
        sending = null
        handovers.clear()
        autoplay.restart()
        // Nor does one that ended a moment ago and is not handed back yet.
        handedBack = gateway.endedStatus
        gateway.endForSignOut()
    }

    /** Whether [takeHandover] has a place for [fileId]; it takes nothing. */
    fun hasHandover(fileId: Long): Boolean {
        val unclaimed = gateway.endedStatus?.takeIf { it !== handedBack && gateway.status.value == null }
        return gateway.availability.value !is CastAvailability.Connected &&
            (fileId in handovers || unclaimed?.fileId == fileId)
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
        return ended.fileId?.let { it to CastHandover(ended.positionAt(clock()), ended.playing, ended.speed) }
    }

    // Only a file seen playing finishes: a receiver found finished already, or a report on a file
    // still loading, is no new finish.
    private fun noteFinish(status: CastRemoteStatus?) {
        val playing = playingFileId.takeUnless { ending }
        playingFileId = status?.takeIf { it.state in LIVE_STATES }?.fileId.takeUnless { ending }
        val finished = status?.takeIf { it.state == CastRemoteState.Ended }?.fileId
        if (finished != null && finished == playing) mutableFinished.tryEmit(finished)
    }
}

private class SessionHandoff(
    private val cast: MobileCastController,
    val fileId: Long,
    private val item: MediaItem,
    val handover: CastHandover,
    private val local: Player,
    private val signedOut: () -> Boolean,
) {
    private val gateway = cast.gateway
    private val loads = cast.loadsSent

    suspend fun prepare(): CastPrepared? {
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val target = PlaybackTarget(FilesItemId(fileId), title, PlaybackMediaType.AUDIO)
        return withTimeoutOrNull(SESSION_CONNECT_WAIT_MILLIS) {
            gateway.availability.first { it is CastAvailability.Connected }
        }?.let { cast.autoplay.account?.prepare(target) }
    }

    suspend fun settle(prepared: CastPrepared?, superseded: () -> Boolean) {
        val plays = !signedOut() && !overtaken() && receiverPlays(prepared)
        if (superseded()) return
        when {
            plays || signedOut() || overtaken() || receiverBusy() -> local.drop(item)
            gateway.endCasting() && !signedOut() && !superseded() -> playHere()
            signedOut() -> local.drop(item)
        }
    }

    private suspend fun receiverPlays(prepared: CastPrepared?): Boolean {
        if (gateway.availability.value !is CastAvailability.Connected) return false
        val before = gateway.status.value
        if (prepared != null) cast.cast(prepared.media, item, handover)
        val reports = combine(gateway.status, gateway.availability) { status, availability ->
            report(status, availability, before)
        }
        val first = withTimeoutOrNull(RECEIVER_REPORT_WAIT_MILLIS) {
            reports.first { it != ReceiverReport.Silent }
        }
        val last = if (first == ReceiverReport.Loads) {
            withTimeoutOrNull(RECEIVER_LOAD_WAIT_MILLIS) {
                reports.first { it == ReceiverReport.Plays || it == ReceiverReport.Fails }
            }
        } else {
            first
        }
        return last == ReceiverReport.Plays
    }

    private fun report(
        status: CastRemoteStatus?,
        availability: CastAvailability,
        before: CastRemoteStatus?,
    ): ReceiverReport = when {
        !availability.casting -> ReceiverReport.Fails
        availability !is CastAvailability.Connected || status?.fileId != fileId -> ReceiverReport.Silent
        status.state in PLAYED_STATES -> ReceiverReport.Plays
        status.state == CastRemoteState.Failed && status !== before -> ReceiverReport.Fails
        status.state == CastRemoteState.Buffering -> ReceiverReport.Loads
        else -> ReceiverReport.Silent
    }

    private fun playHere() {
        val receiverHeld = gateway.endedStatus?.takeIf { it.fileId == fileId }?.state in PLAYED_STATES
        val handback = cast.takeHandover(fileId)?.takeIf { receiverHeld }
        if (local.currentMediaItem?.let { it.mediaId != item.mediaId } == true) return
        if (handback == null && local.currentMediaItem?.mediaId == item.mediaId) {
            local.resume(handover)
        } else {
            local.playFrom(item, handback ?: handover)
        }
    }

    private fun overtaken(): Boolean = cast.loadsSent != loads && cast.sentMedia?.fileId != fileId

    private fun receiverBusy(): Boolean = gateway.status.value?.takeIf { it.fileId != fileId }.live
}

private enum class ReceiverReport { Silent, Loads, Plays, Fails }

internal fun MobileCastController.resumeHere(local: Player, scope: CoroutineScope) {
    scope.launch {
        val moved = withTimeoutOrNull(MOVE_HERE_WAIT_MILLIS) {
            gateway.availability.first { it !is CastAvailability.Connected }
            gateway.movesHere.first { it }
        }
        val ended = gateway.endedStatus?.takeIf { moved == true && it.audio }
        val fileId = ended?.fileId?.takeIf(::hasHandover) ?: return@launch
        val target = PlaybackTarget(FilesItemId(fileId), ended.title.orEmpty(), PlaybackMediaType.AUDIO)
        val prepared = autoplay.account?.prepare(target) ?: return@launch
        if (local.mediaItemCount > 0) return@launch
        takeHandover(fileId)?.let { local.playFrom(prepared.item, it) }
    }
}

private suspend fun CastGateway.endCasting(): Boolean {
    if (availability.value.casting) stopCasting()
    return withTimeoutOrNull(MOVE_HERE_WAIT_MILLIS) { availability.first { !it.casting } } != null
}

private fun Player.drop(item: MediaItem) {
    if (currentMediaItem?.mediaId != item.mediaId) return
    stop()
    clearMediaItems()
}

private fun Player.resume(handover: CastHandover) {
    playWhenReady = handover.playing
    prepare()
}

private fun Player.playFrom(item: MediaItem, handover: CastHandover) {
    setMediaItem(item, handover.positionMillis)
    setPlaybackSpeed(handover.speed)
    playWhenReady = handover.playing
    prepare()
}

internal fun Player.releaseOnceCastEnds(availability: StateFlow<CastAvailability>, scope: CoroutineScope) {
    if (!availability.value.casting) return release()
    scope.launch {
        availability.first { !it.casting }
        release()
    }
}

internal const val MOVE_HERE_WAIT_MILLIS = 10_000L

private const val STATUS_WAIT_MILLIS = 5_000L

private const val SESSION_CONNECT_WAIT_MILLIS = 10_000L

private const val RECEIVER_REPORT_WAIT_MILLIS = 10_000L

private const val RECEIVER_LOAD_WAIT_MILLIS = 20_000L

private val PLAYED_STATES = setOf(CastRemoteState.Playing, CastRemoteState.Paused)

/** Receiver states with the file still playable there. */
private val LIVE_STATES = setOf(CastRemoteState.Buffering, CastRemoteState.Playing, CastRemoteState.Paused)

private val CastRemoteStatus?.live: Boolean
    get() = this?.state in LIVE_STATES

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
        // Unknown settings show none, as locally: the account may hide subtitles.
        null -> keys.first().takeIf { policy?.autoSelectSubtitles == true }
    }
}

/**
 * The playlist subtitle the receiver shows from HLS, by the same rules as [castSubtitleKey]: the
 * viewer's pick by its language, else the playlist's first when the account selects one. A video is
 * always HLS on the receiver, even when this device plays it from a file with sidecar subtitles.
 */
internal fun castPlaylistSubtitle(
    source: PlaybackSource,
    mediaType: PlaybackMediaType,
    selection: SubtitleSelection?,
    policy: SubtitleStartupPolicy?,
): PlaybackCastPlaylistSubtitle? {
    val onHls = mediaType == PlaybackMediaType.VIDEO || source.subtitles == PlaybackSubtitles.Embedded
    if (!onHls || policy?.showSubtitles == false) return null
    return when (selection) {
        SubtitleSelection.Off -> null
        is SubtitleSelection.Track -> PlaybackCastPlaylistSubtitle(
            selection.identity.language?.takeUnless { it.isBlank() || it == C.LANGUAGE_UNDETERMINED },
        )
        SubtitleSelection.Automatic -> PlaybackCastPlaylistSubtitle(null)
        null -> PlaybackCastPlaylistSubtitle(null).takeIf { policy?.autoSelectSubtitles == true }
    }
}

/** A text track the receiver lists, by its id and language tag. */
internal data class CastTextTrack(val id: Long, val language: String?)

/** The id of the receiver's track for [wanted]: the first in its language, or the first of all; null for none. */
internal fun playlistSubtitleTrack(tracks: List<CastTextTrack>, wanted: PlaybackCastPlaylistSubtitle): Long? {
    val language = wanted.language?.primaryLanguage() ?: return tracks.firstOrNull()?.id
    return tracks.firstOrNull { it.language?.primaryLanguage() == language }?.id
}

/** "en", "eng" and "en-US" all name English: compared as ISO 639-2, else as the tag's first part. */
private fun String.primaryLanguage(): String? {
    val tag = substringBefore('-').substringBefore('_').lowercase().takeUnless { it.isBlank() } ?: return null
    return runCatching { Locale.forLanguageTag(tag).isO3Language }.getOrNull()?.takeUnless { it.isBlank() } ?: tag
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
        get() = state !in LIVE_STATES

    private val MediaItem.reportsPositions: Boolean
        get() = mediaMetadata.extras?.containsKey(PLAYBACK_REPORTING_LEASE_KEY) == true

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
    }
}
