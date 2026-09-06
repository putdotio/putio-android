package io.putdotio.android.trash

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal class FakeTrashRepository : TrashRepository {
    var loadCount = 0
    val pageCursors = mutableListOf<FilesCursor>()
    val restoredIds = mutableListOf<FilesItemId>()
    val resolvedIds = mutableListOf<FilesItemId>()
    var onLoad: suspend () -> FilesRepositoryResult<TrashPage> = { page(trashItem()) }
    var onPage: suspend (FilesCursor) -> FilesRepositoryResult<TrashPage> = { page() }
    var onRestore: suspend (FilesItemId) -> FilesRepositoryResult<Unit> = { FilesRepositoryResult.Success(Unit) }
    var onResolve: suspend (FilesItemId) -> FilesRepositoryResult<FilesItem> = {
        FilesRepositoryResult.Failure(apiFailure(404, "NOT_FOUND"))
    }
    override suspend fun load(): FilesRepositoryResult<TrashPage> { loadCount += 1; return onLoad() }
    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<TrashPage> {
        pageCursors += cursor
        return onPage(cursor)
    }
    override suspend fun restore(itemId: FilesItemId): FilesRepositoryResult<Unit> {
        restoredIds += itemId
        return onRestore(itemId)
    }
    override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> {
        resolvedIds += itemId
        return onResolve(itemId)
    }
}

internal fun trashItem(id: Long = 7L) = TrashItem(
    FilesItemId(id), FilesItemId(2L), "Türkçe-$id.txt", PutioFileType.TEXT, 12L,
    "2026-09-06T10:00:00", "2026-09-20T10:00:00",
)

internal fun liveItem(item: TrashItem = trashItem()) = FilesItem(
    item.id, item.parentId, item.name, item.type, item.sizeBytes, "2026-09-01",
)

internal fun page(vararg items: TrashItem) =
    FilesRepositoryResult.Success(TrashPage(items.toList(), null, items.size, 12L))

internal fun apiFailure(status: Int, type: String) = FilesFailure.ApiRejected(
    status, type, PutioApiException(
        request = PutioRequestData("POST", "/trash/restore"), resolvedStatusCode = status,
        resolvedErrorType = type, envelope = PutioApiErrorEnvelope(status = "ERROR"),
        responseBody = "", message = "Test API failure",
    ),
)

internal fun offlineFailure() = FilesFailure.Unexpected(IllegalStateException("offline"))

internal suspend fun TrashController.awaitState(predicate: (TrashState) -> Boolean): TrashState =
    withTimeout(5_000) { state.first(predicate) }

internal suspend fun TrashController.openLoaded() {
    dispatch(TrashEvent.Open)
    awaitState { it.content is TrashContent.Loaded && !it.content.isRefreshing }
}

internal fun TrashController.confirm(itemId: FilesItemId = trashItem().id) {
    check(dispatch(TrashEvent.SelectRestore(itemId)))
    check(dispatch(TrashEvent.ConfirmRestore(checkNotNull(state.value.confirmationId))))
}
