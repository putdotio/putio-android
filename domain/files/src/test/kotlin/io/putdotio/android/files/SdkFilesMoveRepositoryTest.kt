package io.putdotio.android.files

import io.putdotio.sdk.files.FileMoveError
import io.putdotio.sdk.files.FilesContinueQuery
import io.putdotio.sdk.files.FilesListQuery
import io.putdotio.sdk.files.FilesListResponse
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import io.putdotio.android.PutioResult

class SdkFilesMoveRepositoryTest {
    @Test
    fun moveSendsTheSourceIdsInOneRequestAndPreservesEveryPerItemError() = runBlocking {
        val errors = listOf(FileMoveError("FUTURE_ERROR", 999L, null, 409))
        val calls = mutableListOf<Pair<List<Long>, Long>>()
        val repository = repository(move = { sources, destination -> calls += sources to destination; errors })
        val result = repository.move(listOf(FilesItemId(7L)), FilesFolder.Root.id) as PutioResult.Success
        assertSame(errors, result.value)
        repository.move(listOf(FilesItemId(7L), FilesItemId(8L)), FilesItemId(9L))
        assertEquals(listOf(listOf(7L) to 0L, listOf(7L, 8L) to 9L), calls)
        val refused = listOf(
            listOf(0L) to 9L, listOf(-1L) to 9L, listOf(7L) to -1L, listOf(7L) to 7L,
            listOf(7L, 9L) to 9L, listOf(7L, 0L) to 9L, emptyList<Long>() to 9L,
        )
        for ((sources, destination) in refused) {
            val moved = repository.move(sources.map(::FilesItemId), FilesItemId(destination))
            assertTrue(moved is PutioResult.Failure)
        }
        assertEquals(2, calls.size)
    }

    @Test
    fun folderOnlyQueryAndContinuationExcludeVirtualAndNonFolderRows() = runBlocking {
        val regular = sdkFolder(4L)
        val shared = sdkFolder(5L).copy(folderType = PutioFolderType.SHARED_ROOT)
        val rows = listOf(regular, shared, sdkFolder(-1L), sdkFolder(6L).copy(fileType = PutioFileType.VIDEO))
        var folderQuery: FilesListQuery? = null
        var continuationQuery: FilesContinueQuery? = null
        val repository = repository(
            list = { folderId, query ->
                assertEquals(0L, folderId)
                folderQuery = query
                FilesListResponse(files = rows, cursor = "next", status = "OK")
            },
            continueList = { cursor, query ->
                assertEquals("next", cursor)
                continuationQuery = query
                FilesListResponse(files = rows, status = "OK")
            },
        )
        val first = repository.loadMoveDestinations(FilesFolder.Root.id) as PutioResult.Success
        assertEquals(listOf(regular.toFilesItem()), first.value.items)
        assertEquals(PutioFileType.FOLDER, folderQuery?.fileType)
        assertEquals(50, folderQuery?.perPage)
        assertEquals(false, folderQuery?.noCursor)
        val second = repository.loadMoveDestinations(
            FilesFolder.Root.id, first.value.nextCursor,
        ) as PutioResult.Success
        assertEquals(listOf(regular.toFilesItem()), second.value.items)
        assertEquals(50, continuationQuery?.perPage)
        assertTrue(repository.loadMoveDestinations(FilesItemId(-1L)) is PutioResult.Failure)
    }

    private fun repository(
        move: suspend (List<Long>, Long) -> List<FileMoveError> = { _, _ -> error("Unexpected move") },
        list: suspend (Long, FilesListQuery) -> FilesListResponse = { _, _ -> error("Unexpected listing") },
        continueList: suspend (String, FilesContinueQuery) -> FilesListResponse = { _, _ ->
            error("Unexpected continuation")
        },
    ) = SdkFilesRepository(
        listFolder = list,
        continueListing = continueList,
        setSort = { _, _ -> error("Unexpected sort") },
        getFile = { error("Unexpected item lookup") },
        mutations = SdkFilesMutations(
            rename = { _, _ -> error("Unexpected rename") },
            delete = { _, _ -> error("Unexpected delete") },
            move = move,
        ),
    )

    private fun sdkFolder(id: Long) = PutioFile(
        id = id, name = "folder-$id", fileType = PutioFileType.FOLDER,
        size = 0L, createdAt = "2026-09-06", parentId = 0L,
    )
}
