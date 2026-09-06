package io.putdotio.android.history

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryControllerTest {
    @Test
    fun disabledControllerNeverCallsRepository() = runBlocking {
        var calls = 0
        val controller = HistoryController(repository { calls += 1 }, historyEnabled = false, parentScope = this)
        try {
            assertEquals(HistoryContent.Disabled, controller.state.value.content)
            assertEquals(0, calls)
            assertFalse(controller.dispatch(HistoryEvent.LoadNextPage))
        } finally {
            controller.close()
        }
    }

    @Test
    fun enablingDisabledControllerLoadsAndDisablingDropsLoadedHistory() = runBlocking {
        var calls = 0
        val controller = HistoryController(repository { calls += 1 }, historyEnabled = false, parentScope = this)
        try {
            assertTrue(controller.dispatch(HistoryEvent.SetEnabled(true)))
            controller.awaitState { it.content == HistoryContent.Empty }
            assertEquals(1, calls)

            assertTrue(controller.dispatch(HistoryEvent.SetEnabled(false)))
            assertEquals(HistoryContent.Disabled, controller.state.value.content)
        } finally {
            controller.close()
        }
    }

    @Test
    fun controllerLoadsClearsOnlyAfterConfirmationAndPublishesNavigation() = runBlocking {
        var clears = 0
        var loads = 0
        val repository = object : HistoryRepository {
            override suspend fun load(before: HistoryEventId?): HistoryRepositoryResult<HistoryPage> {
                loads += 1
                val items = if (loads == 1) listOf(item(2L)) else emptyList()
                return HistoryRepositoryResult.Success(HistoryPage(items, false))
            }
            override suspend fun clear(): HistoryRepositoryResult<Unit> {
                clears += 1
                return HistoryRepositoryResult.Success(Unit)
            }
        }
        val controller = HistoryController(repository, historyEnabled = true, parentScope = this)
        try {
            controller.awaitState { it.content is HistoryContent.Ready }
            assertFalse(controller.dispatch(HistoryEvent.ConfirmClear))
            assertEquals(0, clears)
            assertTrue(controller.dispatch(HistoryEvent.RequestClear))
            assertTrue(controller.dispatch(HistoryEvent.ConfirmClear))
            controller.awaitState { it.content == HistoryContent.Empty }
            assertEquals(1, clears)
            assertEquals(2, loads)

            val navigation = async(start = CoroutineStart.UNDISPATCHED) { controller.navigation.first() }
            controller.dispatch(HistoryEvent.OpenFile(HistoryFileId(22L)))
            assertEquals(HistoryFileId(22L), withTimeout(2_000L) { navigation.await() }.fileId)
        } finally {
            controller.close()
        }
    }

    private fun repository(onLoad: () -> Unit) = object : HistoryRepository {
        override suspend fun load(before: HistoryEventId?): HistoryRepositoryResult<HistoryPage> {
            onLoad()
            return HistoryRepositoryResult.Success(HistoryPage(emptyList(), false))
        }
        override suspend fun clear() = HistoryRepositoryResult.Success(Unit)
    }

    private suspend fun HistoryController.awaitState(predicate: (HistoryState) -> Boolean) =
        withTimeout(2_000L) { state.first(predicate) }

    private fun item(id: Long) =
        HistoryItem(HistoryEventId(id), "2026-08-30T00:00:00Z", HistoryEventKind.File(HistoryFileId(22L), "file"))
}
