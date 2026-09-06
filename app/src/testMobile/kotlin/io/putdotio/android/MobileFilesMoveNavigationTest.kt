package io.putdotio.android

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileFilesMoveNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var backOwner: OnBackPressedDispatcherOwner
    private var fallbacks = 0

    @Test
    fun rootMoveConsumesBackOnFilesAndAccountUntilRecoveryCompletes() {
        val preview = RootMoveBackPreview()
        compose.setContent {
            val owner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            DisposableEffect(owner) {
                backOwner = owner
                // Register before MobileShell so this observes an otherwise unhandled activity Back.
                val fallback = object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { fallbacks += 1 }
                }
                owner.onBackPressedDispatcher.addCallback(owner, fallback)
                onDispose { fallback.remove() }
            }
            PutioTheme {
                Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background) {
                    MobileShell(
                        filesState = preview.files, accountSettingsState = preview.settings,
                        appConfigState = preview.appConfig,
                        account = MobileAccount(42L, "Synthetic proof", "synthetic@example.invalid"),
                        sessionId = MobileAuthSessionId(42), playbackRepository = NoMoveBackPlayback,
                        onFilesEvent = preview::dispatch,
                        onAccountSettingsEvent = { error("Unexpected settings mutation") },
                        onPlaybackAuthenticationRequired = { error("Unexpected authentication request") },
                        onSignOut = { error("Unexpected sign out") },
                    )
                }
            }
        }
        compose.runOnIdle {
            assertFalse(preview.files.canNavigateBack)
            assertEquals(FilesFolder.Root.id, preview.files.current.folder.id)
        }
        assertBackRetains(preview, "Files")
        navigate("Account")
        assertBackRetains(preview, "Account")
        compose.runOnIdle { preview.failReadback() }
        navigate("Account")
        assertBackRetains(preview, "Account")
        navigate("Files")
        assertBackRetains(preview, "Files")
        compose.onNodeWithText(preview.item.name).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_OPERATION_RETRY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_FILES_OPERATION_RETRY_TAG).performClick()
        compose.runOnIdle { preview.finishReadback() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(FilesFolderOperation.Idle, preview.files.current.operation)
            assertEquals(1, preview.effects.count { it is FilesBrowserEffect.Move })
            assertEquals(2, preview.effects.count { it is FilesBrowserEffect.CheckMove })
            assertEquals(0, fallbacks)
            backOwner.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, fallbacks)
        }
    }

    private fun assertBackRetains(preview: RootMoveBackPreview, tab: String) {
        compose.runOnIdle {
            val retained = preview.files
            val backEvents = preview.events.count { it == FilesBrowserEvent.NavigateBack }
            backOwner.onBackPressedDispatcher.onBackPressed()
            assertSame(retained, preview.files)
            if (tab == "Files") {
                assertEquals(backEvents + 1, preview.events.count { it == FilesBrowserEvent.NavigateBack })
            }
            assertEquals(0, fallbacks)
        }
        compose.onNode(hasText("Files") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).assertIsSelected()
    }

    private fun navigate(tab: String) {
        compose.onNode(hasText(tab) and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG)))
            .performClick().assertIsSelected()
    }
}

private class RootMoveBackPreview {
    val item = FilesItem(FilesItemId(7), FilesFolder.Root.id, "Pending Move été", PutioFileType.FOLDER, 0, "2026-09-06")
    val settings = AccountSettingsState(
        AccountSettingsContent.Ready(AccountSettingsPreferences(false, true, false, false)),
        AccountSettingsMutation.Idle, 1,
    )
    val appConfig = AndroidAppConfigState(AndroidAppConfigContent.Ready(AndroidAppConfigPreferences()),
        AndroidAppConfigMutation.Idle, 1)
    val effects = mutableListOf<FilesBrowserEffect>()
    val events = mutableListOf<FilesBrowserEvent>()
    var files by mutableStateOf(FilesBrowserState(listOf(
        FilesFolderState(FilesFolder.Root, FilesContent.Ready(listOf(item), FilesPaging.Complete)),
    ), 1))
        private set

    init { dispatch(FilesBrowserEvent.Move(FilesFolder.Root.id, item.id, FilesItemId(8))) }

    fun dispatch(event: FilesBrowserEvent): Boolean {
        events += event
        val transition = FilesBrowserReducer.reduce(files, event)
        files = transition.state
        transition.effect?.let(effects::add)
        return transition.consumed
    }

    fun failReadback() {
        val failure = FilesRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException("Synthetic offline")))
        dispatch(FilesBrowserEvent.MoveFinished((effects.last() as FilesBrowserEffect.Move).requestId, failure))
        dispatch(FilesBrowserEvent.MoveChecked((effects.last() as FilesBrowserEffect.CheckMove).requestId, failure))
    }

    fun finishReadback() {
        val checking = effects.last() as FilesBrowserEffect.CheckMove
        assertEquals(item.id, checking.itemId)
        dispatch(FilesBrowserEvent.MoveChecked(checking.requestId,
            FilesRepositoryResult.Success(item.copy(parentId = FilesItemId(8)))))
        val loading = effects.last() as FilesBrowserEffect.LoadFolder
        assertEquals(FilesFolder.Root.id, loading.folderId)
        dispatch(FilesBrowserEvent.LoadSucceeded(loading.requestId, FilesPage(emptyList(), null)))
    }
}

private object NoMoveBackPlayback : PlaybackRepository {
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        error("Unexpected playback in Move Back proof")
    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
        error("Unexpected playback in Move Back proof")
}
