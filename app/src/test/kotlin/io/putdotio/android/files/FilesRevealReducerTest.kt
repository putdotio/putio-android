package io.putdotio.android.files

import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesRevealReducerTest {
    private val folderId = FilesItemId(44L)

    @Test
    fun anOutsideOpenReadsLaterPagesUntilItsFileAppearsThenOpensAtIt() {
        val pages = folderPages(pageCount = 4)
        val file = pages[2].items[17]
        val opening = FilesBrowserReducer.reduce(
            FilesBrowserReducer.start().state, FilesBrowserEvent.OpenExternalItem(file, FilesOpenOrigin.SEARCH),
        )

        val (settled, reads) = drive(opening, pages)

        assertEquals(listOf(null, FilesCursor("page-1"), FilesCursor("page-2")), reads)
        val content = settled.current.content as FilesContent.Ready
        assertEquals(pages.take(3).flatMap { it.items }, content.items)
        assertEquals(FILES_PER_PAGE * 2 + 17, content.viewport.firstVisibleItemIndex)
        assertEquals(FilesPaging.Available(FilesCursor("page-3")), content.paging)
        assertEquals("Sample folder", settled.current.folder.name)
        assertEquals(file.id, settled.current.revealItemId)
    }

    @Test
    fun aFolderThatEndsWithoutTheFileOpensAtItsTop() {
        val pages = folderPages(pageCount = 3)
        val missing = row(9_999L).copy(parentId = folderId)
        val opening = FilesBrowserReducer.reduce(
            FilesBrowserReducer.start().state, FilesBrowserEvent.OpenExternalItem(missing, FilesOpenOrigin.HISTORY),
        )

        val (settled, reads) = drive(opening, pages)

        assertEquals(3, reads.size)
        val content = settled.current.content as FilesContent.Ready
        assertEquals(FILES_PER_PAGE * 3, content.items.size)
        assertEquals(FilesViewportPosition(), content.viewport)
        assertEquals(FilesPaging.Complete, content.paging)
    }

    @Test
    fun theSearchStopsAtThePageCapAndLeavesLoadMore() {
        val pages = folderPages(pageCount = MAX_REVEAL_PAGES + 2)
        val beyondCap = pages[MAX_REVEAL_PAGES].items.first()
        val opening = FilesBrowserReducer.reduce(
            FilesBrowserReducer.start().state, FilesBrowserEvent.OpenExternalItem(beyondCap, FilesOpenOrigin.SEARCH),
        )

        val (settled, reads) = drive(opening, pages)

        assertEquals(MAX_REVEAL_PAGES, reads.size)
        val content = settled.current.content as FilesContent.Ready
        assertEquals(FILES_PER_PAGE * MAX_REVEAL_PAGES, content.items.size)
        assertEquals(FilesViewportPosition(), content.viewport)
        assertEquals(FilesPaging.Available(FilesCursor("page-$MAX_REVEAL_PAGES")), content.paging)
        val loadMore = FilesBrowserReducer.reduce(settled, FilesBrowserEvent.LoadNextPage)
        assertEquals(FilesCursor("page-$MAX_REVEAL_PAGES"), (loadMore.effect as FilesBrowserEffect.LoadNextPage).cursor)
    }

    @Test
    fun aFailedSearchPageShowsTheRowsReadSoFarWithARetry() {
        val pages = folderPages(pageCount = 3)
        val file = pages[2].items.first()
        val opening = FilesBrowserReducer.reduce(
            FilesBrowserReducer.start().state, FilesBrowserEvent.OpenExternalItem(file, FilesOpenOrigin.SEARCH),
        )
        val firstPage = FilesBrowserReducer.reduce(
            opening.state, FilesBrowserEvent.LoadSucceeded(opening.effect!!.requestId, pages[0]),
        )
        val failure = FilesFailure.Unexpected(IllegalStateException("offline"))

        val failed = FilesBrowserReducer.reduce(
            firstPage.state, FilesBrowserEvent.LoadFailed(firstPage.effect!!.requestId, failure),
        ).state

        val content = failed.current.content as FilesContent.Ready
        assertEquals(pages[0].items, content.items)
        assertEquals(FilesPaging.Failed(FilesCursor("page-1"), failure), content.paging)
        val retry = FilesBrowserReducer.reduce(failed, FilesBrowserEvent.Retry)
        assertEquals(FilesCursor("page-1"), (retry.effect as FilesBrowserEffect.LoadNextPage).cursor)
    }

    @Test
    fun leavingTheFolderDropsItsSearchRequest() {
        val pages = folderPages(pageCount = 3)
        val opening = FilesBrowserReducer.reduce(
            FilesBrowserReducer.start().state,
            FilesBrowserEvent.OpenExternalItem(pages[2].items.first(), FilesOpenOrigin.SEARCH),
        )
        val searching = FilesBrowserReducer.reduce(
            opening.state, FilesBrowserEvent.LoadSucceeded(opening.effect!!.requestId, pages[0]),
        )
        val pending = searching.effect as FilesBrowserEffect.LoadNextPage

        val back = FilesBrowserReducer.reduce(searching.state, FilesBrowserEvent.NavigateBack).state

        assertFalse(back.hasRequest(pending.requestId))
        val late = FilesBrowserReducer.reduce(back, FilesBrowserEvent.LoadSucceeded(pending.requestId, pages[1]))
        assertFalse(late.consumed)
    }

    @Test
    fun revealingARowBeyondALoadedListingReadsOnToIt() {
        val pages = folderPages(pageCount = 3, parent = FilesFolder.Root.id)
        val start = FilesBrowserReducer.start()
        val loaded = FilesBrowserReducer.reduce(
            start.state, FilesBrowserEvent.LoadSucceeded(start.effect!!.requestId, pages[0]),
        ).state
        val target = pages[1].items[3]

        val reveal = FilesBrowserReducer.reduce(loaded, FilesBrowserEvent.RevealItem(FilesFolder.Root.id, target.id))

        assertTrue(reveal.current().content is FilesContent.Loading)
        val (settled, reads) = drive(reveal, pages)
        assertEquals(listOf(FilesCursor("page-1")), reads)
        val content = settled.current.content as FilesContent.Ready
        assertEquals(pages.take(2).flatMap { it.items }, content.items)
        assertEquals(FILES_PER_PAGE + 3, content.viewport.firstVisibleItemIndex)
    }

    @Test
    fun revealingALoadedRowOrAnotherFolderReadsNothing() {
        val pages = folderPages(pageCount = 2, parent = FilesFolder.Root.id)
        val start = FilesBrowserReducer.start()
        val loaded = FilesBrowserReducer.reduce(
            start.state, FilesBrowserEvent.LoadSucceeded(start.effect!!.requestId, pages[0]),
        ).state

        val listed = FilesBrowserReducer.reduce(
            loaded, FilesBrowserEvent.RevealItem(FilesFolder.Root.id, pages[0].items[4].id),
        )
        val elsewhere = FilesBrowserReducer.reduce(loaded, FilesBrowserEvent.RevealItem(folderId, pages[1].items[0].id))

        assertNull(listed.effect)
        assertEquals(loaded, listed.state)
        assertFalse(elsewhere.consumed)
        assertNull(elsewhere.effect)
    }

    private fun FilesBrowserTransition.current(): FilesFolderState = state.current

    /** Answers every listing request from [pages] and returns the settled state and the cursors read. */
    private fun drive(
        transition: FilesBrowserTransition,
        pages: List<FilesPage>,
    ): Pair<FilesBrowserState, List<FilesCursor?>> {
        var current = transition
        val reads = mutableListOf<FilesCursor?>()
        while (true) {
            val page = when (val effect = current.effect) {
                is FilesBrowserEffect.LoadFolder -> pages[0].also { reads += null }
                is FilesBrowserEffect.LoadNextPage ->
                    pages[effect.cursor.value.removePrefix("page-").toInt()].also { reads += effect.cursor }
                null -> return current.state to reads
                else -> error("Unexpected effect $effect")
            }
            val loaded = FilesBrowserEvent.LoadSucceeded(checkNotNull(current.effect).requestId, page)
            current = FilesBrowserReducer.reduce(current.state, loaded)
        }
    }

    private fun folderPages(pageCount: Int, parent: FilesItemId = folderId): List<FilesPage> =
        (0 until pageCount).map { index ->
            FilesPage(
                items = (1..FILES_PER_PAGE).map { row(index * 100L + it).copy(parentId = parent) },
                nextCursor = FilesCursor("page-${index + 1}").takeIf { index + 1 < pageCount },
                parent = row(parent.value, PutioFileType.FOLDER).copy(name = "Sample folder").takeIf { index == 0 },
            )
        }

    private fun row(id: Long, type: PutioFileType = PutioFileType.VIDEO) = FilesItem(
        id = FilesItemId(id),
        parentId = folderId,
        name = "Harbor film $id.mp4",
        type = type,
        sizeBytes = 1L,
        createdAt = "2026-09-30T00:00:00Z",
    )

    private companion object {
        const val FILES_PER_PAGE = 50
    }
}
