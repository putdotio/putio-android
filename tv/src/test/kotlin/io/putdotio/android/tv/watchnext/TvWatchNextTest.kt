package io.putdotio.android.tv.watchnext

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.auth.TvLinkPhase
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvWatchNextTest {
    private val store = FakeWatchNextStore()
    private val auth = MutableStateFlow<TvAuthState>(signedIn(userId = 1L, session = 1L))
    private val quietSignOuts = MutableStateFlow(0)
    private var now = 1_000L

    @Test
    fun `a position saved mid video publishes one card that later saves move`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = watchNext().recorder(1L, TvAuthSessionId(1L))

        recorder.positionSaved(video(7L), 120.0)
        now = 2_000L
        recorder.positionSaved(video(7L), 240.0)

        val card = store.rows.single()
        assertEquals(TvWatchNextOwner(1L, 7L), card.owner)
        assertEquals(240_000L, card.program.positionMillis)
        assertEquals(600_000L, card.program.durationMillis)
        assertEquals("film-7.mp4", card.program.title)
        assertEquals("https://api.put.io/screenshots/7.jpg", card.program.posterUrl)
        assertEquals(2_000L, card.engagedAtMillis)
    }

    @Test
    fun `finishing a video or marking it unwatched removes its card`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = watchNext().recorder(1L, TvAuthSessionId(1L))
        recorder.positionSaved(video(7L), 120.0)
        recorder.positionSaved(video(8L), 120.0)

        recorder.positionSaved(video(7L), 590.0)
        assertEquals(listOf(8L), store.rows.map { it.program.fileId })

        recorder.positionSaved(video(8L), 0.0)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `signing out removes every card and a late write from that session adds none`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorder = watchNext().recorder(1L, TvAuthSessionId(1L))
            recorder.positionSaved(video(7L), 120.0)

            auth.value = TvAuthState.SigningOut
            assertTrue(store.rows.isEmpty())
            auth.value = TvAuthState.Linking(TvLinkPhase.RequestingCode)
            recorder.positionSaved(video(8L), 120.0)
            assertTrue("A write after sign-out is dropped", store.rows.isEmpty())

            auth.value = signedIn(userId = 1L, session = 2L)
            recorder.positionSaved(video(9L), 120.0)
            assertTrue("The old session's recorder stays closed", store.rows.isEmpty())
        }

    @Test
    fun `a rejected session that falls back to a code removes every card`() = runTest(UnconfinedTestDispatcher()) {
        watchNext().recorder(1L, TvAuthSessionId(1L)).positionSaved(video(7L), 120.0)

        auth.value = TvAuthState.Linking(TvLinkPhase.RequestingCode, sessionExpired = true)

        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `a session put io rejected with no screen removes every card`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = watchNext().recorder(1L, TvAuthSessionId(1L))
        recorder.positionSaved(video(7L), 120.0)

        // A quiet sign-out lands where every process starts; only the count tells them apart.
        auth.value = TvAuthState.Initializing
        assertEquals(1, store.rows.size)
        quietSignOuts.value = 1

        assertTrue(store.rows.isEmpty())
        recorder.positionSaved(video(8L), 120.0)
        assertTrue("A late write from that session adds none", store.rows.isEmpty())
    }

    @Test
    fun `cards are reconciled once per process and user`() = runTest(UnconfinedTestDispatcher()) {
        val watchNext = watchNext()
        watchNext.recorder(1L, TvAuthSessionId(1L)).positionSaved(video(7L), 120.0)
        val reads = mutableListOf<Long>()
        val resolve: suspend (FilesItemId) -> PutioResult<FilesItem> = {
            reads += it.value
            PutioResult.Success(file(it.value, startFrom = 120.0))
        }

        watchNext.recorder(1L, TvAuthSessionId(1L)).reconcile(resolve)
        auth.value = signedIn(userId = 1L, session = 2L)
        watchNext.recorder(1L, TvAuthSessionId(2L)).reconcile(resolve)

        assertEquals("A later session of the same user reads nothing", listOf(7L), reads)
    }

    @Test
    fun `signing in removes another user's cards and keeps the user's own`() = runTest(UnconfinedTestDispatcher()) {
        auth.value = TvAuthState.Initializing
        store.rows += stored(rowId = 1L, owner = TvWatchNextOwner(2L, 7L))
        store.rows += stored(rowId = 2L, owner = TvWatchNextOwner(1L, 8L))
        store.rows += stored(rowId = 3L, owner = null)
        watchNext()

        auth.value = TvAuthState.RestoringSession
        assertEquals("Nothing is known until the session is", 3, store.rows.size)
        auth.value = signedIn(userId = 1L, session = 1L)

        assertEquals(listOf(2L), store.rows.map { it.rowId })
    }

    @Test
    fun `a card the viewer removed from the row is updated in place, never inserted again`() =
        runTest(UnconfinedTestDispatcher()) {
            store.rows += stored(rowId = 5L, owner = TvWatchNextOwner(1L, 7L)).copy(browsable = false)
            val recorder = watchNext().recorder(1L, TvAuthSessionId(1L))

            recorder.positionSaved(video(7L), 300.0)

            val card = store.rows.single()
            assertEquals(5L, card.rowId)
            assertEquals(false, card.browsable)
            assertEquals(300_000L, card.program.positionMillis)
        }

    @Test
    fun `the least recently watched card makes room past twenty`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = watchNext().recorder(1L, TvAuthSessionId(1L))
        (1L..20L).forEach { id ->
            now = id
            recorder.positionSaved(video(id), 60.0)
        }

        now = 21L
        recorder.positionSaved(video(21L), 60.0)

        assertEquals((2L..21L).toList(), store.rows.map { it.program.fileId })
    }

    @Test
    fun `reconciling drops gone and finished files, moves cards watched elsewhere, keeps the rest`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorder = watchNext().recorder(1L, TvAuthSessionId(1L))
            listOf(7L, 8L, 9L, 10L, 11L).forEach { recorder.positionSaved(video(it), 120.0) }
            val engaged = store.rows.associate { it.program.fileId to it.engagedAtMillis }
            now = 99_000L
            val responses = mapOf(
                7L to PutioResult.Failure(notFound()),
                8L to PutioResult.Success(file(8L, startFrom = 595.0)),
                9L to PutioResult.Success(file(9L, startFrom = 300.0)),
                10L to PutioResult.Success(file(10L, startFrom = 120.0)),
                11L to PutioResult.Failure(PutioFailure.NetworkUnavailable(IllegalStateException("offline"))),
            )

            val verdict = recorder.reconcile { id -> responses.getValue(id.value) }

            assertNull(verdict)
            assertEquals(listOf(9L, 10L, 11L), store.rows.map { it.program.fileId })
            val moved = store.rows.first { it.program.fileId == 9L }
            assertEquals(300_000L, moved.program.positionMillis)
            assertEquals("Moving a card is not engaging with it", engaged[9L], moved.engagedAtMillis)
            assertEquals("An unchanged card is not written", 0, store.updates.count { it == 10L })
        }

    @Test
    fun `a 401 while reconciling stops the pass and is returned as the session's verdict`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorder = watchNext().recorder(1L, TvAuthSessionId(1L))
            recorder.positionSaved(video(7L), 120.0)
            val rejected = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))

            assertEquals(rejected, recorder.reconcile { PutioResult.Failure(rejected) })
            assertEquals(1, store.rows.size)
        }

    @Test
    fun `a file the app could not open from its card leaves the row`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = watchNext().recorder(1L, TvAuthSessionId(1L))
        recorder.positionSaved(video(7L), 120.0)

        recorder.fileGone(7L)

        assertTrue(store.rows.isEmpty())
    }

    private fun TestScope.watchNext() =
        TvWatchNext(store, auth, quietSignOuts, backgroundScope, clock = { now }).also { runCurrent() }

    private fun video(id: Long) = TvWatchNextMedia(
        fileId = id,
        title = "film-$id.mp4",
        durationSeconds = 600.0,
        posterUrl = "https://api.put.io/screenshots/$id.jpg",
        isVideo = true,
    )

    private fun file(id: Long, startFrom: Double) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesItemId(0L),
        name = "film-$id.mp4",
        type = PutioFileType.VIDEO,
        sizeBytes = 1L,
        createdAt = "2026-10-04T10:00:00",
        playback = FilesPlaybackProgress(startFrom, null),
        screenshotUrl = "https://api.put.io/screenshots/$id.jpg",
    )

    private fun stored(rowId: Long, owner: TvWatchNextOwner?) = FakeWatchNextStore.Row(
        rowId = rowId,
        owner = owner,
        program = TvWatchNextProgram(owner?.fileId ?: 0L, "old", null, 60_000L, 600_000L),
        engagedAtMillis = 0L,
    )

    private fun notFound() = PutioFailure.ApiRejected(404, "NotFound", PutioConfigurationException("404"))

    private fun signedIn(userId: Long, session: Long) = TvAuthState.SignedIn(
        TvAccount(userId = userId, username = "u", email = "u@example.com", historyEnabled = true),
        TvAuthSessionId(session),
    )
}

/** The provider's rows for this app, with the `browsable` flag the launcher clears on removal. */
private class FakeWatchNextStore : TvWatchNextStore {
    data class Row(
        val rowId: Long,
        val owner: TvWatchNextOwner?,
        val program: TvWatchNextProgram,
        val engagedAtMillis: Long,
        val browsable: Boolean = true,
    )

    val rows = mutableListOf<Row>()
    val updates = mutableListOf<Long>()
    private var nextId = 100L

    override suspend fun programs() =
        rows.map { TvStoredWatchNextProgram(it.rowId, it.owner, it.program, it.engagedAtMillis) }

    override suspend fun insert(owner: TvWatchNextOwner, program: TvWatchNextProgram, engagedAtMillis: Long) {
        rows += Row(nextId++, owner, program, engagedAtMillis)
    }

    override suspend fun update(
        rowId: Long,
        owner: TvWatchNextOwner,
        program: TvWatchNextProgram,
        engagedAtMillis: Long,
    ) {
        updates += program.fileId
        val index = rows.indexOfFirst { it.rowId == rowId }
        rows[index] = rows[index].copy(owner = owner, program = program, engagedAtMillis = engagedAtMillis)
    }

    override suspend fun delete(rowId: Long) {
        rows.removeAll { it.rowId == rowId }
    }
}
