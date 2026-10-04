package io.putdotio.android.sharing

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.putioRefusal
import io.putdotio.android.toPutioFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PublicLinksControllerTest {
    private val video = FilesItemId(7L)
    private val folder = FilesItemId(8L)

    @Test
    fun createWaitsForTheAccountsLinksSoTheViewerSeesWhatExists() = runTest {
        val repository = FakePublicLinksRepository(listOf(publicLink(id = 3L, fileId = 7L)))
        val controller = controller(repository)

        assertFalse(controller.dispatch(PublicLinksEvent.Create(video)))
        assertTrue(controller.dispatch(PublicLinksEvent.Load))
        assertFalse(controller.dispatch(PublicLinksEvent.Create(video)))
        runCurrent()

        assertEquals(listOf(PublicLinkId(3L)), controller.state.value.linksFor(video)?.map { it.id })
        assertEquals(emptyList<PublicLink>(), controller.state.value.linksFor(folder))
        assertEquals(emptyList<FilesItemId>(), repository.created)
    }

    @Test
    fun aCreatedLinkJoinsTheItemsLinksNewestFirst() = runTest {
        val repository = FakePublicLinksRepository(listOf(publicLink(id = 3L, fileId = 7L)))
        val controller = loaded(repository)

        assertTrue(controller.dispatch(PublicLinksEvent.Create(video)))
        assertEquals(PublicLinksMutation.Creating(video), controller.state.value.mutation)
        assertFalse(controller.dispatch(PublicLinksEvent.Create(video)))
        runCurrent()

        val state = controller.state.value
        assertEquals(listOf(4L, 3L), state.linksFor(video)?.map { it.id.value })
        assertEquals(PublicLinksOutcome.Created(repository.links.last()), state.outcome)
        assertNull(state.mutation)
        assertEquals(listOf(video), repository.created)
        assertEquals(1, repository.listCount)
    }

    @Test
    fun aRefusedCreateNamesWebsReasonAndKeepsTheLinks() = runTest {
        val repository = FakePublicLinksRepository(listOf(publicLink(id = 3L, fileId = 7L)))
        repository.onCreate = { PutioResult.Failure(refusal(403, "PUBLIC_SHARE_DAILY_TOTAL_LINK_COUNT_EXCEEDED")) }
        val controller = loaded(repository)

        controller.dispatch(PublicLinksEvent.Create(video))
        runCurrent()

        val outcome = controller.state.value.outcome as PublicLinksOutcome.CreateFailed
        assertEquals(video, outcome.fileId)
        assertTrue(outcome.failure is PutioFailure.AccessDenied)
        assertEquals(PublicLinkRefusal.DAILY_LIMIT, outcome.refusal)
        assertEquals(listOf(3L), controller.state.value.linksFor(video)?.map { it.id.value })
        assertTrue(controller.state.value.canCreate)
    }

    @Test
    fun revokeRemovesOnlyThatLinkOncePutioConfirms() = runTest {
        val kept = publicLink(id = 2L, fileId = 7L)
        val revoked = publicLink(id = 3L, fileId = 7L)
        val other = publicLink(id = 4L, fileId = 8L)
        val repository = FakePublicLinksRepository(listOf(kept, revoked, other))
        val pending = CompletableDeferred<PutioResult<Unit>>()
        repository.onRevoke = { pending.await() }
        val controller = loaded(repository)

        assertTrue(controller.dispatch(PublicLinksEvent.Revoke(revoked.id)))
        runCurrent()
        assertEquals(listOf(revoked.id, kept.id), controller.state.value.linksFor(video)?.map { it.id })
        assertFalse(controller.state.value.canCreate)

        pending.complete(PutioResult.Success(Unit))
        runCurrent()

        val state = controller.state.value
        assertEquals(listOf(kept.id), state.linksFor(video)?.map { it.id })
        assertEquals(listOf(other.id), state.linksFor(folder)?.map { it.id })
        assertEquals(PublicLinksOutcome.Revoked(revoked), state.outcome)
        assertEquals(listOf(revoked.id), repository.revoked)
    }

    @Test
    fun aFailedRevokeKeepsTheLinkWithPutiosReason() = runTest {
        val link = publicLink(id = 3L, fileId = 7L)
        val repository = FakePublicLinksRepository(listOf(link))
        val failure = PutioFailure.ApiRejected(400, "BadRequest", refusalException(400, "BadRequest"))
        repository.onRevoke = { PutioResult.Failure(failure) }
        val controller = loaded(repository)

        controller.dispatch(PublicLinksEvent.Revoke(link.id))
        runCurrent()

        assertEquals(listOf(link), controller.state.value.linksFor(video))
        assertEquals(PublicLinksOutcome.RevokeFailed(link, failure), controller.state.value.outcome)
        assertTrue(controller.state.value.canRevoke)
    }

    @Test
    fun aFailedRevokeReadsTheLinksAgainSoOneAlreadyGoneLeaves() = runTest {
        val gone = publicLink(id = 3L, fileId = 7L)
        val kept = publicLink(id = 2L, fileId = 7L)
        val repository = FakePublicLinksRepository(listOf(kept, gone))
        val failure = PutioFailure.ApiRejected(404, "NotFound", refusalException(404, "NotFound"))
        repository.onRevoke = { PutioResult.Failure(failure) }
        val controller = loaded(repository)
        // Revoked on web, or expired, after this list was read.
        repository.links.remove(gone)

        controller.dispatch(PublicLinksEvent.Revoke(gone.id))
        runCurrent()

        assertEquals(listOf(kept), controller.state.value.linksFor(video))
        assertEquals(PublicLinksOutcome.RevokeFailed(gone, failure), controller.state.value.outcome)
        assertEquals(2, repository.listCount)
        assertFalse(controller.state.value.refreshing)
    }

    @Test
    fun aFailedReadAfterAFailedRevokeKeepsTheLinksShown() = runTest {
        val link = publicLink(id = 3L, fileId = 7L)
        val repository = FakePublicLinksRepository(listOf(link))
        repository.onRevoke = { PutioResult.Failure(PutioFailure.NetworkUnavailable(IllegalStateException("offline"))) }
        val controller = loaded(repository)
        val pending = CompletableDeferred<PutioResult<List<PublicLink>>>()
        repository.onList = { pending.await() }

        controller.dispatch(PublicLinksEvent.Revoke(link.id))
        runCurrent()
        assertTrue(controller.state.value.refreshing)
        assertFalse(controller.state.value.canCreate)
        assertFalse(controller.dispatch(PublicLinksEvent.Load))

        pending.complete(PutioResult.Failure(PutioFailure.NetworkUnavailable(IllegalStateException("offline"))))
        runCurrent()

        assertEquals(listOf(link), controller.state.value.linksFor(video))
        assertTrue(controller.state.value.outcome is PublicLinksOutcome.RevokeFailed)
        assertTrue(controller.state.value.canRevoke)
    }

    @Test
    fun aRejectedSessionStopsEveryOtherRequest() = runTest {
        val repository = FakePublicLinksRepository()
        repository.onList = { PutioResult.Failure(PutioFailure.AuthenticationRequired(refusalException(401, null))) }
        val controller = controller(repository)

        controller.dispatch(PublicLinksEvent.Load)
        runCurrent()

        assertTrue(controller.state.value.authenticationFailure is PutioFailure.AuthenticationRequired)
        assertFalse(controller.dispatch(PublicLinksEvent.Load))
        assertEquals(1, repository.listCount)
    }

    @Test
    fun readingAgainClearsTheLastOutcome() = runTest {
        val repository = FakePublicLinksRepository()
        val controller = loaded(repository)
        controller.dispatch(PublicLinksEvent.Create(video))
        runCurrent()

        controller.dispatch(PublicLinksEvent.Load)
        runCurrent()

        assertNull(controller.state.value.outcome)
        assertEquals(1, controller.state.value.linksFor(video)?.size)
    }

    @Test
    fun aClosedControllerDropsTheResultOfItsRequest() = runTest {
        val repository = FakePublicLinksRepository()
        val pending = CompletableDeferred<PutioResult<List<PublicLink>>>()
        repository.onList = { pending.await() }
        val controller = controller(repository)
        controller.dispatch(PublicLinksEvent.Load)
        runCurrent()

        controller.close()
        pending.complete(PutioResult.Success(listOf(publicLink())))
        runCurrent()

        assertEquals(PublicLinksContent.Loading, controller.state.value.content)
        assertFalse(controller.dispatch(PublicLinksEvent.Load))
    }

    private fun TestScope.controller(repository: PublicLinksRepository) =
        PublicLinksController(repository, backgroundScope)

    private fun TestScope.loaded(repository: PublicLinksRepository): PublicLinksController {
        val controller = controller(repository)
        controller.dispatch(PublicLinksEvent.Load)
        runCurrent()
        check(controller.state.value.content is PublicLinksContent.Ready)
        return controller
    }

    private fun refusal(status: Int, type: String): PutioFailure = refusalException(status, type).toPutioFailure()

    private fun refusalException(status: Int, type: String?) = putioRefusal(
        status,
        """{"error_type":${type?.let { "\"$it\"" } ?: "null"},"error_message":"No",""" +
            """"status":"ERROR","status_code":$status}""",
    )
}
