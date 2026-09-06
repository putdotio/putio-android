package io.putdotio.android.trash

import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.toFilesFailure
import io.putdotio.android.files.toFilesItem
import io.putdotio.sdk.OkResponse
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioSerializationException
import io.putdotio.sdk.files.FileDetailsQuery
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.trash.TrashBulkInput
import io.putdotio.sdk.trash.TrashContinueQuery
import io.putdotio.sdk.trash.TrashListQuery
import io.putdotio.sdk.trash.TrashListResponse
import java.util.concurrent.CancellationException

interface TrashRepository {
    suspend fun load(): FilesRepositoryResult<TrashPage>
    suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<TrashPage>
    suspend fun restore(itemId: FilesItemId): FilesRepositoryResult<Unit>
    suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem>
    suspend fun deleteItem(itemId: FilesItemId): FilesRepositoryResult<Unit>
    suspend fun restoreAll(selection: TrashBulkSelection): FilesRepositoryResult<Unit>
    suspend fun empty(): FilesRepositoryResult<Unit>
}

class SdkTrashRepository internal constructor(
    private val list: suspend (TrashListQuery) -> TrashListResponse,
    private val continueList: suspend (String, TrashContinueQuery) -> TrashListResponse,
    private val restoreItem: suspend (TrashBulkInput) -> OkResponse,
    private val getFile: suspend (Long, FileDetailsQuery) -> PutioFile,
    private val deleteItems: suspend (TrashBulkInput) -> OkResponse,
    private val emptyTrash: suspend () -> OkResponse,
) : TrashRepository {
    constructor(client: PutioClient) : this(
        list = { client.trash.list(it) },
        continueList = { cursor, query -> client.trash.continueList(cursor, query) },
        restoreItem = { client.trash.restore(it) },
        getFile = { id, query -> client.files.get(id, query) },
        deleteItems = { client.trash.delete(it) },
        emptyTrash = { client.trash.empty() },
    )

    override suspend fun load(): FilesRepositoryResult<TrashPage> = request {
        list(TrashListQuery(perPage = TRASH_PAGE_SIZE)).toPage(initial = true)
    }

    override suspend fun loadNextPage(cursor: FilesCursor): FilesRepositoryResult<TrashPage> = request {
        continueList(cursor.value, TrashContinueQuery(perPage = TRASH_PAGE_SIZE)).toPage(initial = false)
    }

    override suspend fun restore(itemId: FilesItemId): FilesRepositoryResult<Unit> = request {
        // Zero and cursor inputs are bulk selections; this app operation owns exactly one item.
        require(itemId.value > 0L) { "Restore requires one positive item ID" }
        restoreItem(TrashBulkInput(ids = listOf(itemId.value)))
    }

    override suspend fun deleteItem(itemId: FilesItemId): FilesRepositoryResult<Unit> = request {
        require(itemId.value > 0L) { "Permanent deletion requires one positive item ID" }
        deleteItems(TrashBulkInput(ids = listOf(itemId.value)))
    }

    override suspend fun restoreAll(selection: TrashBulkSelection): FilesRepositoryResult<Unit> = request {
        require(selection.itemIds.all { it.value > 0L }) { "Restore all requires positive item IDs" }
        val input = selection.cursor?.let { TrashBulkInput(cursor = it.value) }
            ?: TrashBulkInput(ids = selection.itemIds.map(FilesItemId::value))
        restoreItem(input)
    }

    override suspend fun empty(): FilesRepositoryResult<Unit> = request { emptyTrash() }

    override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> = request {
        require(itemId.value > 0L) { "Restore lookup requires one positive item ID" }
        getFile(itemId.value, FileDetailsQuery(
            mp4Size = false, startFrom = false, streamUrl = false, mp4StreamUrl = false,
        )).toFilesItem()
    }

    // Preserve cancellation and the SDK's typed causes at the application boundary.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> request(block: suspend () -> T): FilesRepositoryResult<T> = try {
        FilesRepositoryResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: PutioException) {
        FilesRepositoryResult.Failure(failure.toFilesFailure())
    } catch (unexpected: Exception) {
        FilesRepositoryResult.Failure(FilesFailure.Unexpected(unexpected))
    }
}

private fun TrashListResponse.toPage(initial: Boolean): TrashPage {
    if (files.any { it.id <= 0L || it.size < 0L }) {
        throw invalidTrashResponse("Trash contains an invalid item", "/trash/list")
    }
    return TrashPage(
        items = files.map { file ->
            TrashItem(
                id = FilesItemId(file.id), parentId = file.parentId?.let(::FilesItemId),
                name = file.name, type = file.fileType, sizeBytes = file.size,
                deletedAt = file.deletedAt.takeIf(String::isNotBlank),
                expirationDate = file.expirationDate.takeIf(String::isNotBlank),
            )
        },
        nextCursor = cursor?.takeIf(String::isNotBlank)?.let(::FilesCursor),
        total = if (initial) total else null,
        // The SDK preserves its public zero default on continuation; it is not a server aggregate.
        trashSizeBytes = if (initial) trashSize else null,
    )
}

internal fun invalidTrashResponse(message: String, path: String): PutioSerializationException =
    PutioSerializationException(PutioRequestData("GET", path), "", IllegalArgumentException(message))

private const val TRASH_PAGE_SIZE = 50
