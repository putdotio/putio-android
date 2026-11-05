package io.putdotio.android

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.CastAccount
import io.putdotio.android.playback.CastAdvance
import io.putdotio.android.playback.CastAvailability
import io.putdotio.android.playback.CastDevice
import io.putdotio.android.playback.CastGateway
import io.putdotio.android.playback.CastHandover
import io.putdotio.android.playback.CastPrepared
import io.putdotio.android.playback.CastRemoteState
import io.putdotio.android.playback.CastRemoteStatus
import io.putdotio.android.playback.CastTextTrack
import io.putdotio.android.playback.MobileCastController
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.PlaybackCastMedia
import io.putdotio.android.playback.PlaybackCastPlaylistSubtitle
import io.putdotio.android.playback.PlaybackMediaType
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
import io.putdotio.android.playback.resumeHere
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
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
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
        cast.autoplay.follow(SESSION, Account(next = NextTarget))
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
        cast.autoplay.follow(SESSION, Account(next = NextTarget, autoplay = false))
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
        val account = SlowAccount(next = NextTarget)
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
        cast.autoplay.follow(SESSION, Account(next = null))
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
        cast.autoplay.follow(SESSION, Account(next = NextTarget))
        gateway.connect()
        gateway.report(CastRemoteState.Playing, positionMillis = 1_000L)
        cast.attach(Item)

        gateway.report(CastRemoteState.Ended, positionMillis = 2_000L)

        assertTrue(gateway.loads.isEmpty())
        assertEquals(listOf(CastAdvance.End(42L)), advanced)
    }

    @Test
    fun aSignedOutSessionLendsItsAccountNoMore() {
        cast.autoplay.follow(SESSION, Account(next = NextTarget))
        cast.autoplay.forget("another session")
        cast.autoplay.forget(SESSION)
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))

        gateway.report(CastRemoteState.Playing, positionMillis = 1_000L)
        gateway.report(CastRemoteState.Ended, positionMillis = 2_000L)

        assertEquals(1, gateway.loads.size)
    }

    @Test
    fun sessionAudioACastSessionTookOverGoesToTheReceiverFromWhereItStood() {
        cast.autoplay.follow(SESSION, Account(next = null))
        gateway.connect()

        cast.castFromSession(
            AudioItem,
            CastHandover(positionMillis = 95_000L, playing = true, speed = 1.25f),
            LocalPlayer(),
            scope,
        )
        idle()

        assertEquals(listOf(Load(42L, 95_000L, autoplay = true, speed = 1.25f)), gateway.loads)
    }

    @Test
    fun sessionAudioThatCannotReachTheReceiverEndsTheSessionAndPlaysOnHereWhereItStood() {
        val local = LocalPlayer()
        cast.autoplay.follow(SESSION, Account(next = null, prepares = false))
        gateway.connect()

        val handover = CastHandover(positionMillis = 95_000L, playing = true, speed = 1.25f)
        cast.castFromSession(AudioItem, handover, local, scope)
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.loads.isEmpty())
        assertEquals(listOf("stop"), gateway.commands)
        assertPlaysHereWhereItStood(local, speed = 1.25f)
        assertNull(cast.takeHandover(42L))
    }

    @Test
    fun aFileCastWhileSessionAudioIsStillPreparingKeepsTheReceiver() {
        val local = LocalPlayer()
        val account = SlowAccount(next = null)
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.castFromSession(AudioItem, CastHandover(positionMillis = 95_000L, playing = true), local, scope)
        idle()

        assertTrue(cast.cast(Media.copy(fileId = 7L), OtherItem, CastHandover(positionMillis = 0L, playing = true)))
        account.answer.complete(Unit)
        idle()

        assertEquals(listOf(Load(7L, 0L, autoplay = true, speed = 1f)), gateway.loads)
        assertTrue(gateway.commands.isEmpty())
        assertEquals(0, local.mediaItemCount)
    }

    @Test
    fun aScreenOpenedOnSessionAudioStillOnItsWayLeavesTheLoadToTheTransferFromWhereLocalPlaybackStood() {
        val account = SlowAccount(next = null)
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.castFromSession(AudioItem, CastHandover(positionMillis = 25_000L, playing = true), LocalPlayer(), scope)
        idle()

        assertTrue(cast.holds(42L))
        assertFalse(cast.cast(AudioMedia, AudioItem, CastHandover(positionMillis = 0L, playing = true)))
        account.answer.complete(Unit)
        idle()

        assertEquals(listOf(Load(42L, 25_000L, autoplay = true, speed = 1f)), gateway.loads)
    }

    @Test
    fun sessionAudioWhoseLookupFailsKeepsTheSessionWhenTheOpenScreensOwnLoadReachesTheReceiver() {
        val local = LocalPlayer()
        val account = SlowAccount(next = null, prepares = false)
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.castFromSession(AudioItem, CastHandover(positionMillis = 95_000L, playing = true), local, scope)
        account.answer.complete(Unit)
        idle()

        waitFor(WITHIN_THE_GRACE)
        assertTrue(cast.cast(AudioMedia, AudioItem, CastHandover(positionMillis = 95_000L, playing = true)))
        gateway.report(CastRemoteState.Playing, positionMillis = 95_000L)
        waitFor(RECEIVER_SETTLED)

        assertEquals(listOf(Load(42L, 95_000L, autoplay = true, speed = 1f)), gateway.loads)
        assertTrue(gateway.commands.isEmpty())
        assertEquals(CastRemoteState.Playing, gateway.status.value?.state)
        assertEquals(0, local.mediaItemCount)
    }

    @Test
    fun sessionAudioTheReceiverAlreadyPlaysStaysThereWhenTheLookupFails() {
        val local = LocalPlayer()
        cast.autoplay.follow(SESSION, Account(next = null, prepares = false))
        gateway.connect()
        gateway.report(CastRemoteState.Playing, positionMillis = 120_000L)

        cast.castFromSession(AudioItem, CastHandover(positionMillis = 95_000L, playing = true), local, scope)
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.commands.isEmpty())
        assertEquals(CastRemoteState.Playing, gateway.status.value?.state)
        assertEquals(0, local.mediaItemCount)
    }

    @Test
    fun sessionAudioWhoseLookupFailsEndsTheIdleSessionAScreenAttachedToAndLeftAndPlaysOnHere() {
        val local = LocalPlayer()
        val account = SlowAccount(next = null, prepares = false)
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.castFromSession(AudioItem, CastHandover(positionMillis = 95_000L, playing = true), local, scope)
        idle()

        cast.attach(AudioItem)
        gateway.report(CastRemoteState.Idle, positionMillis = 0L)
        account.answer.complete(Unit)
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.loads.isEmpty())
        assertEquals(listOf("stop"), gateway.commands)
        assertPlaysHereWhereItStood(local)
    }

    @Test
    fun sessionAudioWhoseReceiverNeverConnectsEndsTheSessionAndPlaysOnHere() {
        val local = LocalPlayer()
        cast.autoplay.follow(SESSION, Account(next = null))
        gateway.availability.value = CastAvailability.Connecting("Living room TV")

        cast.castFromSession(AudioItem, CastHandover(positionMillis = 95_000L, playing = true), local, scope)
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.loads.isEmpty())
        assertEquals(listOf("stop"), gateway.commands)
        assertEquals(CastAvailability.Available, gateway.availability.value)
        assertPlaysHereWhereItStood(local)
    }

    @Test
    fun sessionAudioEndsTheIdleReceiverTheUserSwitchedToWhileItWasPreparingAndPlaysOnHere() {
        val local = LocalPlayer()
        val account = SlowAccount(next = null)
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.castFromSession(AudioItem, CastHandover(positionMillis = 95_000L, playing = true), local, scope)
        idle()

        gateway.availability.value = CastAvailability.Connecting("Kitchen speaker")
        account.answer.complete(Unit)
        idle()
        gateway.connect("Kitchen speaker")
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.loads.isEmpty())
        assertEquals(listOf("stop"), gateway.commands)
        assertPlaysHereWhereItStood(local)
    }

    @Test
    fun signingOutWhileFailedSessionAudioEndsTheReceiverLeavesThisDeviceSilent() {
        val local = LocalPlayer()
        cast.autoplay.follow(SESSION, Account(next = null, prepares = false))
        gateway.connect()
        gateway.stopsAtOnce = false

        cast.castFromSession(AudioItem, CastHandover(positionMillis = 95_000L, playing = true), local, scope)
        waitFor(PAST_THE_GRACE)
        assertEquals(listOf("stop"), gateway.commands)

        cast.end()
        waitFor(RECEIVER_SETTLED)

        assertEquals(0, local.mediaItemCount)
        assertFalse(local.playWhenReady)
    }

    private fun assertPlaysHereWhereItStood(local: LocalPlayer, speed: Float = 1f) {
        assertEquals("42", local.currentMediaItem?.mediaId)
        assertEquals(95_000L, local.currentPosition)
        assertEquals(speed, local.playbackParameters.speed)
        assertTrue(local.playWhenReady)
        assertEquals(Media3Player.STATE_READY, local.playbackState)
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
    fun audioTheOutputSwitcherMovesHerePlaysOnFromWhereTheReceiverGotTo() {
        val local = LocalPlayer()
        cast.autoplay.follow(SESSION, Account(next = null))
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true, speed = 1.25f))
        gateway.status.value = gateway.remote(CastRemoteState.Playing, positionMillis = 60_000L, speed = 1.25f)
            .copy(audio = true)
        idle()

        cast.resumeHere(local, scope)
        gateway.movesHere.value = true
        now += 2_000L
        gateway.disconnect()

        assertEquals("42", local.currentMediaItem?.mediaId)
        assertEquals(62_500L, local.currentPosition)
        assertEquals(1.25f, local.playbackParameters.speed)
        assertTrue(local.playWhenReady)
        assertEquals(Media3Player.STATE_READY, local.playbackState)
        assertNull(cast.takeHandover(42L))
    }

    @Test
    fun aSessionEndedAnyOtherWayLeavesItsAudioToTheScreen() {
        val local = LocalPlayer()
        cast.autoplay.follow(SESSION, Account(next = null))
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.status.value = gateway.remote(CastRemoteState.Playing, positionMillis = 60_000L).copy(audio = true)
        idle()

        cast.resumeHere(local, scope)
        gateway.disconnect()

        assertEquals(0, local.mediaItemCount)
        assertEquals(CastHandover(positionMillis = 60_000L, playing = true), cast.takeHandover(42L))
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

    private data class Load(val fileId: Long, val startMillis: Long, val autoplay: Boolean, val speed: Float)

    /** The Cast SDK's side: a session the test opens and closes, and the receiver's reports. */
    private inner class FakeCastGateway : CastGateway {
        override val availability = MutableStateFlow<CastAvailability>(CastAvailability.Available)
        override val status = MutableStateFlow<CastRemoteStatus?>(null)
        override var endedStatus: CastRemoteStatus? = null
        override val movesHere = MutableStateFlow(false)
        override val devices = MutableStateFlow<List<CastDevice>>(emptyList())
        val loads = mutableListOf<Load>()
        val commands = mutableListOf<String>()
        var stopsAtOnce = true
        fun connect(device: String = "Living room TV") {
            availability.value = CastAvailability.Connected(device)
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

        override fun stopCasting() {
            commands += "stop"
            if (stopsAtOnce) end()
        }

        override fun endForSignOut() {
            // As the SDK may: the receiver's status lands before the session's end does.
            report(CastRemoteState.Playing, positionMillis = 90_000L)
            availability.value = CastAvailability.Available
            endedStatus = null
            status.value = null
            idle()
        }

        override fun localNetworkAnswered(granted: Boolean) = Unit

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

    private class Account(
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

    private class SlowAccount(next: PlaybackTarget?, prepares: Boolean = true) : CastAccount {
        private val account = Account(next, prepares = prepares)
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

    private object Folder : PlaybackRepository {
        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
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

    private class LocalPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var current: State = State.Builder()
            .setAvailableCommands(Media3Player.Commands.Builder().addAllCommands().build())
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

        private fun update(change: State.Builder.() -> State.Builder): ListenableFuture<*> {
            current = current.buildUpon().change().build()
            return Futures.immediateVoidFuture()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(time: Duration) = shadowOf(Looper.getMainLooper()).idleFor(time)

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
        val NextTarget = PlaybackTarget(FilesItemId(43L), "episode 2.mkv")
        const val SESSION = "session"
        val WITHIN_THE_GRACE: Duration = Duration.ofSeconds(1)
        val PAST_THE_GRACE: Duration = Duration.ofSeconds(6)
        val RECEIVER_SETTLED: Duration = Duration.ofSeconds(30)
    }
}
