package io.putdotio.android.trash

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class FakeTrashRepository : TrashRepository {
    var loadCount = 0
    val pageCursors = mutableListOf<FilesCursor>()
    val restoredIds = mutableListOf<FilesItemId>()
    val resolvedIds = mutableListOf<FilesItemId>()
    val deletedIds = mutableListOf<FilesItemId>()
    val bulkRestores = mutableListOf<TrashBulkSelection>()
    var emptyCount = 0
    var onLoad: suspend () -> PutioResult<TrashPage> = { page(trashItem()) }
    var onPage: suspend (FilesCursor) -> PutioResult<TrashPage> = { page() }
    var onRestore: suspend (FilesItemId) -> PutioResult<Unit> = { PutioResult.Success(Unit) }
    var onResolve: suspend (FilesItemId) -> PutioResult<FilesItem> = {
        PutioResult.Failure(apiFailure(404, "NOT_FOUND"))
    }
    var onDelete: suspend (FilesItemId) -> PutioResult<Unit> = { PutioResult.Success(Unit) }
    var onRestoreAll: suspend (TrashBulkSelection) -> PutioResult<Unit> =
        { PutioResult.Success(Unit) }
    var onEmpty: suspend () -> PutioResult<Unit> = { PutioResult.Success(Unit) }
    override suspend fun load(): PutioResult<TrashPage> { loadCount += 1; return onLoad() }
    override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<TrashPage> {
        pageCursors += cursor
        return onPage(cursor)
    }
    override suspend fun restore(itemId: FilesItemId): PutioResult<Unit> {
        restoredIds += itemId
        return onRestore(itemId)
    }
    override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> {
        resolvedIds += itemId
        return onResolve(itemId)
    }
    override suspend fun deleteItem(itemId: FilesItemId): PutioResult<Unit> {
        deletedIds += itemId
        return onDelete(itemId)
    }
    override suspend fun restoreAll(selection: TrashBulkSelection): PutioResult<Unit> {
        bulkRestores += selection
        return onRestoreAll(selection)
    }
    override suspend fun empty(): PutioResult<Unit> { emptyCount += 1; return onEmpty() }
}

fun trashItem(id: Long = 7L) = TrashItem(
    FilesItemId(id), FilesItemId(2L), "Türkçe-$id.txt", PutioFileType.TEXT, 12L,
    "2026-09-06T10:00:00", "2026-09-20T10:00:00",
)

fun liveItem(item: TrashItem = trashItem()) = FilesItem(
    item.id, item.parentId, item.name, item.type, item.sizeBytes, "2026-09-01",
)

fun page(vararg items: TrashItem) =
    PutioResult.Success(TrashPage(items.toList(), null, items.size, 12L))

internal fun apiFailure(status: Int, type: String) = PutioFailure.ApiRejected(
    status, type, PutioApiException(
        request = PutioRequestData("POST", "/trash/restore"), resolvedStatusCode = status,
        resolvedErrorType = type, envelope = PutioApiErrorEnvelope(status = "ERROR"),
        responseBody = "", message = "Test API failure",
    ),
)

internal fun offlineFailure() = PutioFailure.Unexpected(IllegalStateException("offline"))

internal suspend fun TrashController.awaitState(predicate: (TrashState) -> Boolean): TrashState =
    withTimeout(5_000) { state.first(predicate) }

internal suspend fun TrashController.openLoaded() {
    dispatch(TrashEvent.Open)
    awaitState { it.content is TrashContent.Loaded && !it.content.isRefreshing }
}

fun TrashController.confirm(itemId: FilesItemId = trashItem().id) {
    check(dispatch(TrashEvent.SelectRestore(itemId)))
    check(dispatch(TrashEvent.ConfirmRestore(checkNotNull(state.value.confirmationId))))
}

internal fun TrashController.confirmAction(select: TrashEvent) {
    check(dispatch(select))
    check(dispatch(TrashEvent.ConfirmAction(checkNotNull(state.value.actionConfirmationId))))
}
