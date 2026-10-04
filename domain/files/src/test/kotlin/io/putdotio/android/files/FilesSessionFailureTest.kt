package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FilesSessionFailureTest {
    @Test
    fun `authoritative failure in a hidden parent still expires the session`() {
        val failure = PutioFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val state = browserStateWithParentPagingFailure(failure)

        assertSame(failure, state.authoritativeSessionFailure())
    }

    @Test
    fun `non-auth failure in a hidden parent does not expire the session`() {
        val failure = PutioFailure.Misconfigured(PutioConfigurationException("missing client"))
        val state = browserStateWithParentPagingFailure(failure)

        assertNull(state.authoritativeSessionFailure())
    }

    @Test
    fun `access denied in a hidden parent preserves the session`() {
        val failure = PutioFailure.AccessDenied(PutioConfigurationException("forbidden"))
        val state = browserStateWithParentPagingFailure(failure)

        assertNull(state.authoritativeSessionFailure())
    }

    @Test
    fun `child failure does not mask an authoritative parent failure`() {
        val authFailure = PutioFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val childFailure = PutioFailure.Misconfigured(PutioConfigurationException("missing client"))
        val state = browserStateWithParentPagingFailure(authFailure).let { browserState ->
            browserState.copy(
                stack = browserState.stack.dropLast(1) + browserState.current.copy(
                    content = FilesContent.Failed(childFailure),
                ),
            )
        }

        assertSame(authFailure, state.authoritativeSessionFailure())
    }

    @Test
    fun `auth failure in a folder operation expires the session`() {
        val failure = PutioFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val state = browserStateWithOperationFailure(failure)

        assertSame(failure, state.authoritativeSessionFailure())
    }

    @Test
    fun `non-auth failure in a folder operation preserves the session`() {
        val failure = PutioFailure.Misconfigured(PutioConfigurationException("missing client"))
        val state = browserStateWithOperationFailure(failure)

        assertNull(state.authoritativeSessionFailure())
    }

    @Test
    fun `non-auth operation failure does not mask authoritative paging failure`() {
        val authFailure = PutioFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val operationFailure = PutioFailure.Misconfigured(PutioConfigurationException("missing client"))
        val state = FilesBrowserState(
            stack = listOf(
                FilesFolderState(
                    folder = FilesFolder.Root,
                    content = FilesContent.Empty(
                        paging = FilesPaging.Failed(FilesCursor("root-next"), authFailure),
                    ),
                    operation = FilesFolderOperation.Failed(
                        failure = operationFailure,
                        intent = FilesFolderOperationIntent.Refresh,
                        phase = FilesFolderOperationPhase.RELOADING,
                    ),
                ),
            ),
            nextRequestValue = 3L,
        )

        assertSame(authFailure, state.authoritativeSessionFailure())
    }

    private fun browserStateWithOperationFailure(failure: PutioFailure): FilesBrowserState =
        FilesBrowserState(
            stack = listOf(
                FilesFolderState(
                    folder = FilesFolder.Root,
                    content = FilesContent.Empty(paging = FilesPaging.Complete),
                    operation = FilesFolderOperation.Failed(
                        failure = failure,
                        intent = FilesFolderOperationIntent.Refresh,
                        phase = FilesFolderOperationPhase.RELOADING,
                    ),
                ),
            ),
            nextRequestValue = 2L,
        )

    private fun browserStateWithParentPagingFailure(failure: PutioFailure): FilesBrowserState =
        FilesBrowserState(
            stack = listOf(
                FilesFolderState(
                    folder = FilesFolder.Root,
                    content = FilesContent.Empty(
                        paging = FilesPaging.Failed(FilesCursor("root-next"), failure),
                    ),
                ),
                FilesFolderState(
                    folder = FilesFolder(id = FilesItemId(7L), name = "Shows"),
                    content = FilesContent.Loading(FilesRequestId(2L)),
                ),
            ),
            nextRequestValue = 3L,
        )
}
