package io.putdotio.android

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsRequestId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class MobilePlaybackReportingTest {
    @Test
    fun onlyEnabledResolvedItemsFromTheCurrentSessionReceiveAnOpaqueLease() = runTest {
        val fixture = ReportingFixture(backgroundScope)
        val item = MediaItem.Builder().setMediaId("42")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Episode")
                .setExtras(Bundle().apply { putString("existing", "preserved") }).build())
            .build()
        assertSame(item, fixture.factory.reportableItem(item, useStartFrom = false))
        val reportable = fixture.factory.reportableItem(item, useStartFrom = true)
        val lease = reportable.mediaMetadata.extras?.getString(PLAYBACK_REPORTING_LEASE_KEY)
        assertNotNull(UUID.fromString(requireNotNull(lease)))
        assertEquals("42", reportable.mediaId)
        assertEquals("Episode", reportable.mediaMetadata.title)
        assertEquals("preserved", reportable.mediaMetadata.extras?.getString("existing"))
        assertNull(item.mediaMetadata.extras?.getString(PLAYBACK_REPORTING_LEASE_KEY))

        val wrongSession = fixture.runtime.factoryFor(MobileAuthSessionId(99), fixture.settings, fixture.delegate)
        assertSame(item, wrongSession.reportableItem(item, useStartFrom = true))
        for (id in listOf("", "0", "-1", "invalid")) {
            val invalid = item.buildUpon().setMediaId(id).build()
            assertSame(invalid, fixture.factory.reportableItem(invalid, useStartFrom = true))
        }
        fixture.player.show(fixture.factory.reportableItem(item, useStartFrom = false), 10_000L)
        fixture.observer.flush()
        runCurrent()
        assertTrue(fixture.writes.isEmpty())
        fixture.close()
    }

    @Test
    fun unknownPendingAndFailedResumeSettingsSuppressWritesUntilConfirmedAgain() = runTest {
        val fixture = ReportingFixture(backgroundScope, initiallyLoaded = false)
        fixture.player.show(fixture.item(), 10_000L)
        fixture.observer.flush()
        runCurrent()
        assertTrue(fixture.writes.isEmpty())
        fixture.event(AccountSettingsEvent.LoadSucceeded(AccountSettingsRequestId(1), Preferences))
        runCurrent()
        fixture.observer.flush()
        runCurrent()
        assertEquals(listOf(42L to 10.0), fixture.writes)

        fixture.event(AccountSettingsEvent.ChangeRequested(
            AccountSettingsChange(AccountSettingsKey.ResumePlayback, false),
        ))
        runCurrent()
        fixture.snapshot(20_000L)
        runCurrent()
        val request = fixture.requestId()
        fixture.event(AccountSettingsEvent.SaveFailed(request, Failure))
        runCurrent()
        fixture.snapshot(30_000L)
        runCurrent()
        assertEquals(listOf(42L to 10.0), fixture.writes)

        fixture.event(AccountSettingsEvent.RetryChange)
        val retry = fixture.requestId()
        fixture.event(AccountSettingsEvent.SaveSucceeded(retry))
        fixture.event(AccountSettingsEvent.RefreshSucceeded(retry, Preferences.copy(resumePlayback = false)))
        runCurrent()
        fixture.snapshot(40_000L)
        runCurrent()
        fixture.event(AccountSettingsEvent.ChangeRequested(
            AccountSettingsChange(AccountSettingsKey.ResumePlayback, true),
        ))
        val enable = fixture.requestId()
        runCurrent()
        fixture.snapshot(50_000L)
        fixture.event(AccountSettingsEvent.SaveSucceeded(enable))
        runCurrent()
        fixture.snapshot(60_000L)
        runCurrent()
        assertEquals(listOf(42L to 10.0), fixture.writes)
        fixture.event(AccountSettingsEvent.RefreshSucceeded(enable, Preferences))
        runCurrent()
        assertEquals(listOf(42L to 10.0), fixture.writes)
        fixture.snapshot(70_000L)
        runCurrent()
        assertEquals(listOf(42L to 10.0, 42L to 70.0), fixture.writes)
        fixture.close()
    }

    @Test
    fun unrelatedPendingAndFailedSettingsDoNotRevokeConfirmedResume() = runTest {
        val fixture = ReportingFixture(backgroundScope)
        fixture.player.show(fixture.item(), 10_000L)
        fixture.event(AccountSettingsEvent.ChangeRequested(
            AccountSettingsChange(AccountSettingsKey.History, false),
        ))
        runCurrent()
        fixture.observer.flush()
        runCurrent()
        fixture.event(AccountSettingsEvent.SaveFailed(fixture.requestId(), Failure))
        runCurrent()
        fixture.snapshot(20_000L)
        runCurrent()
        assertEquals(listOf(42L to 10.0, 42L to 20.0), fixture.writes)
        fixture.close()
    }

    @Test
    fun disablingResumeCancelsInflightAndQueuedPositionsWithoutReplayingThemOnEnable() = runTest {
        val fixture = ReportingFixture(backgroundScope, suspendWrites = true)
        fixture.player.show(fixture.item(), 10_000L)
        fixture.observer.flush()
        runCurrent()
        fixture.snapshot(20_000L)
        runCurrent()
        fixture.event(AccountSettingsEvent.ChangeRequested(
            AccountSettingsChange(AccountSettingsKey.ResumePlayback, false),
        ))
        runCurrent()
        assertEquals(1, fixture.cancelledWrites)
        val disable = fixture.requestId()
        fixture.event(AccountSettingsEvent.SaveSucceeded(disable))
        fixture.event(AccountSettingsEvent.RefreshSucceeded(disable, Preferences.copy(resumePlayback = false)))
        fixture.event(AccountSettingsEvent.ChangeRequested(
            AccountSettingsChange(AccountSettingsKey.ResumePlayback, true),
        ))
        val enable = fixture.requestId()
        fixture.event(AccountSettingsEvent.SaveSucceeded(enable))
        fixture.event(AccountSettingsEvent.RefreshSucceeded(enable, Preferences))
        runCurrent()
        assertEquals(listOf(42L to 10.0), fixture.writes)
        fixture.snapshot(30_000L)
        runCurrent()
        assertEquals(listOf(42L to 10.0, 42L to 30.0), fixture.writes)
        fixture.close()
    }

    @Test
    fun sessionExitCancelsInflightAndQueuedWritesAndOldPlayersCannotWriteInANewSession() = runTest {
        val fixture = ReportingFixture(backgroundScope, suspendWrites = true)
        val oldItem = fixture.item()
        fixture.player.show(oldItem, 10_000L)
        fixture.observer.flush()
        runCurrent()
        fixture.snapshot(20_000L)
        runCurrent()
        fixture.auth.value = MobileAuthState.SigningOut
        runCurrent()
        assertEquals(1, fixture.cancelledWrites)
        assertEquals(listOf(42L to 10.0), fixture.writes)
        val nextSession = MobileAuthSessionId(2)
        fixture.auth.value = MobileAuthState.SignedIn(Account, nextSession)
        runCurrent()
        val nextFactory = fixture.runtime.factoryFor(nextSession, fixture.settings, fixture.delegate)
        runCurrent()
        fixture.snapshot(30_000L)
        runCurrent()
        assertEquals(listOf(42L to 10.0), fixture.writes)
        assertSame(oldItem, fixture.factory.reportableItem(oldItem, useStartFrom = true))
        val nextItem = nextFactory.reportableItem(MediaItem.Builder().setMediaId("42").build(), true)
        fixture.player.show(nextItem, 40_000L)
        fixture.observer.flush()
        runCurrent()
        assertEquals(listOf(42L to 10.0, 42L to 40.0), fixture.writes)
        fixture.close()
    }
}

@UnstableApi
private class ReportingFixture(
    scope: CoroutineScope,
    initiallyLoaded: Boolean = true,
    suspendWrites: Boolean = false,
) {
    val auth = MutableStateFlow<MobileAuthState>(MobileAuthState.SignedIn(Account, MobileAuthSessionId(1)))
    val settings = MutableStateFlow(
        if (initiallyLoaded) AccountSettingsReducer.reduce(
            AccountSettingsReducer.start().state,
            AccountSettingsEvent.LoadSucceeded(AccountSettingsRequestId(1), Preferences),
        ).state else AccountSettingsReducer.start().state,
    )
    val writes = mutableListOf<Pair<Long, Double>>()
    var cancelledWrites = 0
    val runtime = MobilePlaybackReporting(auth, scope) { fileId, seconds ->
        writes += fileId to seconds
        if (suspendWrites) {
            try {
                awaitCancellation()
            } finally {
                cancelledWrites += 1
            }
        }
        PlaybackRepositoryResult.Success(Unit)
    }
    val player = ReportingPlayer()
    val delegate = MobilePlayerFactory { _, _ -> player }
    val factory = runtime.factoryFor(MobileAuthSessionId(1), settings, delegate)
    val observer = runtime.observe(player)

    fun item(): MediaItem = factory.reportableItem(MediaItem.Builder().setMediaId("42").build(), true)

    fun event(event: AccountSettingsEvent) {
        settings.value = AccountSettingsReducer.reduce(settings.value, event).state
    }

    fun requestId(): AccountSettingsRequestId =
        (settings.value.mutation as AccountSettingsMutation.Saving).requestId

    fun snapshot(positionMillis: Long) {
        player.position(positionMillis)
        observer.flush()
    }

    fun close() {
        observer.close()
        player.release()
    }
}

@UnstableApi
private class ReportingPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAllCommands().build()).build()

    override fun getState(): State = state

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    fun show(item: MediaItem, positionMillis: Long) {
        state = state.buildUpon()
            .setPlaylist(listOf(MediaItemData.Builder(item).setMediaItem(item).build()))
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
}

private val Account = MobileAccount(42L, "test-user", "test@example.invalid")
private val Preferences = AccountSettingsPreferences(
    historyEnabled = true,
    trashEnabled = true,
    showSubtitles = true,
    autoSelectSubtitles = true,
    resumePlayback = true,
)
private val Failure = AccountSettingsFailure.Unexpected(IllegalStateException("offline"))
