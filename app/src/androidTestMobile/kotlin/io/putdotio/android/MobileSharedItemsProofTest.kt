package io.putdotio.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.MOBILE_FILES_DOWNLOAD_ACTION_TAG
import io.putdotio.android.files.MOBILE_FILES_SHARE_ACTION_TAG
import io.putdotio.android.files.MobileFilesScreen
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Synthetic proof that shared-with-me rows offer no owner actions; no API calls. */
@RunWith(AndroidJUnit4::class)
class MobileSharedItemsProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic shared-items proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.shared.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun sharedRowsKeepDownloadAndShareOnlyAndSharedFoldersOfferNothing() {
        var generation by mutableIntStateOf(0)
        compose.setContent {
            PutioTheme {
                Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    key(generation) {
                        MobileFilesScreen(
                            state = sharedFiles(),
                            onEvent = {},
                            onPlayMedia = {},
                            confirmedTrashEnabled = true,
                            onMoveItem = {},
                            onDownloadItem = {},
                            onShareItem = {},
                        )
                    }
                }
            }
        }

        for (folder in listOf(SHARED_ROOT, FRIEND, SHARED_FOLDER)) {
            compose.onNodeWithText(folder).assertIsDisplayed()
            compose.onNodeWithContentDescription("Actions for $folder").assertDoesNotExist()
        }
        screenshot("01-list")

        compose.onNodeWithContentDescription("Actions for $SHARED_VIDEO").performClick()
        compose.onNodeWithTag(MOBILE_FILES_DOWNLOAD_ACTION_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_SHARE_ACTION_TAG).assertIsDisplayed()
        for (owner in listOf("Rename", "Move", "Move to trash")) compose.onAllNodesWithText(owner).assertCountEquals(0)
        screenshot("02-shared-file-actions")

        compose.runOnIdle { generation++ }
        compose.onNodeWithContentDescription("Actions for $OWNED_VIDEO").performClick()
        for (owner in listOf("Rename", "Move", "Move to trash")) compose.onNodeWithText(owner).assertIsDisplayed()
        screenshot("03-owned-file-actions")
    }

    private fun screenshot(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments().getString("putio.shared.runId")))
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "shared-proof-$runId")
        check(directory.mkdirs() || directory.isDirectory)
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

private const val SHARED_ROOT = "Items shared with you"
private const val FRIEND = "deniz"
private const val SHARED_FOLDER = "Bodrum footage"
private const val SHARED_VIDEO = "Gümbet sunset.mp4"
private const val OWNED_VIDEO = "My rehearsal.mp4"

private fun sharedFiles(): FilesBrowserState {
    fun item(id: Long, name: String, type: PutioFileType) =
        FilesItem(FilesItemId(id), FilesFolder.Root.id, name, type, 128_000_000, "2026-09-08T12:00:00Z")
    return FilesBrowserState(listOf(FilesFolderState(
        FilesFolder.Root,
        FilesContent.Ready(listOf(
            item(10, SHARED_ROOT, PutioFileType.FOLDER).copy(folderType = PutioFolderType.SHARED_ROOT),
            item(11, FRIEND, PutioFileType.FOLDER).copy(folderType = PutioFolderType.SHARED_FRIEND),
            item(12, SHARED_FOLDER, PutioFileType.FOLDER).copy(isShared = true),
            item(13, SHARED_VIDEO, PutioFileType.VIDEO).copy(isShared = true),
            item(14, OWNED_VIDEO, PutioFileType.VIDEO),
        ), FilesPaging.Complete),
    )), 1)
}
