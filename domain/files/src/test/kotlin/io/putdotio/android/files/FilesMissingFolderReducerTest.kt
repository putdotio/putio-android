package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.putioErrorBody
import io.putdotio.android.putioRefusal
import io.putdotio.android.toPutioFailure
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesMissingFolderReducerTest {
    private val shows = folder(7L, "Shows")
    private val season = folder(8L, "Season 1", parent = shows.id)

    @Test
    fun aFolderThatAnswers404ReturnsToItsParentWhichReloadsWithoutIt() {
        val opened = reduce(loadedRoot(shows), FilesBrowserEvent.OpenFolder(shows.id))
        val request = (opened.effect as FilesBrowserEffect.LoadFolder).requestId

        val missing = reduce(opened.state, FilesBrowserEvent.LoadFailed(request, notFound()))

        assertTrue(missing.consumed)
        assertEquals(listOf(FilesFolder.Root.id), missing.state.path.map { it.id })
        assertEquals(FilesFolder(shows.id, "Shows"), missing.state.missingFolder?.folder)
        assertTrue(missing.state.current.content.items().none { it.id == shows.id })
        val reload = missing.effect as FilesBrowserEffect.LoadFolder
        assertEquals(FilesFolder.Root.id, reload.folderId)
        val emptyRoot = FilesPage(emptyList(), null)
        val reloaded = reduce(missing.state, FilesBrowserEvent.LoadSucceeded(reload.requestId, emptyRoot))
        assertTrue(reloaded.state.current.content is FilesContent.Empty)

        val notice = requireNotNull(missing.state.missingFolder)
        val announced = reduce(reloaded.state, FilesBrowserEvent.MissingFolderAnnounced(notice))
        assertTrue(announced.consumed)
        assertNull(announced.state.missingFolder)
    }

    @Test
    fun aRefreshThatAnswers404ReturnsToTheParent() {
        val inShows = loaded(reduce(loadedRoot(shows), FilesBrowserEvent.OpenFolder(shows.id)), season)
        val inSeason = loaded(reduce(inShows, FilesBrowserEvent.OpenFolder(season.id)), file(9L, season.id))
        val refreshing = reduce(inSeason, FilesBrowserEvent.Refresh)
        val request = (refreshing.effect as FilesBrowserEffect.LoadFolder).requestId

        val missing = reduce(refreshing.state, FilesBrowserEvent.LoadFailed(request, notFound()))

        assertEquals(listOf(FilesFolder.Root.id, shows.id), missing.state.path.map { it.id })
        assertEquals(season.id, missing.state.missingFolder?.folder?.id)
        assertEquals(shows.id, (missing.effect as FilesBrowserEffect.LoadFolder).folderId)
    }

    @Test
    fun aMissingFolderOpenedFromOutsideFallsBackToTheFilesRoot() {
        val inShows = loaded(reduce(loadedRoot(shows), FilesBrowserEvent.OpenFolder(shows.id)), season)
        val opened = reduce(inShows, FilesBrowserEvent.OpenExternalItem(folder(40L, "Gone"), FilesOpenOrigin.SEARCH))
        val request = (opened.effect as FilesBrowserEffect.LoadFolder).requestId

        val missing = reduce(opened.state, FilesBrowserEvent.LoadFailed(request, notFound()))

        assertEquals(listOf(FilesFolder.Root.id), missing.state.path.map { it.id })
        assertEquals(FilesItemId(40L), missing.state.missingFolder?.folder?.id)
        assertEquals(FilesFolder.Root.id, (missing.effect as FilesBrowserEffect.LoadFolder).folderId)
    }

    @Test
    fun aMissingNextPageStaysOnTheFolder() {
        val opened = reduce(loadedRoot(shows), FilesBrowserEvent.OpenFolder(shows.id))
        val firstPage = (opened.effect as FilesBrowserEffect.LoadFolder).requestId
        val inShows = reduce(
            opened.state,
            FilesBrowserEvent.LoadSucceeded(firstPage, FilesPage(listOf(season), FilesCursor("next"))),
        ).state
        val paging = reduce(inShows, FilesBrowserEvent.LoadNextPage)
        val request = (paging.effect as FilesBrowserEffect.LoadNextPage).requestId

        val failed = reduce(paging.state, FilesBrowserEvent.LoadFailed(request, notFound()))

        assertEquals(shows.id, failed.state.current.folder.id)
        assertNull(failed.state.missingFolder)
    }

    @Test
    fun anyOtherLoadFailureStaysOnTheFolderWithTryAgain() {
        val opened = reduce(loadedRoot(shows), FilesBrowserEvent.OpenFolder(shows.id))
        val request = (opened.effect as FilesBrowserEffect.LoadFolder).requestId
        val failure = PutioFailure.Unexpected(IllegalStateException("boom"))

        val failed = reduce(opened.state, FilesBrowserEvent.LoadFailed(request, failure))

        assertEquals(shows.id, failed.state.current.folder.id)
        assertEquals(FilesContent.Failed(failure), failed.state.current.content)
        assertNull(failed.state.missingFolder)
        assertNull(failed.effect)
    }

    @Test
    fun theRootThatAnswers404FailsInPlace() {
        val start = FilesBrowserReducer.start()
        val request = (start.effect as FilesBrowserEffect.LoadFolder).requestId

        val failed = reduce(start.state, FilesBrowserEvent.LoadFailed(request, notFound()))

        assertFalse(failed.state.canNavigateBack)
        assertTrue(failed.state.current.content is FilesContent.Failed)
        assertNull(failed.state.missingFolder)
    }

    private fun reduce(state: FilesBrowserState, event: FilesBrowserEvent) = FilesBrowserReducer.reduce(state, event)

    private fun loadedRoot(vararg items: FilesItem): FilesBrowserState =
        loaded(FilesBrowserReducer.start(), *items)

    private fun loaded(transition: FilesBrowserTransition, vararg items: FilesItem): FilesBrowserState {
        val request = (transition.effect as FilesBrowserEffect.LoadFolder).requestId
        return reduce(transition.state, FilesBrowserEvent.LoadSucceeded(request, FilesPage(items.toList(), null))).state
    }

    private fun folder(id: Long, name: String, parent: FilesItemId = FilesFolder.Root.id) =
        FilesItem(FilesItemId(id), parent, name, PutioFileType.FOLDER, 1L, "2026-11-01T00:00:00Z")

    private fun file(id: Long, parent: FilesItemId) =
        FilesItem(FilesItemId(id), parent, "episode-$id.mkv", PutioFileType.VIDEO, 1L, "2026-11-01T00:00:00Z")

    private fun notFound(): PutioFailure = putioRefusal(404, putioErrorBody(404, "File not found")).toPutioFailure()
}
