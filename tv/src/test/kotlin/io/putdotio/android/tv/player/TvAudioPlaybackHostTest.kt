package io.putdotio.android.tv.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.withReportingLease
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.tv.TvSessionKey
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CancellationException
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
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvAudioPlaybackHostTest {
    @After
    fun tearDown() = TvPlaybackService.resetForTests()

    @Test
    fun ordinaryStopLetsFinalAudioPositionWriteFinishAndNotifiesSaved() {
        val harness = startAudioHarness()

        TvPlaybackService.audioHost.stop()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(TvPlaybackService.audioHost.active.value)
        assertTrue(harness.writeStarted.isCompleted)
        assertFalse(harness.writeCancelled.isCompleted)

        harness.releaseWrite.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(9L to 47.0), harness.completedWrites)
        assertEquals(listOf(9L to 47.0), harness.saved)
    }

    @Test
    fun endingAuthenticationCancelsDrainingPositionWrite() {
        val harness = startAudioHarness()

        TvPlaybackService.audioHost.stop()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(harness.writeStarted.isCompleted)
        assertFalse(harness.writeCancelled.isCompleted)

        harness.authState.value = TvAuthState.SigningOut
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(harness.writeCancelled.isCompleted)
        assertTrue(harness.saved.isEmpty())
    }

    @Test
    fun supersedeAfterStopWaitsForDrainingAudioPositionWrite() {
        val order = mutableListOf<String>()
        val harness = startAudioHarness(onOrder = { order += it })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        try {
            TvPlaybackService.audioHost.stop()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("audio-start"), order)

            scope.launch {
                TvPlaybackService.audioHost.supersede(9L) { order += "watched" }
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("audio-start"), order)

            harness.releaseWrite.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("audio-start", "audio-end", "watched"), order)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stoppingForEndedSessionSkipsFinalPositionWrite() {
        val harness = startAudioHarness()

        TvPlaybackService.audioHost.stop(cancelWrites = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(TvPlaybackService.audioHost.active.value)
        assertEquals(0, harness.writeCalls)
        assertTrue(harness.saved.isEmpty())
    }

    @Test
    fun reattachingWithLoadingSettingsDoesNotCancelInFlightPositionWrite() {
        val harness = startAudioHarness()
        val key = TvSessionKey(42L, TvAuthSessionId(1L))
        TvPlaybackService.audioHost.detachCallbacks(harness.savedCallback)

        harness.player.pause()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(harness.writeStarted.isCompleted)

        val loading = AccountSettingsReducer.start().state
        TvPlaybackService.audioHost.reattach(
            TvAudioSessionAttachments(
                sessionKey = key,
                settings = MutableStateFlow(loading),
                onSaved = harness.savedCallback,
                onAuthenticationRejected = {},
            ),
        )
        TvPlaybackService.audioHost.updateSettings(key, loading)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(harness.writeCancelled.isCompleted)

        harness.releaseWrite.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(9L to 47.0), harness.completedWrites)
        assertEquals(listOf(9L to 47.0), harness.saved)
    }

    @Test
    fun detached401StopsAudioAndSubsequentWritesAndNotifiesOnReattach() {
        val rejected = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))
        val harness = startAudioHarness(
            writeResult = { PlaybackRepositoryResult.Failure(PlaybackFailure.Putio(rejected)) },
        )
        harness.releaseWrite.complete(Unit)
        TvPlaybackService.audioHost.detachCallbacks(harness.savedCallback)

        harness.player.pause()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(TvPlaybackService.audioHost.active.value)
        assertEquals(1, harness.writeCalls)

        harness.player.seekTo(60_000L)
        harness.player.play()
        harness.player.pause()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, harness.writeCalls)

        var reattachRejected = 0
        TvPlaybackService.audioHost.reattach(
            TvAudioSessionAttachments(
                sessionKey = TvSessionKey(42L, TvAuthSessionId(1L)),
                settings = MutableStateFlow(resumeEnabledSettings()),
                onSaved = harness.savedCallback,
                onAuthenticationRejected = { reattachRejected += 1 },
            ),
        )
        assertEquals(1, reattachRejected)
    }
}

private class AudioHostHarness(
    val authState: MutableStateFlow<TvAuthState>,
    val saved: MutableList<Pair<Long, Double>>,
    val savedCallback: (Long, Double) -> Unit,
    private val onOrder: (String) -> Unit,
    private val writeResult: () -> PlaybackRepositoryResult<Unit>,
) {
    val writeStarted = CompletableDeferred<Unit>()
    val releaseWrite = CompletableDeferred<Unit>()
    val writeCancelled = CompletableDeferred<Unit>()
    val completedWrites = mutableListOf<Pair<Long, Double>>()
    var writeCalls: Int = 0
    lateinit var player: FakePlayer

    suspend fun recordWrite(fileId: Long, seconds: Double): PlaybackRepositoryResult<Unit> {
        writeCalls += 1
        onOrder("audio-start")
        writeStarted.complete(Unit)
        try {
            releaseWrite.await()
        } catch (cancelled: CancellationException) {
            writeCancelled.complete(Unit)
            throw cancelled
        }
        onOrder("audio-end")
        val result = writeResult()
        if (result is PlaybackRepositoryResult.Success) completedWrites += fileId to seconds
        return result
    }
}

@UnstableApi
private fun startAudioHarness(
    onOrder: (String) -> Unit = {},
    writeResult: () -> PlaybackRepositoryResult<Unit> = { PlaybackRepositoryResult.Success(Unit) },
): AudioHostHarness {
    val songTarget = PlaybackTarget(FilesItemId(9L), "01 Song.mp3", PlaybackMediaType.AUDIO, 120.0)
    val authState = MutableStateFlow<TvAuthState>(
        TvAuthState.SignedIn(TvAccount(42L, "u", "u@example.com", historyEnabled = true), TvAuthSessionId(1L)),
    )
    val saved = mutableListOf<Pair<Long, Double>>()
    val savedCallback: (Long, Double) -> Unit = { fileId, seconds -> saved += fileId to seconds }
    val harness = AudioHostHarness(authState, saved, savedCallback, onOrder, writeResult)
    TvPlaybackService.audioHost.start(
        TvAudioStartRequest(
            identity = TvAudioSessionIdentity(
                sessionKey = TvSessionKey(42L, TvAuthSessionId(1L)),
                authState = authState,
                application = ApplicationProvider.getApplicationContext(),
            ),
            media = TvAudioMedia(
                target = songTarget,
                startItem = FilesItem(
                    id = songTarget.fileId,
                    parentId = FilesItemId(0L),
                    name = songTarget.name,
                    type = PutioFileType.AUDIO,
                    sizeBytes = 1L,
                    createdAt = "2026-04-20T10:00:00Z",
                ),
                fromHome = false,
                startAnswer = PlaybackEvent.Resume,
            ),
            callbacks = TvAudioSessionCallbacks(
                playbackRepository = singleSongRepository(),
                settings = MutableStateFlow(resumeEnabledSettings()),
                writePlaybackPosition = harness::recordWrite,
                onSaved = savedCallback,
                onAuthenticationRejected = {},
            ),
        ),
    )
    shadowOf(Looper.getMainLooper()).idle()
    val hosted = checkNotNull(TvPlaybackService.audioHost.active.value)
    val lease = checkNotNull(hosted.reporting.lease(songTarget.fileId.value))
    val player = FakePlayer().apply {
        setMediaItem(MediaItem.Builder().setMediaId("9").build().withReportingLease(lease), 47_000L)
        playWhenReady = true
        ready()
    }
    harness.player = player
    checkNotNull(hosted.queue).take(source(fileId = 9L), songTarget, player)
    shadowOf(Looper.getMainLooper()).idle()
    return harness
}

private fun singleSongRepository() = object : PlaybackRepository {
    override suspend fun resolve(target: PlaybackTarget, convertedOnly: Boolean) =
        PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source(fileId = target.fileId.value), false))

    override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended

    override suspend fun findNeighbours(target: PlaybackTarget) =
        PlaybackRepositoryResult.Success(PlaybackNeighbours(previous = null, next = null, folderName = "Mix"))
}

private fun resumeEnabledSettings(): AccountSettingsState =
    AccountSettingsReducer.reduce(
        AccountSettingsReducer.start().state,
        AccountSettingsEvent.LoadSucceeded(
            AccountSettingsRequestId(1),
            AccountSettingsPreferences(
                historyEnabled = true,
                trashEnabled = true,
                showSubtitles = true,
                autoSelectSubtitles = true,
                resumePlayback = true,
            ),
        ),
    ).state
