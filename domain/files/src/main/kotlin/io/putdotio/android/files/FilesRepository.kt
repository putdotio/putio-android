package io.putdotio.android.files

import io.putdotio.android.PutioResult
import io.putdotio.android.displayableApiReason
import io.putdotio.android.putioRequest
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.FilesContinueQuery
import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.sdk.files.FilesListQuery
import io.putdotio.sdk.files.FilesListResponse
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.sharing.CloneSharedFilesInput
import io.putdotio.sdk.sharing.SharedFileCloneInfo
import io.putdotio.sdk.sharing.SharedFileCloneStatus

public interface FilesRepository : FilesCopyRepository {
    public suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage>

    public suspend fun loadNextPage(cursor: FilesCursor): PutioResult<FilesPage>

    public suspend fun loadMoveDestinations(
        folderId: FilesItemId,
        cursor: FilesCursor? = null,
    ): PutioResult<FilesPage>

    public suspend fun move(itemId: FilesItemId, destinationId: FilesItemId): PutioResult<List<FileMoveError>>

    public suspend fun persistSort(
        folderId: FilesItemId,
        sort: FilesSort,
    ): PutioResult<Unit>

    public suspend fun rename(itemId: FilesItemId, name: String): PutioResult<Unit>

    public suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode): PutioResult<FileDeleteResult>

    public suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem>
}

public interface FilesCopyRepository {
    /** Starts copying an item shared with the viewer into [destinationId]; put.io copies in the background. */
    public suspend fun startCopy(itemId: FilesItemId, destinationId: FilesItemId): PutioResult<FilesCopyId>

    public suspend fun checkCopy(copyId: FilesCopyId): PutioResult<FilesCopyProgress>
}

public interface FilesItemResolver {
    public suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem>
}

internal class SdkFilesMutations(
    val rename: suspend (Long, String) -> Unit,
    val delete: suspend (Long, Boolean) -> FileDeleteResult,
    val move: suspend (Long, Long) -> List<FileMoveError>,
)

internal class SdkFilesCopies(
    private val start: suspend (Long, Long) -> Long,
    private val info: suspend (Long) -> SharedFileCloneInfo,
) : FilesCopyRepository {
    override suspend fun startCopy(
        itemId: FilesItemId,
        destinationId: FilesItemId,
    ): PutioResult<FilesCopyId> = putioRequest {
        // A lone zero is a bulk selector, never a single source item.
        require(itemId.value > 0L && destinationId.value >= 0L) {
            "Copy requires one positive source ID and a nonnegative destination ID"
        }
        FilesCopyId(start(itemId.value, destinationId.value))
    }

    override suspend fun checkCopy(copyId: FilesCopyId): PutioResult<FilesCopyProgress> = putioRequest {
        val info = info(copyId.value)
        when (info.status) {
            SharedFileCloneStatus.DONE -> FilesCopyProgress.Done
            SharedFileCloneStatus.ERROR -> FilesCopyProgress.Failed(info.errorMessage?.let(::displayableApiReason))
            // NEW, PROCESSING and any status this app does not know yet may still finish.
            else -> FilesCopyProgress.Running
        }
    }
}

public class SdkFilesRepository internal constructor(
    private val listFolder: suspend (Long, FilesListQuery) -> FilesListResponse,
    private val continueListing: suspend (String, FilesContinueQuery) -> FilesListResponse,
    private val setSort: suspend (Long, String) -> Unit,
    private val getFile: suspend (Long) -> PutioFile,
    private val mutations: SdkFilesMutations,
    copies: SdkFilesCopies = SdkFilesCopies(
        start = { _, _ -> error("Copies are not wired") },
        info = { error("Copies are not wired") },
    ),
) : FilesRepository, FilesItemResolver, FilesCopyRepository by copies {
    public constructor(client: PutioClient) : this(
        listFolder = { folderId, query -> client.files.list(parentId = folderId, query = query) },
        continueListing = { cursor, query -> client.files.continueList(cursor = cursor, query = query) },
        setSort = { folderId, sort ->
            client.files.setSortBy(fileId = folderId, sortBy = sort)
            Unit
        },
        getFile = { fileId -> client.files.get(fileId) },
        mutations = SdkFilesMutations(
            rename = { fileId, name ->
                client.files.rename(fileId, name)
                Unit
            },
            delete = { fileId, skipTrash ->
                client.files.delete(fileIds = listOf(fileId), skipTrash = skipTrash)
            },
            move = { fileId, destinationId -> client.files.move(listOf(fileId), destinationId) },
        ),
        copies = SdkFilesCopies(
            start = { fileId, destinationId ->
                client.sharing.cloneSharedFiles(CloneSharedFilesInput(ids = listOf(fileId), parentId = destinationId))
            },
            info = { copyId -> client.sharing.getCloneInfo(copyId) },
        ),
    )

    override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> =
        // Child video_metadata carries duration for the watched indicator; continuation
        // cursors inherit the initial listing's field flags server-side.
        putioRequest {
            listFolder(folderId.value, FilesListQuery(perPage = FILES_PAGE_SIZE, videoMetadata = true)).toFilesPage()
        }

    override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<FilesPage> =
        putioRequest { continueListing(cursor.value, FilesContinueQuery(perPage = FILES_PAGE_SIZE)).toFilesPage() }

    override suspend fun loadMoveDestinations(
        folderId: FilesItemId,
        cursor: FilesCursor?,
    ): PutioResult<FilesPage> = putioRequest {
        require(folderId.value >= 0L) { "Move destination must be root or a positive folder ID" }
        val response = if (cursor == null) {
            listFolder(folderId.value, FilesListQuery(perPage = FILES_PAGE_SIZE, fileType = PutioFileType.FOLDER))
        } else {
            continueListing(cursor.value, FilesContinueQuery(perPage = FILES_PAGE_SIZE))
        }
        response.copy(files = response.files.filter {
            it.id > 0L && it.fileType == PutioFileType.FOLDER && it.folderType == PutioFolderType.REGULAR
        }).toFilesPage()
    }

    override suspend fun move(
        itemId: FilesItemId,
        destinationId: FilesItemId,
    ): PutioResult<List<FileMoveError>> = putioRequest {
        // A lone zero is a bulk selector, never a single source item.
        require(itemId.value > 0L && destinationId.value >= 0L && itemId != destinationId) {
            "Move requires one positive source ID and a different nonnegative destination ID"
        }
        mutations.move(itemId.value, destinationId.value)
    }

    override suspend fun persistSort(
        folderId: FilesItemId,
        sort: FilesSort,
    ): PutioResult<Unit> = putioRequest { setSort(folderId.value, sort.apiValue) }

    override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
        putioRequest { getFile(itemId.value).toFilesItem() }

    override suspend fun rename(itemId: FilesItemId, name: String): PutioResult<Unit> =
        putioRequest { mutations.rename(itemId.value, name) }

    override suspend fun delete(itemId: FilesItemId, mode: FilesDeleteMode): PutioResult<FileDeleteResult> =
        putioRequest {
            // A lone zero means every root item at the API boundary.
            require(itemId.value > 0L) { "Delete requires one positive file ID" }
            mutations.delete(itemId.value, mode == FilesDeleteMode.PERMANENT)
        }

}

private fun FilesListResponse.toFilesPage(): FilesPage =
    FilesPage(
        items = files.map(PutioFile::toFilesItem),
        nextCursor = cursor?.takeIf(String::isNotBlank)?.let(::FilesCursor),
        sort = FilesSort.fromApiValue(parent?.sortBy),
        parent = parent?.toFilesItem(),
    )

public fun PutioFile.toFilesItem(): FilesItem =
    FilesItem(
        id = FilesItemId(id),
        parentId = parentId?.let(::FilesItemId),
        name = name,
        type = fileType,
        sizeBytes = size,
        createdAt = createdAt,
        playback = toPlaybackProgress(),
        isShared = isShared,
        folderType = folderType,
    )

// Only media rows carry a position. Malformed server values drop the indicator instead of
// failing the list. A never-played media file has no position but may still have a
// duration, which is kept so it can be marked watched.
private fun PutioFile.toPlaybackProgress(): FilesPlaybackProgress? {
    val isMedia = fileType == PutioFileType.VIDEO || fileType == PutioFileType.AUDIO
    val position = startFrom ?: 0.0
    // Absent duration is a known unknown; a supplied but unusable one is malformed data.
    val duration = videoMetadata?.duration
    val durationValid = duration == null || (duration.isFinite() && duration > 0.0)
    val known = startFrom != null || duration != null
    val usable = isMedia && known && position.isFinite() && position >= 0.0 && durationValid
    return if (usable) FilesPlaybackProgress(position, duration) else null
}

private const val FILES_PAGE_SIZE = 50
