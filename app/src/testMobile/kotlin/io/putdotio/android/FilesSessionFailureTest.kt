package io.putdotio.android

import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.authoritativeSessionFailure
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.history.HistoryClearing
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryState
import io.putdotio.android.history.authoritativeSessionFailure
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FilesSessionFailureTest {
    @Test
    fun `authoritative failure in a hidden parent still expires the session`() {
        val failure = FilesFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val state = browserStateWithParentPagingFailure(failure)

        assertSame(failure, state.authoritativeSessionFailure())
    }

    @Test
    fun `non-auth failure in a hidden parent does not expire the session`() {
        val failure = FilesFailure.Misconfigured(PutioConfigurationException("missing client"))
        val state = browserStateWithParentPagingFailure(failure)

        assertNull(state.authoritativeSessionFailure())
    }

    @Test
    fun `access denied in a hidden parent preserves the session`() {
        val failure = FilesFailure.AccessDenied(PutioConfigurationException("forbidden"))
        val state = browserStateWithParentPagingFailure(failure)

        assertNull(state.authoritativeSessionFailure())
    }

    @Test
    fun `child failure does not mask an authoritative parent failure`() {
        val authFailure = FilesFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val childFailure = FilesFailure.Misconfigured(PutioConfigurationException("missing client"))
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
        val failure = FilesFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val state = browserStateWithOperationFailure(failure)

        assertSame(failure, state.authoritativeSessionFailure())
    }

    @Test
    fun `non-auth failure in a folder operation preserves the session`() {
        val failure = FilesFailure.Misconfigured(PutioConfigurationException("missing client"))
        val state = browserStateWithOperationFailure(failure)

        assertNull(state.authoritativeSessionFailure())
    }

    private fun browserStateWithOperationFailure(failure: FilesFailure): FilesBrowserState =
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

    @Test
    fun `non-auth operation failure does not mask authoritative paging failure`() {
        val authFailure = FilesFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val operationFailure = FilesFailure.Misconfigured(PutioConfigurationException("missing client"))
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

    @Test
    fun `history paging failure does not mask an authoritative clear failure`() {
        val pagingFailure = FilesFailure.Misconfigured(PutioConfigurationException("missing client"))
        val authFailure = FilesFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val state =
            HistoryState(
                content =
                    HistoryContent.Ready(
                        items =
                            listOf(
                                HistoryItem(
                                    id = HistoryEventId(1L),
                                    createdAt = "2026-08-30T00:00:00Z",
                                    kind = HistoryEventKind.File(HistoryFileId(2L), "file.mkv"),
                                ),
                            ),
                        paging = HistoryPaging.Failed(HistoryEventId(1L), pagingFailure),
                    ),
                clearing = HistoryClearing.Failed(authFailure),
            )

        assertSame(authFailure, state.authoritativeSessionFailure())
    }

    @Test
    fun `history preserves an authoritative failure while disabled`() {
        val authFailure = FilesFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val state =
            HistoryState(
                content = HistoryContent.Disabled,
                authoritativeFailure = authFailure,
            )

        assertSame(authFailure, state.authoritativeSessionFailure())
    }

    private fun browserStateWithParentPagingFailure(failure: FilesFailure): FilesBrowserState =
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
