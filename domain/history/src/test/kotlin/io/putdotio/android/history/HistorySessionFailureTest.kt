package io.putdotio.android.history

import io.putdotio.android.PutioFailure
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertSame
import org.junit.Test

class HistorySessionFailureTest {
    @Test
    fun `history paging failure does not mask an authoritative clear failure`() {
        val pagingFailure = PutioFailure.Misconfigured(PutioConfigurationException("missing client"))
        val authFailure = PutioFailure.AuthenticationRequired(PutioConfigurationException("rejected"))
        val item = HistoryItem(
            HistoryEventId(1L),
            "2026-08-30T00:00:00Z",
            HistoryEventKind.File(HistoryFileId(2L), "file.mkv"),
        )
        val state = HistoryState(
            content = HistoryContent.Ready(listOf(item), HistoryPaging.Failed(HistoryEventId(1L), pagingFailure)),
            clearing = HistoryClearing.Failed(authFailure),
        )

        assertSame(authFailure, state.authoritativeSessionFailure())
    }
}
