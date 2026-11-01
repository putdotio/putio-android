package io.putdotio.android.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player as Media3Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.AnalyticsEvent.Playback
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class AudioSessionAnalyticsTest {
    private val events = mutableListOf<AnalyticsEvent>()
    private val analytics = AudioSessionAnalytics(events::add) { 0L }

    @Test
    fun aScreenOnTheRunningFileRepeatsNothingAndTheNextOpeningAfterItsEndStartsAnew() =
        runTest(UnconfinedTestDispatcher()) {
            val player = SessionPlayer()
            analytics.observe(player)
            player.setMediaItem(item(PlaybackSourceKind.ORIGINAL).withReportingLease("lease"))
            val reopened = analytics.follow(controller(), backgroundScope)
            reopened.localShown()
            reopened.reachedEnd()
            player.end()
            player.seekTo(0L)
            player.end()
            player.setMediaItem(item(PlaybackSourceKind.ORIGINAL))

            assertEquals(
                listOf(
                    AnalyticsEvent.PlaybackStarted(Original),
                    AnalyticsEvent.PlaybackEnded(Original, AnalyticsEvent.PlaybackDuration.Under5m),
                    AnalyticsEvent.PlaybackStarted(Original),
                ),
                events,
            )
        }

    @Test
    fun aReceiverTakesTheFileOverOnceAndTheSessionPlayerStartsItAgainAfterwards() =
        runTest(UnconfinedTestDispatcher()) {
            val player = SessionPlayer()
            val observer = analytics.observe(player)
            player.setMediaItem(item(PlaybackSourceKind.MP4, offline = true))
            val screen = analytics.follow(controller(ready = true), backgroundScope)
            player.clearMediaItems()
            screen.castStarted()
            observer.close()
            screen.castStarted()
            screen.reachedEnd()
            val next = SessionPlayer()
            analytics.observe(next)
            next.setMediaItem(item(PlaybackSourceKind.MP4, offline = true))

            val download = Playback(AnalyticsEvent.Media.Audio, AnalyticsEvent.StreamMethod.LocalFile, Local)
            val cast = Playback(AnalyticsEvent.Media.Audio, AnalyticsEvent.StreamMethod.Mp4, Cast)
            assertEquals(
                listOf(
                    AnalyticsEvent.PlaybackStarted(download),
                    AnalyticsEvent.PlaybackStarted(cast),
                    AnalyticsEvent.PlaybackEnded(cast, AnalyticsEvent.PlaybackDuration.Under5m),
                    AnalyticsEvent.PlaybackStarted(download),
                ),
                events,
            )
        }

    @Test
    fun eachFailureReportsOnceFromWhicheverSideSawIt() = runTest(UnconfinedTestDispatcher()) {
        val player = SessionPlayer()
        analytics.observe(player)
        val controller = controller(failFirst = true)
        analytics.follow(controller, backgroundScope)
        controller.dispatch(PlaybackEvent.Retry)
        player.setMediaItem(item(PlaybackSourceKind.ORIGINAL))
        player.fail()
        val relayed = PlaybackFailure.Putio(PutioFailure.Unexpected(IOException()))
        controller.dispatch(PlaybackEvent.PlayerFailed(relayed, 0L))

        val unresolved = Playback(AnalyticsEvent.Media.Audio, null, Local)
        assertEquals(
            listOf(
                AnalyticsEvent.PlaybackFailed(unresolved, AnalyticsEvent.PlaybackError.Network),
                AnalyticsEvent.PlaybackStarted(Original),
                AnalyticsEvent.PlaybackFailed(Original, AnalyticsEvent.PlaybackError.Unknown),
            ),
            events,
        )
    }

    private fun TestScope.controller(ready: Boolean = false, failFirst: Boolean = false): PlaybackController {
        var failures = if (failFirst) 1 else 0
        val repository = object : PlaybackRepository {
            override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
                if (failures-- > 0) {
                    PlaybackRepositoryResult.Failure(
                        PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("offline"))),
                    )
                } else {
                    PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source(PlaybackSourceKind.MP4)))
                }

            override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
        }
        val startup = if (ready || failFirst) PlaybackStartup.Resolve else PlaybackStartup.AttachAudioSession
        return PlaybackController(Target, repository, backgroundScope, startup)
    }

    private companion object {
        val Target = PlaybackTarget(FilesItemId(42L), "song.flac", PlaybackMediaType.AUDIO)
        val Local = AnalyticsEvent.Player.ExoPlayer
        val Cast = AnalyticsEvent.Player.Chromecast
        val Original = Playback(AnalyticsEvent.Media.Audio, AnalyticsEvent.StreamMethod.Progressive, Local)

        fun source(kind: PlaybackSourceKind) =
            PlaybackSource(
                fileId = 42L,
                kind = kind,
                url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/stream?token=secret"),
                startFromSeconds = 0.0,
                subtitles = PlaybackSubtitles.None,
            )

        fun item(kind: PlaybackSourceKind, offline: Boolean = false): MediaItem =
            source(kind).toMediaItem("song.flac", PlaybackMediaType.AUDIO, offline)
    }
}

@UnstableApi
private class SessionPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state = State.Builder()
        .setAvailableCommands(Media3Player.Commands.Builder().addAllCommands().build())
        .build()

    override fun getState(): State = state

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        state = state.buildUpon()
            .setPlaylist(mediaItems.map { MediaItemData.Builder(Any()).setMediaItem(it).build() })
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(0L)
            .setPlaybackState(Media3Player.STATE_READY)
            .setPlayerError(null)
            .build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        state = state.buildUpon().setContentPositionMs(positionMs).setPlaybackState(Media3Player.STATE_READY).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        state = state.buildUpon()
            .setPlaybackState(Media3Player.STATE_IDLE)
            .setPlaylist(emptyList())
            .setCurrentMediaItemIndex(C.INDEX_UNSET)
            .setContentPositionMs(0L)
            .build()
        return Futures.immediateVoidFuture()
    }

    fun end() {
        state = state.buildUpon().setPlaybackState(Media3Player.STATE_ENDED).build()
        invalidateState()
    }

    fun fail() {
        state = state.buildUpon()
            .setPlayerError(PlaybackException("Playback failed", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
            .setPlaybackState(Media3Player.STATE_IDLE)
            .build()
        invalidateState()
    }
}
