package io.putdotio.android.downloads

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
        val positions = playedOffline()

        remote.online = true
        remote.positions[FILE] = 900.0
        positions.syncNow(USER)

        assertTrue(remote.writes.isEmpty())
        assertTrue(positions.store(USER).pending().isEmpty())
        assertEquals(900.0, positions.resumePosition(USER, sintel), 0.0)
    }

    @Test
    fun aWriteThatLandedWithoutAReplyIsNotMistakenForAnotherDevice() = runTest {
        val positions = playedOffline()

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
    fun aNewerPlayerWriteDuringTheReadIsNeverOverwrittenByTheOlderOfflinePosition() = runTest {
        val positions = playedOffline()

        remote.online = true
        // The read sees the old server value; the online player's newer write lands right after it.
        remote.afterRead = {
            remote.positions[FILE] = 400.0
            positions.afterWrite(USER, FILE, 400.0, PlaybackRepositoryResult.Success(Unit))
        }
        positions.syncNow(USER)

        assertTrue(remote.writes.isEmpty())
        assertEquals(400.0, remote.positions[FILE])
        assertEquals(400.0, positions.store(USER).known(FILE))
        assertTrue(synced.isEmpty())
    }

    @Test
    fun aPlayerWriteAcceptedDuringTheSyncWriteKeepsItsNewerPosition() = runTest {
        val positions = playedOffline()

        remote.online = true
        remote.duringWrite = { positions.afterWrite(USER, FILE, 400.0, PlaybackRepositoryResult.Success(Unit)) }
        positions.syncNow(USER)

        // The older position's late confirmation neither rewrites what is known nor reaches Files rows.
        assertEquals(400.0, positions.store(USER).known(FILE))
        assertNull(positions.store(USER).pending(FILE))
        assertTrue(synced.isEmpty())
    }

    @Test
    fun aSignOutDuringTheSyncReadStopsItsWrite() = runTest {
        val positions = playedOffline()

        remote.online = true
        remote.afterRead = { signedIn = null }
        positions.syncNow(USER)

        assertTrue(remote.writes.isEmpty())
        assertEquals(250.0, positions.store(USER).pending(FILE)?.seconds)
    }

    @Test
    fun aSignOutDuringTheSyncWritePublishesNothingAndTheNextPassSettlesIt() = runTest {
        val positions = playedOffline()

        remote.online = true
        remote.duringWrite = { signedIn = null }
        positions.syncNow(USER)
        assertEquals(250.0, remote.positions[FILE])
        assertTrue(synced.isEmpty())

        signedIn = USER
        positions.syncNow(USER)
        assertNull(positions.store(USER).pending(FILE))
        assertEquals(listOf(FILE to 250.0), synced)
    }

    @Test
    fun aFileDeletedOrNoLongerAccessibleStopsCostingAReadWhileANetworkFailureKeepsWaiting() = runTest {
        val positions = playedOffline()
        remote.online = true

        remote.readFailure = NetworkFailure.failure
        positions.syncNow(USER)
        assertEquals(250.0, positions.store(USER).pending(FILE)?.seconds)

        remote.readFailure = PlaybackFailure.Putio(
            PutioFailure.ApiRejected(HTTP_NOT_FOUND, "NotFound", PutioConfigurationException("gone")),
        )
        positions.syncNow(USER)
        assertNull(positions.store(USER).pending(FILE))
        val reads = remote.reads
        positions.syncNow(USER)
        assertEquals(reads, remote.reads)
        assertTrue(remote.writes.isEmpty())
    }

    @Test
    fun resumeTurnedOffOnTheAccountDropsWhatWasPlayedOffline() = runTest {
        val positions = playedOffline()

        remote.online = true
        remote.resume = false
        positions.syncNow(USER)

        assertTrue(remote.writes.isEmpty())
        assertTrue(positions.store(USER).pending().isEmpty())
        assertEquals(false, positions.store(USER).resumeSetting)
    }

    @Test
    fun onlyTheSignedInOwnersPositionsAreSentAndOnlyForDownloadedFiles() = runTest {
        val positions = playedOffline()
        // A streamed file was never opened offline; its failed write is not kept.
        positions.afterWrite(USER, 99L, 40.0, NetworkFailure)
        assertNull(positions.store(USER).pending(99L))

        remote.online = true
        signedIn = OTHER
        positions.syncNow(USER)
        assertTrue(remote.writes.isEmpty())
        assertEquals(250.0, positions.store(USER).pending(FILE)?.seconds)
    }

    @Test
    fun a401KeepsPendingPositionsAndRejectsTheSessionWhileOnlyPermanentFileErrorsDiscard() = runTest {
        remote.online = false
        var sessionToken = "session-1"
        val rejected = mutableListOf<String>()
        val positions = OfflinePlaybackPositions(
            preferences = preferences,
            session = OfflinePositionSession(
                userId = { signedIn },
                captureRejection = {
                    val captured = sessionToken
                    { rejected += captured }
                },
            ),
            remote = remote,
            scope = backgroundScope,
            onSynced = { fileId, seconds -> synced += fileId to seconds },
        )
        positions.resumePosition(USER, sintel)
        positions.afterWrite(USER, FILE, 250.0, PlaybackRepositoryResult.Failure(AuthenticationFailure))
        assertEquals(250.0, positions.store(USER).pending(FILE)?.seconds)

        remote.online = true
        remote.readFailure = AuthenticationFailure
        remote.afterRead = { sessionToken = "session-2" }
        positions.syncNow(USER)

        assertEquals(listOf("session-1"), rejected)
        assertEquals(250.0, positions.store(USER).pending(FILE)?.seconds)

        remote.readFailure = PlaybackFailure.Putio(
            PutioFailure.Misconfigured(PutioConfigurationException("bad config")),
        )
        positions.syncNow(USER)
        assertEquals(250.0, positions.store(USER).pending(FILE)?.seconds)

        remote.readFailure = PlaybackFailure.Putio(
            PutioFailure.ApiRejected(HTTP_GONE, "Gone", PutioConfigurationException("gone")),
        )
        positions.syncNow(USER)
        assertNull(positions.store(USER).pending(FILE))

        positions.resumePosition(USER, sintel)
        positions.afterWrite(USER, FILE, 300.0, NetworkFailure)
        remote.readFailure = PlaybackFailure.Putio(PutioFailure.AccessDenied(PutioConfigurationException("denied")))
        positions.syncNow(USER)
        assertNull(positions.store(USER).pending(FILE))
    }

    @Test
    fun coldSyncLoadsPositionStoreOffMainThread() = runOnRobolectricMain {
        OfflinePositionStore(preferences, USER).apply {
            remote(FILE, 100.0)
            keep(FILE, 250.0)
        }
        var readOnMain = false
        var writeOnMain = false
        val countingPrefs = object : SharedPreferences by preferences {
            override fun getString(key: String?, defValue: String?): String? {
                if (key == "positions-$USER" && Looper.myLooper() == Looper.getMainLooper()) readOnMain = true
                return preferences.getString(key, defValue)
            }

            override fun edit(): SharedPreferences.Editor {
                val delegate = preferences.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        if (key == "positions-$USER" && Looper.myLooper() == Looper.getMainLooper()) {
                            writeOnMain = true
                        }
                        return delegate.putString(key, value)
                    }
                }
            }
        }
        remote.online = true
        remote.positions[FILE] = 100.0
        val positions = OfflinePlaybackPositions(countingPrefs, { signedIn }, remote, this)

        positions.syncNow(USER)

        assertFalse(readOnMain)
        assertFalse(writeOnMain)
        assertEquals(listOf(FILE to 250.0), remote.writes)
    }

    @Test
    fun coldAfterWriteLoadsPositionStoreOffMainThread() = runOnRobolectricMain {
        OfflinePositionStore(preferences, USER).remote(FILE, 100.0)
        var readOnMain = false
        val countingPrefs = object : SharedPreferences by preferences {
            override fun getString(key: String?, defValue: String?): String? {
                if (key == "positions-$USER" && Looper.myLooper() == Looper.getMainLooper()) readOnMain = true
                return preferences.getString(key, defValue)
            }
        }
        remote.online = false
        val positions = OfflinePlaybackPositions(countingPrefs, { signedIn }, remote, this)

        positions.afterWrite(USER, FILE, 260.0, NetworkFailure)
        assertEquals(260.0, positions.resumePosition(USER, sintel), 0.0)

        assertFalse(readOnMain)
        assertEquals(260.0, positions.store(USER).pending(FILE)?.seconds)
    }

    private fun runOnRobolectricMain(block: suspend CoroutineScope.() -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val finished = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        scope.async { block() }.invokeOnCompletion { cause ->
            if (cause != null) failure.compareAndSet(null, cause)
            finished.set(true)
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAIN_WAIT_SECONDS)
            while (!finished.get()) {
                if (System.nanoTime() >= deadline) {
                    throw AssertionError("Timed out waiting for Robolectric main coroutine")
                }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(MAIN_POLL_MS)
            }
            failure.get()?.let { throw it }
        } finally {
            scope.cancel()
        }
    }

    /** Opened offline, then played to 250 s while put.io could not take it. */
    private suspend fun TestScope.playedOffline(): OfflinePlaybackPositions {
        remote.online = false
        val positions = positions(backgroundScope)
        positions.resumePosition(USER, sintel)
        positions.afterWrite(USER, FILE, 250.0, NetworkFailure)
        return positions
    }

    private fun positions(scope: CoroutineScope) =
        OfflinePlaybackPositions(preferences, { signedIn }, remote, scope) { fileId, seconds ->
            synced += fileId to seconds
        }

    private companion object {
        const val USER = 7L
        const val OTHER = 8L
        const val FILE = 10L
        const val HTTP_NOT_FOUND = 404
        const val HTTP_GONE = 410
        const val MAIN_WAIT_SECONDS = 10L
        const val MAIN_POLL_MS = 10L
        val AuthenticationFailure = PlaybackFailure.Putio(
            PutioFailure.AuthenticationRequired(PutioConfigurationException("expired")),
        )
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
    var readFailure: PlaybackFailure? = null
    /** Runs after a read has taken its value, as a concurrent request would. */
    var afterRead: () -> Unit = {}
    var duringWrite: () -> Unit = {}
    /** Holds the next write until completed, as a slow request would. */
    var writeGate: CompletableDeferred<Unit>? = null
    val positions = mutableMapOf<Long, Double>()
    val writes = mutableListOf<Pair<Long, Double>>()
    var reads = 0
        private set

    override suspend fun read(fileId: Long): PlaybackRepositoryResult<Double> {
        reads += 1
        val failure = readFailure ?: offlineFailure.takeUnless { online }
        val value = positions[fileId] ?: 0.0
        afterRead()
        afterRead = {}
        return failure?.let { PlaybackRepositoryResult.Failure(it) } ?: PlaybackRepositoryResult.Success(value)
    }

    override suspend fun write(fileId: Long, seconds: Double): PlaybackRepositoryResult<Unit> {
        writeGate?.also { writeGate = null }?.await()
        duringWrite()
        duringWrite = {}
        if (online) positions[fileId] = seconds
        if (online && !landButFail) writes += fileId to seconds
        return if (online && !landButFail) PlaybackRepositoryResult.Success(Unit) else offline()
    }

    override suspend fun resumeEnabled(): PlaybackRepositoryResult<Boolean> =
        if (online) PlaybackRepositoryResult.Success(resume) else offline()

    private val offlineFailure = PlaybackFailure.Putio(PutioFailure.NetworkUnavailable(IOException("offline")))

    private fun offline() = PlaybackRepositoryResult.Failure(offlineFailure)
}
