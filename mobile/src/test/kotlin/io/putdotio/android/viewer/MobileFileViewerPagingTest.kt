package io.putdotio.android.viewer

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFileViewerPagingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pagesWithoutAFileOfTheShownKindStopTheReadsAfterThree() {
        val reads = showViewer(pageBringsImage = { false })

        compose.runOnIdle { assertEquals(3, reads()) }
    }

    @Test
    fun aPageThatBringsAFileOfTheShownKindKeepsTheReadsGoing() {
        val reads = showViewer(pageBringsImage = { read -> read % 3 == 0 })

        compose.runOnIdle { assertEquals(FOLDER_PAGES, reads()) }
    }

    @Test
    fun reachingTheLastFileAgainReadsOnAfterTheStop() {
        val first = image(1L)
        val last = image(2L)
        var shown by mutableStateOf(last.id)
        val reads = showViewer(listOf(first, last), { shown }, pageBringsImage = { false })
        compose.runOnIdle { assertEquals(3, reads()) }

        compose.runOnIdle { shown = first.id }
        compose.runOnIdle { assertEquals(3, reads()) }
        compose.runOnIdle { shown = last.id }

        compose.runOnIdle { assertEquals(6, reads()) }
    }

    private fun showViewer(
        initial: List<FilesItem> = listOf(image(1L)),
        shownId: () -> FilesItemId = { initial.last().id },
        pageBringsImage: (Int) -> Boolean,
    ): () -> Int {
        var files by mutableStateOf(initial)
        var read by mutableIntStateOf(0)
        compose.setContent {
            PutioTheme {
                MobileFileViewerContent(
                    files = files,
                    shownId = shownId(),
                    actions = MobileFileViewerActions(documentUri = { Uri.EMPTY }, onOpenWithAnotherApp = {}),
                    onShown = {},
                    onDismiss = {},
                    paging = MobileFileViewerPaging(
                        nextPage = FilesPaging.Available(FilesCursor("page-$read")).takeIf { read < FOLDER_PAGES },
                        more = read < FOLDER_PAGES,
                        onLoadNextPage = {
                            read++
                            if (pageBringsImage(read)) files = files + image(100L + read)
                        },
                    ),
                )
            }
        }
        return { read }
    }

    private fun image(id: Long) =
        FilesItem(FilesItemId(id), FilesItemId(9L), "image-$id.jpg", PutioFileType.IMAGE, 1L, "2026-10-13")
}

private const val FOLDER_PAGES = 12
