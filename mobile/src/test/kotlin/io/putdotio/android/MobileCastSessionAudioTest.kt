package io.putdotio.android

import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.CastFixtures.AudioItem
import io.putdotio.android.CastFixtures.AudioMedia
import io.putdotio.android.CastFixtures.Item
import io.putdotio.android.CastFixtures.Media
import io.putdotio.android.CastFixtures.OtherItem
import io.putdotio.android.CastFixtures.SESSION
import io.putdotio.android.playback.CastAvailability
import io.putdotio.android.playback.CastHandover
import io.putdotio.android.playback.CastRemoteState
import io.putdotio.android.playback.MobileCastController
import io.putdotio.android.playback.resumeHere
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
class MobileCastSessionAudioTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var now = 10_000L
    private val gateway = FakeCastGateway { now }
    private val cast = MobileCastController(gateway, scope) { now }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun sessionAudioACastSessionTookOverGoesToTheReceiverFromWhereItStood() {
        val handover = CastHandover(positionMillis = 95_000L, playing = true, speed = 1.25f)
        val local = LocalPlayer(handover)
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null))
        gateway.connect()

        cast.castFromSession(AudioItem, handover, local, scope)
        idle()

        assertEquals(listOf(Load(42L, 95_000L, autoplay = true, speed = 1.25f)), gateway.loads)
        assertEquals("42", local.currentMediaItem?.mediaId)
        assertFalse(local.playWhenReady)

        gateway.report(CastRemoteState.Playing, positionMillis = 95_000L)

        assertEquals(0, local.mediaItemCount)
        assertTrue(gateway.commands.isEmpty())
    }

    @Test
    fun sessionAudioTheReceiverRejectsEndsTheSessionAndPlaysOnHereWhereItStood() {
        val local = LocalPlayer(HANDED_OVER)
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null))
        gateway.connect()

        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
        idle()
        gateway.report(CastRemoteState.Failed, positionMillis = 0L)
        waitFor(RECEIVER_SETTLED)

        assertEquals(listOf(Load(42L, 95_000L, autoplay = true, speed = 1f)), gateway.loads)
        assertEquals(listOf("stop"), gateway.commands)
        assertPlaysHereWhereItStood(local)
    }

    @Test
    fun sessionAudioWhoseReceiverIsStuckConnectingEndsTheSessionAndPlaysOnHereWhenTheSameFileOpens() {
        val local = LocalPlayer(HANDED_OVER)
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null))
        gateway.availability.value = CastAvailability.Connecting("Living room TV")

        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
        waitFor(WITHIN_THE_WAIT)
        assertTrue(cast.holds(42L))
        assertNull(cast.takeHandover(42L))
        cast.attach(AudioItem)
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.loads.isEmpty())
        assertEquals(listOf("stop"), gateway.commands)
        assertPlaysHereWhereItStood(local)
    }

    @Test
    fun sessionAudioTheReceiverAlreadyPlaysHandsBackTheReceiversPositionWhenTheSessionEndsSoon() {
        val local = LocalPlayer(HANDED_OVER)
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null, prepares = false))
        gateway.connect()
        gateway.report(CastRemoteState.Playing, positionMillis = 120_000L)

        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
        waitFor(WITHIN_THE_WAIT)
        gateway.disconnect()
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.commands.isEmpty())
        assertEquals(0, local.mediaItemCount)
        assertEquals(CastHandover(positionMillis = 120_000L, playing = true), cast.takeHandover(42L))
    }

    @Test
    fun sessionAudioWhoseLookupFailsLeavesAReceiverPlayingAnotherFileAlone() {
        val local = LocalPlayer(HANDED_OVER)
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null, prepares = false))
        gateway.connect()
        gateway.status.value = gateway.remote(CastRemoteState.Playing, positionMillis = 10_000L).copy(fileId = 7L)
        idle()

        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.loads.isEmpty())
        assertTrue(gateway.commands.isEmpty())
        assertEquals(7L, gateway.status.value?.fileId)
        assertEquals(0, local.mediaItemCount)
    }

    @Test
    fun signingOutBeforeTheReceiverPlaysSessionAudioLeavesThisDeviceSilent() {
        val local = LocalPlayer(HANDED_OVER)
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null))
        gateway.connect()
        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
        idle()

        cast.end()
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.commands.isEmpty())
        assertEquals(0, local.mediaItemCount)
        assertFalse(local.playWhenReady)
    }

    @Test
    fun sessionAudioThatCannotReachTheReceiverEndsTheSessionAndPlaysOnHereWhereItStood() {
        val handover = CastHandover(positionMillis = 95_000L, playing = true, speed = 1.25f)
        val local = LocalPlayer(handover)
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null, prepares = false))
        gateway.connect()

        cast.castFromSession(AudioItem, handover, local, scope)
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.loads.isEmpty())
        assertEquals(listOf("stop"), gateway.commands)
        assertPlaysHereWhereItStood(local, speed = 1.25f)
        assertNull(cast.takeHandover(42L))
    }

    @Test
    fun aFileCastWhileSessionAudioIsStillPreparingKeepsTheReceiver() {
        val local = LocalPlayer(HANDED_OVER)
        val account = SlowCastAccount(next = null)
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
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
        val account = SlowCastAccount(next = null)
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
        val local = LocalPlayer(HANDED_OVER)
        val account = SlowCastAccount(next = null, prepares = false)
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
        account.answer.complete(Unit)
        idle()

        waitFor(WITHIN_THE_WAIT)
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
        val local = LocalPlayer(HANDED_OVER)
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null, prepares = false))
        gateway.connect()
        gateway.report(CastRemoteState.Playing, positionMillis = 120_000L)

        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.commands.isEmpty())
        assertEquals(CastRemoteState.Playing, gateway.status.value?.state)
        assertEquals(0, local.mediaItemCount)
    }

    @Test
    fun sessionAudioWhoseLookupFailsEndsTheIdleSessionAScreenAttachedToAndLeftAndPlaysOnHere() {
        val local = LocalPlayer(HANDED_OVER)
        val account = SlowCastAccount(next = null, prepares = false)
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
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
        val local = LocalPlayer(HANDED_OVER)
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null))
        gateway.availability.value = CastAvailability.Connecting("Living room TV")

        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
        waitFor(RECEIVER_SETTLED)

        assertTrue(gateway.loads.isEmpty())
        assertEquals(listOf("stop"), gateway.commands)
        assertEquals(CastAvailability.Available, gateway.availability.value)
        assertPlaysHereWhereItStood(local)
    }

    @Test
    fun sessionAudioEndsTheIdleReceiverTheUserSwitchedToWhileItWasPreparingAndPlaysOnHere() {
        val local = LocalPlayer(HANDED_OVER)
        val account = SlowCastAccount(next = null)
        cast.autoplay.follow(SESSION, account)
        gateway.connect()
        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
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
        val local = LocalPlayer(HANDED_OVER)
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null, prepares = false))
        gateway.connect()
        gateway.stopsAtOnce = false

        cast.castFromSession(AudioItem, HANDED_OVER, local, scope)
        waitFor(PAST_THE_WAIT)
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
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null))
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
        cast.autoplay.follow(SESSION, FakeCastAccount(next = null))
        gateway.connect()
        cast.cast(Media, Item, CastHandover(positionMillis = 0L, playing = true))
        gateway.status.value = gateway.remote(CastRemoteState.Playing, positionMillis = 60_000L).copy(audio = true)
        idle()

        cast.resumeHere(local, scope)
        gateway.disconnect()

        assertEquals(0, local.mediaItemCount)
        assertEquals(CastHandover(positionMillis = 60_000L, playing = true), cast.takeHandover(42L))
    }

    private fun idle() = idleMain()

    private fun waitFor(time: Duration) = shadowOf(android.os.Looper.getMainLooper()).idleFor(time)

    private companion object {
        val HANDED_OVER = CastHandover(positionMillis = 95_000L, playing = true)
        val WITHIN_THE_WAIT: Duration = Duration.ofSeconds(1)
        val PAST_THE_WAIT: Duration = Duration.ofSeconds(11)
        val RECEIVER_SETTLED: Duration = Duration.ofSeconds(30)
    }
}
