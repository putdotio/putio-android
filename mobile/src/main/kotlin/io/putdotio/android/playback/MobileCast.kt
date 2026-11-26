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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface CastAvailability {
    data object Unsupported : CastAvailability

    data object NoDevices : CastAvailability

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

internal data class CastRemoteStatus(
    val fileId: Long?,
    val state: CastRemoteState,
    val positionMillis: Long,
    val durationMillis: Long?,
    val speed: Float = 1f,
    val speedSupported: Boolean = false,
    val seekable: Boolean = true,
    val sampledAtMillis: Long,
    val playIntent: Boolean? = null,
    val title: String? = null,
    val audio: Boolean = false,
    val endedAtMillis: Long? = null,
    val audioTracks: List<CastTrack> = emptyList(),
    val textTracks: List<CastTrack> = emptyList(),
    val activeTrackIds: Set<Long> = emptySet(),
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

internal data class CastTrack(val id: Long, val name: String?, val language: String?)

internal enum class CastDeviceKind { Tv, Speaker, Other }

internal data class CastDevice(
    val id: String,
    val name: String,
    val model: String?,
    val audioOnly: Boolean,
    val kind: CastDeviceKind,
    val selected: Boolean,
)

internal data class CastHandover(
    val positionMillis: Long,
    val playing: Boolean,
    val speed: Float = 1f,
)

internal interface CastGateway : CastRoutes, CastRemote {
    val availability: StateFlow<CastAvailability>

    val movesHere: StateFlow<Boolean>

    fun stopCasting()

    fun endForSignOut()

    fun localNetworkAnswered()
}

internal interface CastRoutes {
    val devices: StateFlow<List<CastDevice>>

    fun startDiscovery()

    fun stopDiscovery()

    fun select(deviceId: String)
}

internal interface CastRemote {
    val status: StateFlow<CastRemoteStatus?>

    val endedStatus: CastRemoteStatus?

    fun load(media: PlaybackCastMedia, startMillis: Long, autoplay: Boolean, speed: Float)

    fun play()

    fun pause()

    fun seekTo(positionMillis: Long)

    fun setSpeed(speed: Float)

    fun setActiveTracks(trackIds: Set<Long>)
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

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class MobileCastController(
    val gateway: CastGateway,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    val availability: StateFlow<CastAvailability> get() = gateway.availability
    val status: StateFlow<CastRemoteStatus?> get() = gateway.status

    val remotePlayer: CastRemotePlayer = CastRemotePlayer(gateway, scope, clock) { startMillis ->
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

    private var ending = false

    private var handedBack: CastRemoteStatus? = null

    private var playingFileId: Long? = null

    private var sending: Long? = null

    private var transferring: Long? = null

    private var signOuts = 0

    private var handoff: SessionHandoff? = null

    private var handoffJob: Job? = null

    val awaitsReceiver: Boolean get() = sending != null

    private val mutableFinished = MutableSharedFlow<Long>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val finished: SharedFlow<Long> = mutableFinished

    init {
        scope.launch {
            gateway.status.collect { status ->
                if (status == null) takeEnded()?.let { (fileId, handover) -> handovers[fileId] = handover }
                if (status?.fileId == sending) sending = null
                noteFinish(status)
            }
        }
        scope.launch {
            gateway.availability.collect {
                if (it !is CastAvailability.Connected) sending = null
                if (it !is CastAvailability.Connected && it !is CastAvailability.Connecting) ending = false
            }
        }
        scope.launch { finished.collect(autoplay::advance) }
    }

    fun holds(fileId: Long): Boolean =
        sending == fileId || transferring == fileId || gateway.status.value?.takeIf { it.fileId == fileId }.live

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
        val previous = handoffJob
        handoff = next
        previous?.cancel()
        handoffJob = playerScope.launch {
            val prepared = try {
                next.prepare()
            } finally {
                if (handoff === next && transferring == fileId) transferring = null
            }
            next.settle(prepared)
        }.apply {
            invokeOnCompletion {
                if (handoff !== next) return@invokeOnCompletion
                if (transferring == fileId) transferring = null
                handoff = null
            }
        }
    }

    fun attach(item: MediaItem, media: PlaybackCastMedia? = null) {
        if (ending) return
        remotePlayer.adopt(item)
        if (media != null) sentMedia = media
    }

    fun castingTarget(): PlaybackTarget? {
        val status = gateway.status.value?.takeIf { gateway.availability.value is CastAvailability.Connected }
        val fileId = status?.fileId ?: return null
        return PlaybackTarget(
            FilesItemId(fileId),
            status.title.orEmpty(),
            if (status.audio) PlaybackMediaType.AUDIO else PlaybackMediaType.VIDEO,
        )
    }

    fun end() {
        signOuts++
        ending = gateway.availability.value.let {
            it is CastAvailability.Connected || it is CastAvailability.Connecting
        }
        sentMedia = null
        playingFileId = null
        sending = null
        handovers.clear()
        autoplay.restart()
        handedBack = gateway.endedStatus
        gateway.endForSignOut()
    }

    fun hasHandover(fileId: Long): Boolean {
        val unclaimed = gateway.endedStatus?.takeIf { it !== handedBack && gateway.status.value == null }
        return gateway.availability.value !is CastAvailability.Connected &&
            (fileId in handovers || unclaimed?.fileId == fileId)
    }

    fun takeHandover(fileId: Long): CastHandover? {
        if (gateway.availability.value is CastAvailability.Connected) return null
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
    private var receiver: String? = null

    suspend fun prepare(): CastPrepared? {
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val target = PlaybackTarget(FilesItemId(fileId), title, PlaybackMediaType.AUDIO)
        receiver = withTimeoutOrNull(SESSION_CONNECT_WAIT_MILLIS) {
            gateway.availability.first { it is CastAvailability.Connected }
        }?.connectedDevice
        return if (receiver != null) cast.autoplay.account?.prepare(target) else null
    }

    suspend fun settle(prepared: CastPrepared?) {
        currentCoroutineContext().ensureActive()
        val plays = !signedOut() && !overtaken() && receiverPlays(prepared)
        when {
            plays || signedOut() || overtaken() || receiverBusy() -> local.drop(item)
            gateway.endCasting() && !signedOut() -> playHere()
            signedOut() -> local.drop(item)
        }
    }

    private suspend fun receiverPlays(prepared: CastPrepared?): Boolean {
        val session = receiver?.let { device ->
            withTimeoutOrNull(SESSION_CONNECT_WAIT_MILLIS) {
                gateway.availability.first {
                    it is CastAvailability.Connected || !it.casting ||
                        (it as? CastAvailability.Connecting)?.deviceName?.let { name -> name != device } == true
                }
            }
        } ?: gateway.availability.value
        val sameReceiver = session is CastAvailability.Connected && receiver?.let { it == session.deviceName } != false
        if (!sameReceiver || signedOut() || overtaken()) return false
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

internal val PLAYED_STATES = setOf(CastRemoteState.Playing, CastRemoteState.Paused)

private val LIVE_STATES = setOf(CastRemoteState.Buffering, CastRemoteState.Playing, CastRemoteState.Paused)

private val CastRemoteStatus?.live: Boolean
    get() = this?.state in LIVE_STATES

internal fun castSubtitleKey(
    source: PlaybackSource,
    selection: SubtitleSelection?,
    policy: SubtitleStartupPolicy?,
): String? {
    val sidecar = source.subtitles as? PlaybackSubtitles.Sidecar
    val keys = sidecar?.tracks.orEmpty().map { it.key }.filter(String::isNotBlank)
    if (keys.isEmpty() || policy?.showSubtitles == false) return null
    val defaultKey = sidecar?.defaultKey?.takeIf { it in keys } ?: keys.first()
    return when (selection) {
        SubtitleSelection.Off -> null
        is SubtitleSelection.Track -> selection.identity.id?.let { id ->
            keys.firstOrNull { key -> id == key || id.endsWith(":$key") }
        }
        SubtitleSelection.Automatic -> defaultKey
        null -> defaultKey.takeIf { policy?.autoSelectSubtitles == true }
    }
}

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

internal data class CastTextTrack(val id: Long, val language: String?)

internal fun playlistSubtitleTrack(tracks: List<CastTextTrack>, wanted: PlaybackCastPlaylistSubtitle): Long? {
    val language = wanted.language?.primaryLanguage() ?: return tracks.firstOrNull()?.id
    return tracks.firstOrNull { it.language?.primaryLanguage() == language }?.id
}

private fun String.primaryLanguage(): String? {
    val tag = substringBefore('-').substringBefore('_').lowercase().takeUnless { it.isBlank() } ?: return null
    return runCatching { Locale.forLanguageTag(tag).isO3Language }.getOrNull()?.takeUnless { it.isBlank() } ?: tag
}

@UnstableApi
internal class CastRemotePlayer(
    private val gateway: CastGateway,
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val replay: (Long) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var item: MediaItem? = null
    private var status: CastRemoteStatus? = gateway.status.value
    private val adoptedStatus = MutableStateFlow<CastRemoteStatus?>(null)
    private val pending = mutableListOf<SettableFuture<Any?>>()
    private val handler = Handler(Looper.getMainLooper())

    private var replaying = false

    init {
        scope.launch {
            gateway.status.collect {
                status = it
                adoptedStatus.value = it.adoptedBy(item)
                replaying = false
                settle()
                invalidateState()
            }
        }
    }

    val receiverStatus: StateFlow<CastRemoteStatus?> get() = adoptedStatus

    fun setActiveTracks(trackIds: Set<Long>) {
        if (adoptedStatus.value != null) gateway.setActiveTracks(trackIds)
    }

    fun adopt(item: MediaItem) {
        val current = this.item
        if (current?.mediaId == item.mediaId && current.reportsPositions && !item.reportsPositions) return
        this.item = item
        adoptedStatus.value = status.adoptedBy(item)
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
