package io.putdotio.android

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.CastAvailability
import io.putdotio.android.playback.CastDevice
import io.putdotio.android.playback.CastGateway
import io.putdotio.android.playback.CastHandover
import io.putdotio.android.playback.CastRemoteState
import io.putdotio.android.playback.CastRemoteStatus
import io.putdotio.android.playback.MobileCastController
import io.putdotio.android.playback.PlaybackCastMedia
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SubtitleSelection
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.SubtitleTrackIdentity
import io.putdotio.android.playback.castSubtitleKey
import io.putdotio.android.playback.toCastMedia
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
class MobileCastControllerTest {
    // MainScope's dispatcher, as the app's controller runs on: collectors run on the next looper turn.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var now = 10_000L
    private val gateway = FakeCastGateway()
    private val cast = MobileCastController(gateway, scope) { now }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun castingHandsTheReceiverWhereLocalPlaybackStood() {
        gateway.connect()

        assertTrue(cast.cast(Media, Item, CastHandover(positionMillis = 754_000L, playing = true, speed = 1.5f)))

        assertEquals(listOf(Load(42L, 754_000L, autoplay = true, speed = 1.5f)), gateway.loads)
    }

    @Test
    fun aPausedVideoStaysPausedOnTheReceiver() {
        gateway.connect()

        cast.cast(Media, Item, CastHandover(positionMillis = 3_000L, playing = false))

        assertEquals(listOf(Load(42L, 3_000L, autoplay = false, speed = 1f)), gateway.loads)
    }

    @Test
    fun aReceiverAlreadyPlayingTheFileIsAttachedNotReloaded() {
        gateway.connect()
        gateway.report(CastRemoteState.Playing, positionMillis = 600_000L)

        assertFalse(cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true)))

        assertTrue(gateway.loads.isEmpty())
        assertEquals(Media3Player.STATE_READY, cast.remotePlayer.playbackState)
    }

    @Test
    fun withoutASessionNothingGoesToAReceiver() {
        assertFalse(cast.cast(Media, Item, CastHandover(positionMillis = 1_000L, playing = true)))
        assertTrue(gateway.loads.isEmpty())
    }

    @Test
    fun endingTheSessionResumesLocallyWhereThePlayingReceiverGotTo() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 60_000L)
        // No new status for 2.5 s of playback, then the session ends.
        now += 2_500L
        assertNull(cast.takeHandover(42L))

        gateway.disconnect()

        assertEquals(CastHandover(positionMillis = 62_500L, playing = true), cast.takeHandover(42L))
        // Taken once: a later opening resumes from put.io's position instead.
        assertNull(cast.takeHandover(42L))
    }

    @Test
    fun aScreenThatSeesTheSessionGoneFirstStillGetsTheHandoverOnce() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 60_000L)

        // The session ends; the player recomposes before the status collector runs.
        gateway.end()

        assertEquals(CastHandover(positionMillis = 60_000L, playing = true), cast.takeHandover(42L))
        idle()
        assertNull(cast.takeHandover(42L))
    }

    @Test
    fun theHandbackUsesTheReceiversLastWordEvenWhenNoCollectorSawIt() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 60_000L)
        // The viewer paused on the TV; that status and the session's end arrive in one looper turn.
        now += 10_000L
        gateway.status.value = gateway.remote(CastRemoteState.Paused, positionMillis = 70_000L)
        gateway.disconnect()

        assertEquals(CastHandover(positionMillis = 70_000L, playing = false), cast.takeHandover(42L))
    }

    @Test
    fun aPausedFileStillLoadingHandsBackPaused() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 5_000L, playing = false))
        // Loading reads as buffering; only the play intent says it waits paused.
        gateway.status.value =
            gateway.remote(CastRemoteState.Buffering, positionMillis = 5_000L).copy(playIntent = false)
        gateway.disconnect()

        assertEquals(CastHandover(positionMillis = 5_000L, playing = false), cast.takeHandover(42L))
    }

    @Test
    fun theHandbackStopsWhereTheSessionEndedHoweverLateItIsTaken() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 60_000L)
        gateway.end()
        // The screen asks half a minute later; the receiver stopped at the end.
        now += 30_000L

        assertEquals(CastHandover(positionMillis = 60_000L, playing = true), cast.takeHandover(42L))
    }

    @Test
    fun signingOutRightAfterASessionEndedHandsNothingBack() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 60_000L)
        gateway.end()

        cast.end()
        idle()

        assertNull(cast.takeHandover(42L))
    }

    @Test
    fun signingOutHandsNothingBackToTheNextAccount() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 60_000L)

        cast.end()
        idle()

        assertNull(cast.takeHandover(42L))
    }

    @Test
    fun aFileThatFinishedOnTheReceiverIsSentAgainWhenReopened() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Ended, positionMillis = 600_000L)

        assertTrue(cast.cast(Media, Item, CastHandover(positionMillis = 120_000L, playing = true)))
        assertEquals(Load(42L, 120_000L, autoplay = true, speed = 1f), gateway.loads.last())
    }

    @Test
    fun aFileThatFailedOnTheReceiverIsSentAgain() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Failed, positionMillis = 0L)

        assertTrue(cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true)))
        assertEquals(2, gateway.loads.size)
    }

    @Test
    fun aPausedReceiverHandsBackItsPositionPaused() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Paused, positionMillis = 125_000L, speed = 2f)
        now += 60_000L

        gateway.disconnect()

        assertEquals(CastHandover(positionMillis = 125_000L, playing = false, speed = 2f), cast.takeHandover(42L))
    }

    @Test
    fun theRemotePlayerShowsAndDrivesTheReceiver() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 30_000L, durationMillis = 600_000L)
        val player = cast.remotePlayer

        assertTrue(player.isPlaying)
        assertEquals("42", player.currentMediaItem?.mediaId)
        assertEquals(600_000L, player.duration)
        assertEquals(30_000L, player.currentPosition)
        // The Default Media Receiver says whether it takes a playback rate.
        assertFalse(player.isCommandAvailable(Media3Player.COMMAND_SET_SPEED_AND_PITCH))

        player.pause()
        player.seekTo(90_000L)
        gateway.report(CastRemoteState.Paused, positionMillis = 90_000L, speedSupported = true)
        player.setPlaybackSpeed(1.25f)
        player.play()

        assertEquals(listOf("pause", "seek 90000", "speed 1.25", "play"), gateway.commands)
    }

    @Test
    fun aFinishedFilePlaysAgainBySendingItOnceMore() {
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Ended, positionMillis = 600_000L)

        cast.remotePlayer.seekTo(0L)
        cast.remotePlayer.play()

        // Play's own seek to the start sends it; play does not send it twice.
        assertEquals(2, gateway.loads.size)
        assertEquals(Load(42L, 0L, autoplay = true, speed = 1f), gateway.loads.last())
    }

    @Test
    fun theReceiverShowsTheSubtitleTheLocalPlayerWould() {
        val sidecar = source(listOf("default", "other"))

        assertEquals("default", castSubtitleKey(sidecar, null, Shown))
        assertNull(castSubtitleKey(sidecar, null, Shown.copy(autoSelectSubtitles = false)))
        assertNull(castSubtitleKey(sidecar, SubtitleSelection.Automatic, Shown.copy(showSubtitles = false)))
        assertNull(castSubtitleKey(sidecar, SubtitleSelection.Off, Shown))
        assertEquals("other", castSubtitleKey(sidecar, SubtitleSelection.Track(identity("0:other")), Shown))
        assertNull(castSubtitleKey(source(emptyList()), SubtitleSelection.Automatic, Shown))
    }

    private fun source(keys: List<String>) =
        PlaybackSource(
            fileId = 42L,
            kind = PlaybackSourceKind.MP4,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/mp4/download"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.Sidecar(
                keys.map {
                    PlaybackSubtitle(
                        format = "srt",
                        key = it,
                        language = "English",
                        languageCode = "eng",
                        name = it,
                        source = "folder",
                        url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/subtitles/$it"),
                    )
                },
            ),
        )

    private fun identity(id: String) =
        SubtitleTrackIdentity(
            id = id,
            language = "eng",
            label = null,
            sampleMimeType = null,
            roleFlags = 0,
            selectionFlags = 0,
            accessibilityChannel = 0,
        )

    private data class Load(val fileId: Long, val startMillis: Long, val autoplay: Boolean, val speed: Float)

    /** The Cast SDK's side: a session the test opens and closes, and the receiver's reports. */
    private inner class FakeCastGateway : CastGateway {
        override val availability = MutableStateFlow<CastAvailability>(CastAvailability.Available)
        override val status = MutableStateFlow<CastRemoteStatus?>(null)
        override var endedStatus: CastRemoteStatus? = null
        override val devices = MutableStateFlow<List<CastDevice>>(emptyList())
        val loads = mutableListOf<Load>()
        val commands = mutableListOf<String>()

        fun connect() {
            availability.value = CastAvailability.Connected("Living room TV")
            idle()
        }

        fun disconnect() {
            end()
            idle()
        }

        /** As the SDK ends a session: the last status kept, then cleared, all before any collector runs. */
        fun end() {
            endedStatus = status.value?.copy(endedAtMillis = now)
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
            idle()
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
                sampledAtMillis = now,
            )

        override fun startDiscovery() = Unit

        override fun stopDiscovery() = Unit

        override fun select(deviceId: String) = Unit

        override fun stopCasting() = disconnect()

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

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private companion object {
        val Shown = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true)
        val Media = requireNotNull(
            PlaybackSource(
            fileId = 42L,
            kind = PlaybackSourceKind.HLS,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/hls/media.m3u8"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.Embedded,
            ).toCastMedia(PlaybackTarget(FilesItemId(42L), "episode.mkv"), "download-token"),
        )
        val Item: MediaItem = MediaItem.Builder().setMediaId("42").build()
    }
}
