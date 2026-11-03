package io.putdotio.android.viewer

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFileViewerLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun thePagesKeepTheirHeightWhenThePositionLineComesAndGoes() {
        val first = image(1L)
        var files by mutableStateOf(listOf(first, image(2L)))
        compose.setContent {
            PutioTheme {
                MobileFileViewerContent(
                    files = files,
                    shownId = first.id,
                    actions = MobileFileViewerActions(documentUri = { Uri.EMPTY }, onOpenWithAnotherApp = {}),
                    onShown = {},
                    onDismiss = {},
                )
            }
        }
        val withPosition = compose.onNodeWithTag(MOBILE_FILE_VIEWER_PAGES_TAG).getUnclippedBoundsInRoot()

        compose.runOnIdle { files = listOf(first) }

        val alone = compose.onNodeWithTag(MOBILE_FILE_VIEWER_PAGES_TAG).getUnclippedBoundsInRoot()
        assertEquals(withPosition.bottom - withPosition.top, alone.bottom - alone.top)
    }

    private fun image(id: Long) =
        FilesItem(FilesItemId(id), FilesItemId(9L), "image-$id.jpg", PutioFileType.IMAGE, 1L, "2026-10-13")
}
