package io.putdotio.android

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.CastFixtures.AudioItem
import io.putdotio.android.CastFixtures.Media
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.CastAccount
import io.putdotio.android.playback.CastAvailability
import io.putdotio.android.playback.CastDevice
import io.putdotio.android.playback.CastGateway
import io.putdotio.android.playback.CastHandover
import io.putdotio.android.playback.CastPrepared
import io.putdotio.android.playback.CastRemoteState
import io.putdotio.android.playback.CastRemoteStatus
import io.putdotio.android.playback.PlaybackCastMedia
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.toCastMedia
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.robolectric.Shadows.shadowOf

internal object CastFixtures {
    val Media = requireNotNull(
        PlaybackSource(
            fileId = 42L,
            kind = PlaybackSourceKind.HLS,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/hls/media.m3u8"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.Embedded,
        ).toCastMedia(PlaybackTarget(FilesItemId(42L), "episode.mkv"), "download-token"),
    )
    val AudioMedia = Media.copy(title = "track.mp3", mediaType = PlaybackMediaType.AUDIO)
    val Item: MediaItem = MediaItem.Builder().setMediaId("42").build()
    val OtherItem: MediaItem = MediaItem.Builder().setMediaId("7").build()
    val AudioItem: MediaItem = MediaItem.Builder()
        .setMediaId("42")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("track.mp3").build())
        .build()
    const val SESSION = "session"
}

internal fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

internal data class Load(val fileId: Long, val startMillis: Long, val autoplay: Boolean, val speed: Float)

/** The Cast SDK's side: a session the test opens and closes, and the receiver's reports. */
internal class FakeCastGateway(private val clock: () -> Long) : CastGateway {
    override val availability = MutableStateFlow<CastAvailability>(CastAvailability.Available)
    override val status = MutableStateFlow<CastRemoteStatus?>(null)
    override var endedStatus: CastRemoteStatus? = null
    override val movesHere = MutableStateFlow(false)
    override val devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val loads = mutableListOf<Load>()
    val commands = mutableListOf<String>()
    var stopsAtOnce = true
    var reportsOnSignOut = true
    fun connect(device: String = "Living room TV") {
        availability.value = CastAvailability.Connected(device)
        idleMain()
    }

    fun disconnect() {
        end()
        idleMain()
    }

    /** As the SDK ends a session: the last status kept, then cleared, all before any collector runs. */
    fun end() {
        endedStatus = status.value?.copy(endedAtMillis = clock())
        availability.value = CastAvailability.Available
        status.value = null
    }

    fun report(
        state: CastRemoteState,
        positionMillis: Long,
        durationMillis: Long? = null,
        speed: Float = 1f,
        speedSupported: Boolean = false,
    ) {
        status.value = remote(state, positionMillis, durationMillis, speed, speedSupported)
        idleMain()
    }

    fun remote(
        state: CastRemoteState,
        positionMillis: Long,
        durationMillis: Long? = null,
        speed: Float = 1f,
        speedSupported: Boolean = false,
    ) =
        CastRemoteStatus(
            fileId = 42L,
            state = state,
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            speed = speed,
            speedSupported = speedSupported,
            sampledAtMillis = clock(),
        )

    override fun startDiscovery() = Unit

    override fun stopDiscovery() = Unit

    override fun select(deviceId: String) = Unit

    override fun stopCasting() {
        commands += "stop"
        if (stopsAtOnce) end()
    }

    override fun endForSignOut() {
        // As the SDK may: the receiver's status lands before the session's end does.
        if (reportsOnSignOut) report(CastRemoteState.Playing, positionMillis = 90_000L)
        availability.value = CastAvailability.Available
        endedStatus = null
        status.value = null
        idleMain()
    }

    var localNetworkAnswers = 0

    override fun localNetworkAnswered() {
        localNetworkAnswers++
    }

    override fun load(media: PlaybackCastMedia, startMillis: Long, autoplay: Boolean, speed: Float) {
        loads += Load(media.fileId, startMillis, autoplay, speed)
    }

    override fun play() {
        commands += "play"
    }

    override fun pause() {
        commands += "pause"
    }

    override fun seekTo(positionMillis: Long) {
        commands += "seek $positionMillis"
    }

    override fun setSpeed(speed: Float) {
        commands += "speed $speed"
    }
}

internal class FakeCastAccount(
    private val next: PlaybackTarget?,
    override val autoplay: Boolean = true,
    private val prepares: Boolean = true,
) : CastAccount {
    override suspend fun next(target: PlaybackTarget): PlaybackTarget? = next

    override suspend fun prepare(target: PlaybackTarget): CastPrepared? =
        CastPrepared(
            media = Media.copy(fileId = target.fileId.value, title = target.name, mediaType = target.mediaType),
            item = MediaItem.Builder().setMediaId(target.fileId.value.toString()).build(),
            startMillis = 30_000L,
        ).takeIf { prepares }
}

internal class SlowCastAccount(next: PlaybackTarget?, prepares: Boolean = true) : CastAccount {
    private val account = FakeCastAccount(next, prepares = prepares)
    val answer = CompletableDeferred<Unit>()
    override val autoplay = true

    override suspend fun next(target: PlaybackTarget): PlaybackTarget? {
        answer.await()
        return account.next(target)
    }

    override suspend fun prepare(target: PlaybackTarget): CastPrepared? {
        answer.await()
        return account.prepare(target)
    }
}

@UnstableApi
internal class LocalPlayer(holding: CastHandover? = null) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var current: State = State.Builder()
        .setAvailableCommands(Media3Player.Commands.Builder().addAllCommands().build())
        .apply {
            if (holding != null) {
                setPlaylist(listOf(MediaItemData.Builder(AudioItem.mediaId).setMediaItem(AudioItem).build()))
                setContentPositionMs(holding.positionMillis)
                setPlaybackParameters(PlaybackParameters(holding.speed))
            }
        }
        .build()

    override fun getState(): State = current

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> = update {
        setPlaylist(mediaItems.map { MediaItemData.Builder(it.mediaId).setMediaItem(it).build() })
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(startPositionMs)
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> =
        update { setPlaybackParameters(playbackParameters) }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        update { setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }

    override fun handlePrepare(): ListenableFuture<*> = update { setPlaybackState(STATE_READY) }

    override fun handleStop(): ListenableFuture<*> = update { setPlaybackState(STATE_IDLE) }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> =
        update { setPlaylist(emptyList()).setPlaybackState(STATE_IDLE) }

    private fun update(change: State.Builder.() -> State.Builder): ListenableFuture<*> {
        current = current.buildUpon().change().build()
        return Futures.immediateVoidFuture()
    }
}
