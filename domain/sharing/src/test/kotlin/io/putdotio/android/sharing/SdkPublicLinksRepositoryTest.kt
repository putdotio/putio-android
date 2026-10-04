package io.putdotio.android.sharing

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.putioRefusal
import io.putdotio.sdk.OkResponse
import io.putdotio.sdk.errors.PutioKnownErrorContract
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.sharing.PublicShare
import io.putdotio.sdk.sharing.PublicShareFile
import io.putdotio.sdk.sharing.PublicShareOwner
import io.putdotio.sdk.sharing.PublicShareToken
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkPublicLinksRepositoryTest {
    @Test
    fun aShareBecomesWebsExclusiveAccessAddressThatNeverPrints() = runBlocking {
        val repository = repository(list = { listOf(share(id = 5L, token = "aB3-x_9")) })

        val link = (repository.list() as PutioResult.Success).value.single()

        assertEquals("https://app.put.io/exclusive-access/aB3-x_9", link.url.value)
        assertEquals(PublicLinkId(5L), link.id)
        assertEquals(FilesItemId(70L), link.fileId)
        assertEquals("Harbor été.mp4", link.fileName)
        assertEquals(PutioFileType.VIDEO, link.fileType)
        // put.io sends UTC without an offset.
        assertEquals(Instant.parse("2026-10-07T19:36:01.325208Z"), link.expiresAt)
        assertFalse(link.toString().contains("aB3-x_9"))
    }

    @Test
    fun aTokenOutsideThePathAlphabetIsEncoded() {
        assertEquals(
            "https://app.put.io/exclusive-access/a%2Fb%3F%C3%A9",
            PublicLinkUrl.of("a/b?é").value,
        )
    }

    @Test
    fun aShareWithoutATokenIsAnInvalidResponse() = runBlocking {
        val repository = repository(list = { listOf(share(id = 5L, token = " ")) })

        val failure = (repository.list() as PutioResult.Failure).failure

        assertTrue(failure is PutioFailure.InvalidResponse)
    }

    @Test
    fun createAsksForTheItemAndRootNeverReachesPutio() = runBlocking {
        val asked = mutableListOf<Long>()
        val repository = repository(create = { id -> asked += id; share(id = 9L, fileId = id) })

        val created = (repository.create(FilesItemId(70L)) as PutioResult.Success).value
        val root = repository.create(FilesItemId(0L))

        assertEquals(PublicLinkId(9L), created.id)
        assertEquals(listOf(70L), asked)
        assertTrue((root as PutioResult.Failure).failure is PutioFailure.Unexpected)
    }

    @Test
    fun aPlanRefusalWrappedByTheSdkKeepsItsErrorType() = runBlocking {
        val api = putioRefusal(
            403,
            """{"error_type":"PUBLIC_SHARE_NOT_ALLOWED_PLAN","error_message":"No",""" +
                """"status":"ERROR","status_code":403}""",
        )
        val wrapped = PutioOperationException(
            domain = "sharing",
            operation = "createPublicShare",
            contract = PutioKnownErrorContract("PUBLIC_SHARE_NOT_ALLOWED_PLAN", 403),
            reason = PutioOperationErrorReason.ErrorType("PUBLIC_SHARE_NOT_ALLOWED_PLAN"),
            underlyingError = api,
        )
        val repository = repository(create = { throw wrapped })

        val failure = (repository.create(FilesItemId(70L)) as PutioResult.Failure).failure

        assertTrue(failure is PutioFailure.AccessDenied)
        assertEquals(PublicLinkRefusal.PLAN_NOT_ALLOWED, failure.publicLinkRefusal)
    }

    @Test
    fun revokeDeletesThatLink() = runBlocking {
        val deleted = mutableListOf<Long>()
        val repository = repository(delete = { id -> deleted += id; OkResponse("OK") })

        assertEquals(PutioResult.Success(Unit), repository.revoke(PublicLinkId(5L)))
        assertEquals(listOf(5L), deleted)
    }

    private fun repository(
        list: suspend () -> List<PublicShare> = { emptyList() },
        create: suspend (Long) -> PublicShare = { error("Unexpected create") },
        delete: suspend (Long) -> OkResponse = { error("Unexpected delete") },
    ) = SdkPublicLinksRepository(list, create, delete)

    private fun share(id: Long, fileId: Long = 70L, token: String = "token") = PublicShare(
        id = id,
        token = PublicShareToken(token),
        pushToken = PublicShareToken("push"),
        createdAt = "2026-10-04T19:36:01.325248",
        expirationDate = "2026-10-07T19:36:01.325208",
        owner = PublicShareOwner("devs"),
        userFile = PublicShareFile(fileId, "Harbor été.mp4", PutioFileType.VIDEO),
    )
}
