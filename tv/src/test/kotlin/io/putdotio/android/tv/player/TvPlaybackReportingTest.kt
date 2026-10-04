package io.putdotio.android.tv.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.PutioFailure
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.withReportingLease
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.sdk.errors.PutioConfigurationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** TV start-from write-back on the shared writer and observer, with a fake position repository. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class TvPlaybackReportingTest {
    @Test
    fun playbackWritesEveryFifteenSecondsAndOnExitButNeverPerTick() = runTest {
        val fixture = Fixture(backgroundScope)
        val player = fixture.play(positionMillis = 10_000L)
        val positions = fixture.reporting.observe(player)
        runCurrent()

        // The screen's position poll ticks every 500 ms; none of those writes.
        repeat(29) { tick ->
            player.position(10_000L + (tick + 1) * 500L)
            advanceTimeBy(500L)
            runCurrent()
        }
        assertEquals(emptyList<Pair<Long, Double>>(), fixture.writes)
        player.position(25_000L)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(listOf(FILE_ID to 25.0), fixture.writes)

        player.position(31_000L)
        positions.close()
        runCurrent()
        assertEquals("Leaving writes the exit position", listOf(FILE_ID to 25.0, FILE_ID to 31.0), fixture.writes)
        assertEquals("The Files row follows each save", fixture.writes, fixture.saved)
        fixture.reporting.close()
    }

    @Test
    fun onlyAConfirmedResumeSettingOnTheCurrentSessionWrites() = runTest {
        val fixture = Fixture(backgroundScope, settings = AccountSettingsReducer.start().state)
        val player = fixture.play(positionMillis = 40_000L, playing = false)
        val positions = fixture.reporting.observe(player)
        positions.close()
        runCurrent()
        assertTrue("Unloaded settings confirm nothing", fixture.writes.isEmpty())

        fixture.settings.value = loaded(resumePlayback = false)
        fixture.reporting.observe(player).close()
        runCurrent()
        assertTrue("Resume turned off writes nothing", fixture.writes.isEmpty())

        fixture.settings.value = loaded(resumePlayback = true)
        fixture.current = false
        fixture.reporting.observe(player).close()
        runCurrent()
        assertTrue("A session that is no longer signed in writes nothing", fixture.writes.isEmpty())

        fixture.current = true
        fixture.reporting.observe(player).close()
        runCurrent()
        assertEquals(listOf(FILE_ID to 40.0), fixture.writes)
        fixture.reporting.close()
    }

    @Test
    fun aRebuiltPlayerKeepsItsPlaybacksLeaseAndANewPlaybackSupersedesIt() = runTest {
        val fixture = Fixture(backgroundScope)
        val first = checkNotNull(fixture.reporting.lease(FILE_ID))
        assertEquals("A player rebuilt for the same playback", first, fixture.reporting.lease(FILE_ID))

        // The old player's exit write is still in flight when the rebuilt player asks for its
        // lease; it must still land.
        val player = fixture.play(positionMillis = 50_000L, playing = false)
        fixture.reporting.observe(player).close()
        fixture.play(positionMillis = 50_000L, playing = false)
        runCurrent()
        assertEquals(listOf(FILE_ID to 50.0), fixture.writes)

        fixture.reporting.startPlayback()
        assertNotEquals("Playing the file again is a new playback", first, fixture.reporting.lease(FILE_ID))
        fixture.reporting.close()
    }

    @Test
    fun anAuthenticationRejectionFlagsTheSessionAndOtherFailuresDoNot() = runTest {
        val fixture = Fixture(backgroundScope)
        val player = fixture.play(positionMillis = 10_000L, playing = false)

        val offline = PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IllegalStateException("offline")))
        fixture.result = PlaybackRepositoryResult.Failure(offline)
        fixture.reporting.observe(player).close()
        runCurrent()
        assertFalse(fixture.reporting.authenticationRejected.value)

        fixture.result = PlaybackRepositoryResult.Failure(AuthFailure)
        player.position(20_000L)
        fixture.reporting.observe(player).close()
        runCurrent()
        assertTrue(fixture.reporting.authenticationRejected.value)
        assertTrue("A failed write updates no row", fixture.saved.isEmpty())
        fixture.reporting.close()
    }

    @Test
    fun aRejectionThatLandsAfterTheSessionChangedIsNotThisSessions() = runTest {
        lateinit var fixture: Fixture
        fixture = Fixture(backgroundScope, beforeResult = { fixture.current = false })
        fixture.result = PlaybackRepositoryResult.Failure(AuthFailure)
        val player = fixture.play(positionMillis = 10_000L, playing = false)
        fixture.reporting.observe(player).close()
        runCurrent()
        assertEquals(1, fixture.writes.size)
        assertFalse(fixture.reporting.authenticationRejected.value)
        fixture.reporting.close()
    }

    @Test
    fun aClosedSessionDiscardsTheExitPosition() = runTest {
        val fixture = Fixture(backgroundScope)
        val player = fixture.play(positionMillis = 10_000L)
        val positions = fixture.reporting.observe(player)
        fixture.reporting.close()
        positions.close()
        runCurrent()
        assertTrue(fixture.writes.isEmpty())
    }

    private class Fixture(
        scope: CoroutineScope,
        settings: AccountSettingsState = loaded(resumePlayback = true),
        private val beforeResult: () -> Unit = {},
    ) {
        val settings = MutableStateFlow(settings)
        var current = true
        var result: PlaybackRepositoryResult<Unit> = PlaybackRepositoryResult.Success(Unit)
        val writes = mutableListOf<Pair<Long, Double>>()
        val saved = mutableListOf<Pair<Long, Double>>()
        val reporting = TvPlaybackReporting(
            scope = scope,
            settings = this.settings,
            sessionCurrent = { current },
            write = { fileId, seconds ->
                writes += fileId to seconds
                beforeResult()
                result
            },
            onSaved = { fileId, seconds -> saved += fileId to seconds },
        )

        /** What the TV screen does: the item carries the playback's lease. */
        fun play(positionMillis: Long, playing: Boolean = true): ReportingPlayer {
            val lease = checkNotNull(reporting.lease(FILE_ID))
            return ReportingPlayer().apply {
                show(MediaItem.Builder().setMediaId("$FILE_ID").build().withReportingLease(lease), positionMillis)
                playWhenReady = playing
            }
        }
    }

    private companion object {
        const val FILE_ID = 9L
        val AuthFailure =
            PlaybackFailure.Putio(PutioFailure.AuthenticationRequired(PutioConfigurationException("rejected")))

        fun loaded(resumePlayback: Boolean): AccountSettingsState =
            AccountSettingsReducer.reduce(
                AccountSettingsReducer.start().state,
                AccountSettingsEvent.LoadSucceeded(
                    AccountSettingsRequestId(1),
                    AccountSettingsPreferences(
                        historyEnabled = true,
                        trashEnabled = true,
                        showSubtitles = true,
                        autoSelectSubtitles = true,
                        resumePlayback = resumePlayback,
                    ),
                ),
            ).state
    }
}

@UnstableApi
private class ReportingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        .build()

    override fun getState(): State = state

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    fun show(item: MediaItem, positionMillis: Long) {
        state = state.buildUpon()
            .setPlaylist(listOf(MediaItemData.Builder(item.mediaId).setMediaItem(item).build()))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setContentPositionMs(positionMillis)
            .build()
        invalidateState()
    }

    fun position(positionMillis: Long) {
        state = state.buildUpon().setContentPositionMs(positionMillis).build()
        invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        state = state.buildUpon()
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        return Futures.immediateVoidFuture()
    }
}
