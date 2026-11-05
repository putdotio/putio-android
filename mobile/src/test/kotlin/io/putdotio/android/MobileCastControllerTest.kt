package io.putdotio.android

import android.os.Looper
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.CastFixtures.Item
import io.putdotio.android.CastFixtures.Media
import io.putdotio.android.CastFixtures.SESSION
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.CastAdvance
import io.putdotio.android.playback.CastHandover
import io.putdotio.android.playback.CastRemoteState
import io.putdotio.android.playback.CastTextTrack
import io.putdotio.android.playback.MobileCastController
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.PlaybackCastMedia
import io.putdotio.android.playback.PlaybackCastPlaylistSubtitle
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.SignedInCastAccount
import io.putdotio.android.playback.SubtitleSelection
import io.putdotio.android.playback.SubtitleStartupPolicy
import io.putdotio.android.playback.SubtitleTrackIdentity
import io.putdotio.android.playback.castPlaylistSubtitle
import io.putdotio.android.playback.castSubtitleKey
import io.putdotio.android.playback.playlistSubtitleTrack
import io.putdotio.android.playback.toCastMedia
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.DefaultAccountSettingsPreferences
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
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
import kotlinx.coroutines.launch
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
    private val gateway = FakeCastGateway { now }
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

        // Once it finishes there, Play sends the held file again.
        gateway.report(CastRemoteState.Ended, positionMillis = 600_000L)
        cast.remotePlayer.seekTo(0L)
        assertEquals(listOf(Load(42L, 0L, autoplay = true, speed = 1f)), gateway.loads)
    }

    @Test
    fun withoutASessionNothingGoesToAReceiver() {
        assertFalse(cast.cast(Media, Item, CastHandover(positionMillis = 1_000L, playing = true)))
        assertTrue(gateway.loads.isEmpty())
    }

    @Test
    fun aFileSentAndNotYetReportedOnIsNotSentAgain() {
        gateway.connect()

        assertTrue(cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true)))
        assertFalse(cast.cast(Media, Item, CastHandover(positionMillis = 4_000L, playing = true)))

        assertEquals(listOf(Load(42L, 0L, autoplay = true, speed = 1f)), gateway.loads)
        assertTrue(cast.holds(42L))
    }

    @Test
    fun aVideoThatFinishesOnTheReceiverGoesOnToTheNextWithNoScreenOpen() {
        val advanced = collectAdvanced()
        cast.autoplay.follow(SESSION, FakeCastAccount(next = NextTarget))
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))

        gateway.report(CastRemoteState.Playing, positionMillis = 1_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 2_000L)

        assertEquals(
            listOf(Load(42L, 0L, autoplay = true, speed = 1f), Load(43L, 30_000L, autoplay = true, speed = 1f)),
            gateway.loads,
        )
        assertEquals(listOf(CastAdvance.Next(42L, NextTarget)), advanced)
    }

    @Test
    fun withAutoplayOffTheReceiverStaysOnTheFinishedVideo() {
        val advanced = collectAdvanced()
        cast.autoplay.follow(SESSION, FakeCastAccount(next = NextTarget, autoplay = false))
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))

        gateway.report(CastRemoteState.Playing, positionMillis = 1_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 2_000L)

        assertEquals(1, gateway.loads.size)
        assertTrue(advanced.isEmpty())
    }

    @Test
    fun aFinishedVideoPlayedAgainBeforeTheNextIsFoundKeepsPlaying() {
        val advanced = collectAdvanced()
        val account = SlowCastAccount(next = NextTarget)
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 1_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 2_000L)

        cast.remotePlayer.seekTo(0L)
        account.answer.complete(Unit)
        idle()

        assertEquals(
            listOf(Load(42L, 0L, autoplay = true, speed = 1f), Load(42L, 0L, autoplay = true, speed = 1f)),
            gateway.loads,
        )
        assertTrue(advanced.isEmpty())
    }

    @Test
    fun withAutoplayOnACastVideoGoesOnToTheNextWhicheverWayTheNextEpisodeButtonIsSet() {
        val account = SignedInCastAccount(
            Folder,
            MobilePlayerFactory { _, _ -> error("Casting plays nothing on the phone") },
            MutableStateFlow(readyAndroidAppConfigState(AndroidAppConfigPreferences(autoplayNextVideo = true))),
            MutableStateFlow(readyAccountSettingsState(DefaultAccountSettingsPreferences.copy(nextEpisode = false))),
            appName = "put.io",
        )
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))

        gateway.report(CastRemoteState.Playing, positionMillis = 1_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 2_000L)

        assertEquals(listOf(42L, 43L), gateway.loads.map { it.fileId })
    }

    @Test
    fun aLastVideoEndsForTheScreenOpenOnIt() {
        val advanced = collectAdvanced()
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null))
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))

        gateway.report(CastRemoteState.Playing, positionMillis = 1_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 2_000L)

        assertEquals(1, gateway.loads.size)
        assertEquals(listOf(CastAdvance.End(42L)), advanced)
    }

    @Test
    fun aVideoAttachedWithoutItsMediaStillEndsForTheScreenOpenOnIt() {
        val advanced = collectAdvanced()
        cast.autoplay.follow(SESSION, FakeCastAccount(next = NextTarget))
        gateway.connect()
        gateway.report(CastRemoteState.Playing, positionMillis = 1_000L)
        cast.attach(Item)

        gateway.report(CastRemoteState.Ended, positionMillis = 2_000L)

        assertTrue(gateway.loads.isEmpty())
        assertEquals(listOf(CastAdvance.End(42L)), advanced)
    }

    @Test
    fun aSignedOutSessionLendsItsAccountNoMore() {
        cast.autoplay.follow(SESSION, FakeCastAccount(next = NextTarget))
        cast.autoplay.forget("another session")
        cast.autoplay.forget(SESSION)
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))

        gateway.report(CastRemoteState.Playing, positionMillis = 1_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 2_000L)

        assertEquals(1, gateway.loads.size)
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
    fun aFileThatPlaysToItsEndOnTheReceiverFinishesOncePerPlay() {
        val finished = collectFinished()
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Buffering, positionMillis = 0L)
        gateway.report(CastRemoteState.Playing, positionMillis = 590_000L, durationMillis = 600_000L)

        // The receiver keeps reporting its finished file until something else loads.
        gateway.report(CastRemoteState.Ended, positionMillis = 600_000L, durationMillis = 600_000L)
        now += 1_000L
        gateway.report(CastRemoteState.Ended, positionMillis = 600_000L, durationMillis = 600_000L)
        assertEquals(listOf(42L), finished)

        // Played again, it finishes again.
        cast.remotePlayer.seekTo(0L)
        gateway.report(CastRemoteState.Playing, positionMillis = 0L, durationMillis = 600_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 600_000L, durationMillis = 600_000L)
        assertEquals(listOf(42L, 42L), finished)
    }

    @Test
    fun stoppingFailingOrLeavingTheReceiverIsNoFinish() {
        val finished = collectFinished()
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))

        // Stopped from the TV's remote: the receiver idles without finishing.
        gateway.report(CastRemoteState.Playing, positionMillis = 60_000L)
        gateway.report(CastRemoteState.Idle, positionMillis = 60_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 600_000L)
        // A failure is no finish either.
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 60_000L)
        gateway.report(CastRemoteState.Failed, positionMillis = 60_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 600_000L)
        // A seek toward the end, taken back before the file got there.
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 60_000L, durationMillis = 600_000L)
        cast.remotePlayer.seekTo(599_000L)
        gateway.report(CastRemoteState.Playing, positionMillis = 599_000L, durationMillis = 600_000L)
        cast.remotePlayer.seekTo(60_000L)
        gateway.report(CastRemoteState.Playing, positionMillis = 60_000L, durationMillis = 600_000L)
        // Stopped from the phone, which ends the session and hands the file back.
        gateway.stopCasting()

        assertTrue(finished.isEmpty())
        assertEquals(CastHandover(positionMillis = 60_000L, playing = true), cast.takeHandover(42L))
    }

    @Test
    fun aSessionThatEndsOrSignsOutMidPlayIsNoFinish() {
        val finished = collectFinished()
        gateway.connect()
        gateway.report(CastRemoteState.Playing, positionMillis = 599_000L)
        // The receiver ends the session; the next one finds the file finished without having seen it play.
        gateway.disconnect()
        gateway.connect()
        gateway.report(CastRemoteState.Ended, positionMillis = 600_000L)

        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.report(CastRemoteState.Playing, positionMillis = 599_000L)
        cast.end()
        gateway.report(CastRemoteState.Ended, positionMillis = 600_000L)

        assertTrue(finished.isEmpty())
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
        // Settings still loading: none unless the viewer picked one, as the local player.
        assertNull(castSubtitleKey(sidecar, null, null))
        assertEquals("default", castSubtitleKey(sidecar, SubtitleSelection.Automatic, null))
    }

    @Test
    fun anHlsStreamShowsThePlaylistSubtitleTheLocalPlayerWould() {
        val hls = PlaybackSource(
            fileId = 42L,
            kind = PlaybackSourceKind.HLS,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/hls/media.m3u8"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.Embedded,
        )

        assertEquals(PlaybackCastPlaylistSubtitle(null), castPlaylistSubtitle(hls, null, Shown))
        assertNull(castPlaylistSubtitle(hls, null, Shown.copy(autoSelectSubtitles = false)))
        assertNull(castPlaylistSubtitle(hls, SubtitleSelection.Automatic, Shown.copy(showSubtitles = false)))
        assertNull(castPlaylistSubtitle(hls, SubtitleSelection.Off, Shown))
        val english = SubtitleSelection.Track(identity("2:subs"))
        assertEquals(PlaybackCastPlaylistSubtitle("eng"), castPlaylistSubtitle(hls, english, Shown))
        // Sidecar subtitles go with the load itself.
        assertNull(castPlaylistSubtitle(source(listOf("default")), SubtitleSelection.Automatic, Shown))
    }

    @Test
    fun thePlaylistSubtitleIsTheReceiversTrackInTheChosenLanguage() {
        val tracks = listOf(CastTextTrack(1L, "de"), CastTextTrack(2L, "en-US"), CastTextTrack(3L, "en"))

        assertEquals(2L, playlistSubtitleTrack(tracks, PlaybackCastPlaylistSubtitle("eng")))
        assertEquals(1L, playlistSubtitleTrack(tracks, PlaybackCastPlaylistSubtitle(null)))
        assertNull(playlistSubtitleTrack(tracks, PlaybackCastPlaylistSubtitle("fra")))
        assertNull(playlistSubtitleTrack(emptyList(), PlaybackCastPlaylistSubtitle(null)))
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

    private object Folder : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(
                PlaybackResolution.Ready(
                    PlaybackSource(
                        fileId = target.fileId.value,
                        kind = PlaybackSourceKind.MP4,
                        url = PutioCredentialUrl.of("https://api.put.io/v2/files/${target.fileId.value}/mp4/download"),
                        startFromSeconds = 0.0,
                        subtitles = PlaybackSubtitles.None,
                    ),
                ),
            )

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
            if (target.fileId.value == 42L) PlaybackNextResult.Found(NextTarget) else PlaybackNextResult.Ended

        override suspend fun castMedia(
            target: PlaybackTarget,
            source: PlaybackSource,
            activeSubtitleKey: String?,
        ): PlaybackRepositoryResult<PlaybackCastMedia> =
            PlaybackRepositoryResult.Success(requireNotNull(source.toCastMedia(target, "download-token")))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun collectAdvanced(): List<CastAdvance> {
        val advanced = mutableListOf<CastAdvance>()
        scope.launch { cast.autoplay.advanced.collect { advanced += it } }
        idle()
        return advanced
    }

    private fun collectFinished(): List<Long> {
        val finished = mutableListOf<Long>()
        scope.launch { cast.finished.collect { finished += it } }
        idle()
        return finished
    }

    private companion object {
        val Shown = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true)
        val NextTarget = PlaybackTarget(FilesItemId(43L), "episode 2.mkv")
    }
}
