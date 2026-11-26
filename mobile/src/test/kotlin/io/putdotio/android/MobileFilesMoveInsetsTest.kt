package io.putdotio.android

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesMoveDestinationState
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.MOBILE_FILES_MOVE_CANCEL_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_HERE_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_LIST_TAG
import io.putdotio.android.files.MOBILE_FILES_MOVE_PICKER_TAG
import io.putdotio.android.files.MobileFilesMoveDestination
import io.putdotio.android.files.filesMoveDestinationFolder
import io.putdotio.android.files.filesMoveDestinationState
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w800dp-h360dp-land")
class MobileFilesMoveInsetsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun dockSpansHorizontalCutoutsAndKeepsBottomPaddingAboveNavigationBar() {
        var dialogView: View? = null
        var density = 1f
        var dockColor = Color.Unspecified
        compose.setBuiltContent {
            PutioTheme {
                density = LocalDensity.current.density
                dockColor = MaterialTheme.colorScheme.surfaceContainerLow
                MobileFilesMoveDestination(
                    state = moveDestinationState(),
                    onEvent = {},
                    onCancel = {},
                    onConfirm = {},
                    confirmLabel = { target ->
                        dialogView = LocalView.current
                        "Move to $target"
                    },
                )
            }
        }
        compose.waitForIdle()
        dispatchCutoutAndBarInsets(checkNotNull(dialogView), density)

        val pickerNode = compose.onNodeWithTag(MOBILE_FILES_MOVE_PICKER_TAG)
        val picker = pickerNode.getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag(MOBILE_FILES_MOVE_LIST_TAG).getUnclippedBoundsInRoot()
        val confirm = compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).getUnclippedBoundsInRoot()

        assertTrue("Top bar must clear left cutout: cancel=$cancel", cancel.left >= picker.left + 48.dp)
        assertTrue(
            "Folder list must clear horizontal cutouts: list=$list",
            list.left >= picker.left + 48.dp && list.right <= picker.right - 48.dp,
        )
        assertTrue(
            "Confirm button must clear right cutout: confirm=$confirm, picker=$picker",
            confirm.right <= picker.right - 48.dp - 15.dp,
        )
        val bottomClearance = picker.bottom - 48.dp - confirm.bottom
        assertTrue(
            "Confirm button must keep at least 8.dp bottom padding, got $bottomClearance",
            bottomClearance >= 7.5.dp,
        )

        val pixels = pickerNode.captureToImage().toPixelMap()
        val sampleY = pixels.height - 4
        assertEquals("Left cutout edge must draw surfaceContainerLow", dockColor, pixels[4, sampleY])
        assertEquals("Right cutout edge must draw surfaceContainerLow", dockColor, pixels[pixels.width - 5, sampleY])
    }

    private fun dispatchCutoutAndBarInsets(view: View, density: Float) {
        val insetPx = (48 * density).toInt()
        val topInsetPx = (24 * density).toInt()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                view,
                WindowInsetsCompat.Builder()
                    .setInsets(
                        WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars(),
                        Insets.of(insetPx, topInsetPx, insetPx, insetPx),
                    )
                    .build(),
            )
        }
        compose.waitForIdle()
    }

    private fun moveDestinationState(): FilesMoveDestinationState {
        val destination = FilesFolder(FilesItemId(8), "Destination")
        val source = FilesItem(FilesItemId(7), FilesFolder.Root.id, "Source", PutioFileType.FOLDER, 0, "2026-09-06")
        val folders = listOf(
            FilesItem(FilesItemId(20), destination.id, "Folder 1", PutioFileType.FOLDER, 0, "2026-09-06"),
        )
        return filesMoveDestinationState(
            sourceItem = source,
            sourceFolderId = FilesFolder.Root.id,
            stack = listOf(
                filesMoveDestinationFolder(FilesFolder.Root, FilesContent.Empty(FilesPaging.Complete)),
                filesMoveDestinationFolder(destination, FilesContent.Ready(folders, FilesPaging.Complete)),
            ),
            nextRequestValue = 1,
        )
    }
}
