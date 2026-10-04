package io.putdotio.android.history

import io.putdotio.android.files.FilesFailure
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertSame
import org.junit.Test

class HistorySessionFailureTest {
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
}
