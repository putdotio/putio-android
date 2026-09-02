package io.putdotio.android.history

import io.putdotio.android.files.FilesFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReducerTest {
    @Test
    fun disabledHistoryDoesNotLoad() {
        val start = HistoryReducer.start(historyEnabled = false)

        assertEquals(HistoryContent.Disabled, start.state.content)
        assertNull(start.effect)
    }

    @Test
    fun startsLoadingAndExposesEmptyFailureAndRetryStates() {
        val start = HistoryReducer.start(historyEnabled = true)
        val request = start.effect as HistoryEffect.Load
        assertNull(request.before)
        assertEquals(request.requestId, (start.state.content as HistoryContent.Loading).requestId)

        val empty =
            HistoryReducer.reduce(
                start.state,
                HistoryEvent.LoadSucceeded(request.requestId, HistoryPage(emptyList(), false)),
            )
        assertEquals(HistoryContent.Empty, empty.state.content)

        val failure = FilesFailure.Unexpected(IllegalStateException("offline"))
        val failed = HistoryReducer.reduce(start.state, HistoryEvent.LoadFailed(request.requestId, failure))
        assertEquals(failure, (failed.state.content as HistoryContent.Failed).failure)

        val retry = HistoryReducer.reduce(failed.state, HistoryEvent.Retry)
        assertNull((retry.effect as HistoryEffect.Load).before)
        assertTrue(retry.state.content is HistoryContent.Loading)
    }

    @Test
    fun settingEnabledReloadsWithAMonotonicRequestAndRejectsStaleResults() {
        val start = HistoryReducer.start(historyEnabled = true)
        val firstRequest = start.effect as HistoryEffect.Load
        val disabled = HistoryReducer.reduce(start.state, HistoryEvent.SetEnabled(false))

        assertEquals(HistoryContent.Disabled, disabled.state.content)
        assertEquals(HistoryClearing.Idle, disabled.state.clearing)

        val enabled = HistoryReducer.reduce(disabled.state, HistoryEvent.SetEnabled(true))
        val secondRequest = enabled.effect as HistoryEffect.Load
        assertTrue(secondRequest.requestId.value > firstRequest.requestId.value)
        assertEquals(secondRequest.requestId, (enabled.state.content as HistoryContent.Loading).requestId)

        val stale =
            HistoryReducer.reduce(
                enabled.state,
                HistoryEvent.LoadSucceeded(firstRequest.requestId, HistoryPage(listOf(item(1L)), false)),
            )
        assertFalse(stale.consumed)
        assertEquals(enabled.state, stale.state)

        val loaded =
            HistoryReducer.reduce(
                enabled.state,
                HistoryEvent.LoadSucceeded(secondRequest.requestId, HistoryPage(listOf(item(2L)), false)),
            )
        assertEquals(listOf(2L), (loaded.state.content as HistoryContent.Ready).items.map { it.id.value })
    }

    @Test
    fun settingCurrentHistoryAvailabilityIsANoOp() {
        val disabled = HistoryReducer.start(historyEnabled = false).state
        val enabled = HistoryReducer.start(historyEnabled = true).state

        assertFalse(HistoryReducer.reduce(disabled, HistoryEvent.SetEnabled(false)).consumed)
        assertFalse(HistoryReducer.reduce(enabled, HistoryEvent.SetEnabled(true)).consumed)
    }

    @Test
    fun pagesByLastEventIdDeduplicatesRowsAndStopsCursorCycles() {
        val initial = loaded(listOf(item(9L), item(8L)), hasMore = true)
        val first = HistoryReducer.reduce(initial, HistoryEvent.LoadNextPage)
        val request = first.effect as HistoryEffect.Load
        assertEquals(HistoryEventId(8L), request.before)

        val cycled =
            HistoryReducer.reduce(
                first.state,
                HistoryEvent.LoadSucceeded(request.requestId, HistoryPage(listOf(item(8L)), hasMore = true)),
            )
        val ready = cycled.state.content as HistoryContent.Ready
        assertEquals(listOf(9L, 8L), ready.items.map { it.id.value })
        assertEquals(HistoryPaging.Complete, ready.paging)
        assertFalse(HistoryReducer.reduce(cycled.state, HistoryEvent.LoadNextPage).consumed)
    }

    @Test
    fun pagingFailureKeepsItemsAndRetriesOnlyTheContinuation() {
        val initial = loaded(listOf(item(4L)), hasMore = true)
        val loading = HistoryReducer.reduce(initial, HistoryEvent.LoadNextPage)
        val request = loading.effect as HistoryEffect.Load
        val failure = FilesFailure.Unexpected(IllegalStateException("offline"))
        val failed = HistoryReducer.reduce(loading.state, HistoryEvent.LoadFailed(request.requestId, failure))

        val content = failed.state.content as HistoryContent.Ready
        assertEquals(listOf(4L), content.items.map { it.id.value })
        assertTrue(content.paging is HistoryPaging.Failed)

        val retry = HistoryReducer.reduce(failed.state, HistoryEvent.Retry)
        assertEquals(HistoryEventId(4L), (retry.effect as HistoryEffect.Load).before)
    }

    @Test
    fun clearRequiresConfirmationAndPreservesContentOnFailure() {
        val initial = loaded(listOf(item(1L)), hasMore = false)
        val premature = HistoryReducer.reduce(initial, HistoryEvent.ConfirmClear)
        assertFalse(premature.consumed)
        assertNull(premature.effect)

        val requested = HistoryReducer.reduce(initial, HistoryEvent.RequestClear)
        assertEquals(HistoryClearing.AwaitingConfirmation, requested.state.clearing)
        val confirmed = HistoryReducer.reduce(requested.state, HistoryEvent.ConfirmClear)
        val effect = confirmed.effect as HistoryEffect.Clear
        assertTrue(confirmed.state.clearing is HistoryClearing.Clearing)

        val failure = FilesFailure.Unexpected(IllegalStateException("nope"))
        val failed = HistoryReducer.reduce(confirmed.state, HistoryEvent.ClearFailed(effect.requestId, failure))
        assertTrue(failed.state.content is HistoryContent.Ready)
        assertEquals(failure, (failed.state.clearing as HistoryClearing.Failed).failure)

        val retryRequest = HistoryReducer.reduce(failed.state, HistoryEvent.RequestClear)
        assertFalse(retryRequest.consumed)
        val dismissed = HistoryReducer.reduce(failed.state, HistoryEvent.DismissClear)
        assertEquals(HistoryClearing.Idle, dismissed.state.clearing)
    }

    @Test
    fun successfulClearEmptiesHistoryAndFileEventsEmitNavigation() {
        val initial = loaded(listOf(item(1L)), hasMore = false)
        val requested = HistoryReducer.reduce(initial, HistoryEvent.RequestClear)
        val confirmed = HistoryReducer.reduce(requested.state, HistoryEvent.ConfirmClear)
        val requestId = (confirmed.effect as HistoryEffect.Clear).requestId
        val cleared = HistoryReducer.reduce(confirmed.state, HistoryEvent.ClearSucceeded(requestId))
        assertEquals(HistoryContent.Empty, cleared.state.content)

        val navigation = HistoryReducer.reduce(cleared.state, HistoryEvent.OpenFile(HistoryFileId(42L)))
        assertEquals(HistoryEffect.NavigateToFile(HistoryFileId(42L)), navigation.effect)
    }

    private fun loaded(items: List<HistoryItem>, hasMore: Boolean): HistoryState {
        val start = HistoryReducer.start(historyEnabled = true)
        val request = start.effect as HistoryEffect.Load
        return HistoryReducer.reduce(
            start.state,
            HistoryEvent.LoadSucceeded(request.requestId, HistoryPage(items, hasMore)),
        ).state
    }

    private fun item(id: Long): HistoryItem =
        HistoryItem(HistoryEventId(id), "2026-08-30T00:00:00Z", HistoryEventKind.Other("OTHER", null))
}
