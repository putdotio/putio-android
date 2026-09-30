package io.putdotio.android.transfers

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.sdk.transfers.Transfer
import io.putdotio.sdk.transfers.TransfersListQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays the deterministic Transfers poll histories on an Android runtime and reports the
 * thread CPU time of one poll: the pre-by-id list walk against the current refresh. The fake
 * SDK decodes pre-encoded JSON per request, so there is no network and no API account.
 */
@RunWith(AndroidJUnit4::class)
class TransfersPollingCpuBenchmark {
    @Test
    fun pollCpuTimeBeforeAndAfter() = runBlocking {
        assumeTrue(
            "Transfers polling benchmark requires opt-in",
            InstrumentationRegistry.getArguments().getString(OPT_IN) == "true",
        )
        val rows =
            CASES.map { case ->
                val backend = FakeTransfersBackend(case.historySize, case.active.toSet())
                val ids =
                    case.active.map { TransferId(backend.history[it].id) } +
                        (1..case.missing).map { TransferId(-it.toLong()) }
                val before = suspend { listWalkRefresh(backend.reads(), ids) }
                val after = suspend { (backend.repository().refresh(ids) as FilesRepositoryResult.Success).value }
                assertEquals(case.label, case.expectBeforeFound, before().items.size)
                assertEquals(case.label, case.active.size, after().items.size)
                repeat(WARMUP_POLLS) {
                    before()
                    after()
                }
                val beforeSamples = mutableListOf<Sample>()
                val afterSamples = mutableListOf<Sample>()
                repeat(MEASURED_POLLS) {
                    beforeSamples += measure(before)
                    afterSamples += measure(after)
                }
                "| ${case.label} | ${beforeSamples.report()} | ${afterSamples.report()} |"
            }
        val table =
            listOf(
                "Median of $MEASURED_POLLS polls after $WARMUP_POLLS warmup; thread CPU ms / wall ms per poll",
                "| History, polled rows | Before (list walk) | After |",
                "| --- | --- | --- |",
            ) + rows
        table.forEach { Log.i(TAG, it) }
    }

    private suspend fun measure(poll: suspend () -> TransfersRowRefresh): Sample {
        val cpuStart = Debug.threadCpuTimeNanos()
        val wallStart = SystemClock.elapsedRealtimeNanos()
        poll()
        val cpu = Debug.threadCpuTimeNanos() - cpuStart
        val wall = SystemClock.elapsedRealtimeNanos() - wallStart
        assertTrue("Thread CPU time is unsupported on this runtime", cpuStart >= 0)
        return Sample(cpu, wall)
    }

    private fun List<Sample>.report(): String =
        "%.2f / %.2f".format(map(Sample::cpuNanos).medianMillis(), map(Sample::wallNanos).medianMillis())

    private fun List<Long>.medianMillis(): Double = sorted()[size / 2] / NANOS_PER_MILLI

    private data class Sample(val cpuNanos: Long, val wallNanos: Long)

    private data class Case(
        val label: String,
        val historySize: Int,
        val active: List<Int> = emptyList(),
        val missing: Int = 0,
        val expectBeforeFound: Int = active.size,
    )

    private companion object {
        const val TAG = "TransfersPollingCpu"
        const val OPT_IN = "putio.transfers.benchmark.enabled"
        const val WARMUP_POLLS = 5
        const val MEASURED_POLLS = 15
        const val NANOS_PER_MILLI = 1_000_000.0
        const val REFRESH_PAGE_SIZE = 1_000

        val CASES =
            listOf(
                Case("10k, 1 recent running", 10_000, active = listOf(0)),
                Case("10k, 1 old running (position 9,500)", 10_000, active = listOf(9_500)),
                Case("10k, 1 deleted", 10_000, missing = 1),
                Case("10k, 3 recent + 1 old", 10_000, active = listOf(0, 1, 2, 9_500)),
                Case("10k, 11 recent (list walk)", 10_000, active = (0 until 11).toList()),
                Case("50k, 1 recent running", 50_000, active = listOf(0)),
                Case(
                    "50k, 1 running past the cap (position 20,000)",
                    50_000,
                    active = listOf(20_000),
                    expectBeforeFound = 0,
                ),
                Case("50k, 1 deleted", 50_000, missing = 1),
            )

        // The refresh before by-id reads, as on main at a706f68: map every row of each 1,000-row
        // page, then keep the requested ids, until all are found or the list ends.
        suspend fun listWalkRefresh(reads: TransfersReadOperations, ids: List<TransferId>): TransfersRowRefresh {
            val requestedIds = ids.distinct()
            val requestedSet = requestedIds.toSet()
            val foundById = mutableMapOf<TransferId, TransferItem>()
            val consumedCursors = mutableSetOf<String>()
            val query = TransfersListQuery(perPage = REFRESH_PAGE_SIZE)
            var cursor: String? = null
            do {
                val response =
                    cursor?.let { reads.continueList(it, query) }
                        ?: reads.list(query)
                response.transfers
                    .asSequence()
                    .map(Transfer::toTransferItem)
                    .filter { it.id in requestedSet }
                    .forEach { foundById[it.id] = it }
                cursor =
                    response.cursor
                        ?.takeIf(String::isNotBlank)
                        ?.takeIf { foundById.size < requestedSet.size }
                check(cursor == null || consumedCursors.add(cursor)) { "Transfers refresh cursor repeated" }
            } while (cursor != null)
            return TransfersRowRefresh(
                items = requestedIds.mapNotNull(foundById::get),
                missingIds = requestedSet - foundById.keys,
            )
        }
    }
}
