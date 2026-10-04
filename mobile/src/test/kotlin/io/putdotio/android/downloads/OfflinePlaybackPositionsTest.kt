package io.putdotio.android.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.sdk.files.PutioFileType
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OfflinePlaybackPositionsTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("offline-positions-test", Context.MODE_PRIVATE)
    // put.io held the listing's position when the file was downloaded.
    private val remote = FakePositionRemote().apply { positions[FILE] = 100.0 }
    private var signedIn: Long? = USER
    private val synced = mutableListOf<Pair<Long, Double>>()
    private val sintel = DownloadEntry(
        FilesItemId(FILE), "Sintel.mkv", PutioFileType.VIDEO, DownloadArtifact.HLS, DownloadStatus.Completed(1L),
        createdAt = 1L, startFromSeconds = 100.0,
    )

    @After
    fun clear() {
        preferences.edit().clear().commit()
    }

    @Test
    fun offlinePlaybackResumesFromWhatItPlayedAndSyncsThatOncePutioAnswers() = runTest {
        remote.online = false
        val positions = positions(backgroundScope)
        // Opened offline: the listing's position from download time.
        assertEquals(100.0, positions.resumePosition(USER, sintel), 0.0)
        positions.afterWrite(USER, FILE, 250.0, NetworkFailure)

        // A process death in between loses nothing.
        val restored = positions(backgroundScope)
        assertEquals(250.0, restored.resumePosition(USER, sintel), 0.0)
        remote.online = true
        restored.syncNow(USER)

        assertEquals(listOf(FILE to 250.0), remote.writes)
        assertTrue(restored.store(USER).pending().isEmpty())
        assertEquals(250.0, restored.store(USER).known(FILE))
        assertEquals(listOf(FILE to 250.0), synced)
    }

    @Test
    fun aPositionAnotherDeviceSavedSinceWinsOverTheOfflineOne() = runTest {
        remote.online = false
        val positions = positions(backgroundScope)
        positions.resumePosition(USER, sintel)
        positions.afterWrite(USER, FILE, 250.0, NetworkFailure)

        remote.online = true
        remote.positions[FILE] = 900.0
        positions.syncNow(USER)

        assertTrue(remote.writes.isEmpty())
        assertTrue(positions.store(USER).pending().isEmpty())
        assertEquals(900.0, positions.resumePosition(USER, sintel), 0.0)
    }

    @Test
    fun aWriteThatLandedWithoutAReplyIsNotMistakenForAnotherDevice() = runTest {
        remote.online = false
        val positions = positions(backgroundScope)
        positions.resumePosition(USER, sintel)
        positions.afterWrite(USER, FILE, 250.0, NetworkFailure)

        remote.online = true
        remote.landButFail = true
        positions.syncNow(USER)
        assertEquals(250.0, remote.positions[FILE])
        assertEquals(250.0, positions.store(USER).pending(FILE)?.seconds)

        remote.landButFail = false
        positions.afterWrite(USER, FILE, 300.0, NetworkFailure)
        positions.syncNow(USER)
        positions.syncNow(USER)

        assertEquals(300.0, remote.positions[FILE])
        assertNull(positions.store(USER).pending(FILE))
    }

    @Test
    fun resumeTurnedOffOnTheAccountDropsWhatWasPlayedOffline() = runTest {
        remote.online = false
        val positions = positions(backgroundScope)
        positions.resumePosition(USER, sintel)
        positions.afterWrite(USER, FILE, 250.0, NetworkFailure)

        remote.online = true
        remote.resume = false
        positions.syncNow(USER)

        assertTrue(remote.writes.isEmpty())
        assertTrue(positions.store(USER).pending().isEmpty())
        assertEquals(false, positions.store(USER).resumeSetting)
    }

    @Test
    fun onlyTheSignedInOwnersPositionsAreSentAndOnlyForDownloadedFiles() = runTest {
        remote.online = false
        val positions = positions(backgroundScope)
        positions.resumePosition(USER, sintel)
        positions.afterWrite(USER, FILE, 250.0, NetworkFailure)
        // A streamed file was never opened offline; its failed write is not kept.
        positions.afterWrite(USER, 99L, 40.0, NetworkFailure)
        assertNull(positions.store(USER).pending(99L))

        remote.online = true
        signedIn = OTHER
        positions.syncNow(USER)
        assertTrue(remote.writes.isEmpty())
        assertEquals(250.0, positions.store(USER).pending(FILE)?.seconds)
    }

    private fun positions(scope: CoroutineScope) =
        OfflinePlaybackPositions(preferences, { signedIn }, remote, scope) { fileId, seconds ->
            synced += fileId to seconds
        }

    private companion object {
        const val USER = 7L
        const val OTHER = 8L
        const val FILE = 10L
        val NetworkFailure = PlaybackRepositoryResult.Failure(
            PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("offline"))),
        )
    }
}

internal class FakePositionRemote : PositionRemote {
    var online = true
    var resume = true
    /** The server stores the position, but the reply never arrives. */
    var landButFail = false
    val positions = mutableMapOf<Long, Double>()
    val writes = mutableListOf<Pair<Long, Double>>()

    override suspend fun read(fileId: Long): PlaybackRepositoryResult<Double> =
        if (online) PlaybackRepositoryResult.Success(positions[fileId] ?: 0.0) else offline()

    override suspend fun write(fileId: Long, seconds: Double): PlaybackRepositoryResult<Unit> {
        if (online) positions[fileId] = seconds
        if (online && !landButFail) writes += fileId to seconds
        return if (online && !landButFail) PlaybackRepositoryResult.Success(Unit) else offline()
    }

    override suspend fun resumeEnabled(): PlaybackRepositoryResult<Boolean> =
        if (online) PlaybackRepositoryResult.Success(resume) else offline()

    private fun offline() = PlaybackRepositoryResult.Failure(
        PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("offline"))),
    )
}
