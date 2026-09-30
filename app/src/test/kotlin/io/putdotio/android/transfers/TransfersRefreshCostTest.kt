package io.putdotio.android.transfers

import io.putdotio.android.files.FilesRepositoryResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TransfersRefreshCostTest {
    @Test
    fun pollingAFewRowsReadsOnlyThoseRowsAtAnyHistoryDepth() = runBlocking {
        val cases =
            listOf(
                Poll(historySize = 10_000, active = listOf(0)),
                Poll(historySize = 10_000, active = listOf(9_500)),
                Poll(historySize = 10_000, missing = 1),
                Poll(historySize = 10_000, active = listOf(0, 1, 2, 9_500)),
                Poll(historySize = 50_000, active = listOf(20_000)),
                Poll(historySize = 50_000, active = listOf(0), missing = 1),
            )
        for (case in cases) {
            val cost = case.run()

            assertEquals("$case requests", case.active.size + case.missing, cost.requests)
            assertEquals("$case rows", case.active.size, cost.rowsDecoded)
            assertEquals("$case found", case.active.size, cost.found)
            assertEquals("$case missing", case.missing, cost.missing)
        }
    }

    @Test
    fun pollingManyRecentRowsReadsOneListPage() = runBlocking {
        val cost = Poll(historySize = 50_000, active = (0 until 11).toList()).run()

        assertEquals(1, cost.requests)
        assertEquals(1_000, cost.rowsDecoded)
        assertEquals(11, cost.found)
    }

    private data class Poll(
        val historySize: Int,
        val active: List<Int> = emptyList(),
        val missing: Int = 0,
    ) {
        suspend fun run(): Cost {
            val backend = FakeTransfersBackend(historySize, active.toSet())
            val ids =
                active.map { TransferId(backend.history[it].id) } +
                    (1..missing).map { TransferId(-it.toLong()) }
            val result = (backend.repository().refresh(ids) as FilesRepositoryResult.Success).value
            return Cost(backend.requests, backend.rowsDecoded, result.items.size, result.missingIds.size)
        }
    }

    private data class Cost(val requests: Int, val rowsDecoded: Int, val found: Int, val missing: Int)
}
