package io.putdotio.android.design

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltOffMainTest {
    private val sources = MutableSharedFlow<List<Int>>()
    private val results = mutableListOf<Built<List<Int>, String>>()

    @Test
    fun aNewerSourceCancelsTheBuildStillRunning() = runTest {
        val first = listOf(1)
        val cancelled = CompletableDeferred<List<Int>>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            sources.buildLatest { source ->
                if (source == first) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(source)
                    }
                }
                source.joinToString()
            }.collect { results += it }
        }
        sources.emit(first)
        sources.emit(listOf(1, 2))

        assertEquals(first, cancelled.await())
        assertEquals(listOf("1, 2"), results.map { it.value })
        collecting.cancel()
    }

    @Test
    fun anEqualSourceKeepsTheBuiltValueAndCoversTheNewSource() = runTest {
        var builds = 0
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            sources.buildLatest { source -> builds++; source.joinToString() }.collect { results += it }
        }
        val first = listOf(1, 2)
        val refreshed = ArrayList(first)
        sources.emit(first)
        sources.emit(refreshed)

        assertEquals(1, builds)
        assertSame(results[0].value, results[1].value)
        assertSame(first, results[1].source)
        assertTrue(results[1].isBuiltFrom(refreshed))
        assertFalse(results[1].isBuiltFrom(first))
        collecting.cancel()
    }
}
