package io.putdotio.android

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.MOBILE_ACCOUNT_LIST_TAG
import io.putdotio.android.account.MOBILE_MANAGE_PUBLIC_LINKS_TAG
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.MOBILE_FILES_PUBLIC_LINK_ACTION_TAG
import io.putdotio.android.files.MobileFilesRoute
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.sharing.FakePublicLinksRepository
import io.putdotio.android.sharing.MOBILE_PUBLIC_LINKS_LIST_TAG
import io.putdotio.android.sharing.MOBILE_PUBLIC_LINK_CREATE_TAG
import io.putdotio.android.sharing.MOBILE_PUBLIC_LINK_REVOKE_CONFIRM_TAG
import io.putdotio.android.sharing.MOBILE_PUBLIC_LINK_SHEET_TAG
import io.putdotio.android.sharing.MobilePublicLinks
import io.putdotio.android.sharing.MobilePublicLinksScreen
import io.putdotio.android.sharing.PublicLinkId
import io.putdotio.android.sharing.PublicLinksController
import io.putdotio.android.sharing.mobilePublicLinkCopyTag
import io.putdotio.android.sharing.mobilePublicLinkRevokeTag
import io.putdotio.android.sharing.publicLink
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PutioFolderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobilePublicLinksTest {
    @get:Rule
    val compose = createComposeRule()

    private val video = item(7L, "Harbor film.mp4", PutioFileType.VIDEO)
    private val folder = item(8L, "Archive été 東京", PutioFileType.FOLDER)
    private val friendsFile = item(9L, "Friend's film.mp4", PutioFileType.VIDEO, isShared = true)
    private val sharedRoot = item(10L, "Items shared with you", PutioFileType.FOLDER, PutioFolderType.SHARED_ROOT)

    @Test
    fun onlyTheViewersOwnItemsOfferExclusiveAccess() {
        val items = listOf(video, folder, friendsFile, sharedRoot)
        compose.setContent { FilesWithLinks(FakePublicLinksRepository(), items) }

        compose.onNodeWithContentDescription("Actions for ${friendsFile.name}").performClick()
        compose.onNodeWithTag(MOBILE_FILES_PUBLIC_LINK_ACTION_TAG).assertDoesNotExist()
        compose.onNodeWithText("Download to this device").performClick()
        compose.onNodeWithContentDescription("Actions for ${sharedRoot.name}").assertDoesNotExist()

        compose.onNodeWithContentDescription("Actions for ${folder.name}").performClick()
        compose.onNodeWithTag(MOBILE_FILES_PUBLIC_LINK_ACTION_TAG).performClick()
        compose.onNode(hasText(folder.name) and hasAnyAncestorTag(MOBILE_PUBLIC_LINK_SHEET_TAG)).assertIsDisplayed()
    }

    @Test
    fun theSheetShowsTheItemsLinksThenCreatesAndCopiesANewOne() {
        val existing = publicLink(id = 3L, fileId = video.id.value, token = "existing")
        val otherItem = publicLink(id = 4L, fileId = folder.id.value, token = "other")
        val repository = FakePublicLinksRepository(listOf(existing, otherItem))
        compose.setContent { FilesWithLinks(repository, listOf(video, folder)) }

        openSheet(video)
        compose.onNodeWithTag(mobilePublicLinkCopyTag(existing.id)).assertIsDisplayed()
        compose.onNodeWithTag(mobilePublicLinkCopyTag(otherItem.id)).assertDoesNotExist()

        compose.onNodeWithTag(MOBILE_PUBLIC_LINK_CREATE_TAG).performClick()
        compose.onNodeWithText("Link created. Copy or share it below.").assertIsDisplayed()
        val created = repository.links.last()
        compose.onNodeWithTag(MOBILE_PUBLIC_LINK_SHEET_TAG)
            .performScrollToNode(hasTestTag(mobilePublicLinkCopyTag(created.id)))
        compose.onNodeWithTag(mobilePublicLinkCopyTag(created.id)).performClick()

        compose.onNodeWithText("Copied").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(video.id), repository.created)
            assertEquals(created.url.value, clipboardText())
            assertEquals("https://app.put.io/exclusive-access/token-${created.id.value}", clipboardText())
            // A bearer link: Android 13+ hides it from the clipboard preview.
            val extras = clipboard().primaryClip?.description?.extras
            assertTrue(extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
        }
    }

    @Test
    fun revokeAsksFirstThenRemovesOnlyThatLink() {
        val revoked = publicLink(id = 3L, fileId = video.id.value)
        val kept = publicLink(id = 2L, fileId = video.id.value)
        val repository = FakePublicLinksRepository(listOf(kept, revoked))
        compose.setContent { FilesWithLinks(repository, listOf(video)) }
        openSheet(video)

        compose.onNodeWithTag(mobilePublicLinkRevokeTag(revoked.id)).performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag(mobilePublicLinkCopyTag(revoked.id)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<PublicLinkId>(), repository.revoked) }

        compose.onNodeWithTag(mobilePublicLinkRevokeTag(revoked.id)).performClick()
        compose.onNodeWithText("Anyone who has the link to “${video.name}” loses access.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_PUBLIC_LINK_REVOKE_CONFIRM_TAG).performClick()

        compose.onNodeWithText("Link is revoked.").assertIsDisplayed()
        compose.onNodeWithTag(mobilePublicLinkCopyTag(revoked.id)).assertDoesNotExist()
        compose.onNodeWithTag(mobilePublicLinkCopyTag(kept.id)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(revoked.id), repository.revoked) }
    }

    @Test
    fun aRefusedLinkReadsAsWebWordsItElsePutiosReason() {
        val repository = FakePublicLinksRepository()
        repository.onCreate = {
            PutioResult.Failure(refusal(403, "PUBLIC_SHARE_DAILY_TOTAL_LINK_COUNT_EXCEEDED", "Daily limit"))
        }
        compose.setContent { FilesWithLinks(repository, listOf(video)) }
        openSheet(video)

        compose.onNodeWithTag(MOBILE_PUBLIC_LINK_CREATE_TAG).performClick()
        compose.onNodeWithText("You have reached the daily public link creation limit.").assertIsDisplayed()

        repository.onCreate = { PutioResult.Failure(refusal(400, "BadRequest", "This file is still being processed.")) }
        compose.onNodeWithTag(MOBILE_PUBLIC_LINK_CREATE_TAG).performClick()
        compose.onNodeWithText("Couldn’t create a link. This file is still being processed.").assertIsDisplayed()
        compose.onAllNodesWithText("You have reached the daily public link creation limit.").assertCountEquals(0)
    }

    @Test
    fun createWaitsForTheItemsLinksToLoad() {
        val repository = FakePublicLinksRepository()
        repository.onList = { PutioResult.Failure(PutioFailure.NetworkUnavailable(IllegalStateException("offline"))) }
        compose.setContent { FilesWithLinks(repository, listOf(video)) }
        openSheet(video)

        compose.onNodeWithText("Check your connection and try again.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_PUBLIC_LINK_CREATE_TAG).assertIsNotEnabled()

        repository.onList = { PutioResult.Success(emptyList()) }
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("No links for this item yet.").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_PUBLIC_LINK_CREATE_TAG).performClick()
        compose.runOnIdle { assertEquals(listOf(video.id), repository.created) }
    }

    @Test
    fun accountListsEveryLinkWithItsItemAndRevokesFromThere() {
        val first = publicLink(id = 3L, fileId = video.id.value, name = video.name)
        val second = publicLink(id = 4L, fileId = folder.id.value, name = folder.name, type = PutioFileType.FOLDER)
        val repository = FakePublicLinksRepository(listOf(first, second))
        compose.setContent {
            PutioTheme { WithLinks(repository) { MobilePublicLinksScreen(it) } }
        }

        compose.onNodeWithTag(MOBILE_PUBLIC_LINKS_LIST_TAG).assertIsDisplayed()
        compose.onNodeWithText(video.name).assertIsDisplayed()
        compose.onNodeWithText(folder.name).assertIsDisplayed()
        compose.onNodeWithTag(mobilePublicLinkRevokeTag(second.id)).performClick()
        compose.onNodeWithTag(MOBILE_PUBLIC_LINK_REVOKE_CONFIRM_TAG).performClick()
        compose.onNodeWithText(folder.name).assertDoesNotExist()
        compose.onNodeWithTag(mobilePublicLinkRevokeTag(first.id)).performClick()
        compose.onNodeWithTag(MOBILE_PUBLIC_LINK_REVOKE_CONFIRM_TAG).performClick()

        compose.onNodeWithText("You don’t have any shared links.").assertIsDisplayed()
    }

    @Test
    fun accountOpensExclusiveAccessAsASubpageThatBackLeaves() {
        val repository = FakePublicLinksRepository(listOf(publicLink(id = 3L, name = video.name)))
        compose.setContent {
            PutioTheme {
                val scope = rememberCoroutineScope()
                MobileShell(
                    filesState = loaded(emptyList()),
                    publicLinksController = remember { PublicLinksController(repository, scope) },
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = MobileAccount(userId = 42L, username = "user", email = "user@example.com"),
                    playbackRepository = NoPlayback,
                    playbackPlayerFactory = NoAudioSessionFactory,
                    sessionId = MobileAuthSessionId(1L),
                    onFilesEvent = { true },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }

        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_MANAGE_PUBLIC_LINKS_TAG))
        compose.onNodeWithTag(MOBILE_MANAGE_PUBLIC_LINKS_TAG).performClick()

        compose.onNodeWithText(video.name).assertIsDisplayed()
        compose.onNodeWithText("Exclusive access").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(MOBILE_PUBLIC_LINKS_LIST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).assertIsDisplayed()
    }

    @Composable
    private fun FilesWithLinks(repository: FakePublicLinksRepository, items: List<FilesItem>) {
        PutioTheme {
            WithLinks(repository) { links ->
                MobileFilesRoute(
                    state = loaded(items),
                    repository = FilesStub,
                    onEvent = { true },
                    onPlayMedia = {},
                    confirmedTrashEnabled = true,
                    onAuthenticationRequired = {},
                    onDownloadItem = {},
                    publicLinks = links,
                )
            }
        }
    }

    @Composable
    private fun WithLinks(repository: FakePublicLinksRepository, content: @Composable (MobilePublicLinks) -> Unit) {
        val scope = rememberCoroutineScope()
        val controller = remember { PublicLinksController(repository, scope) }
        val state by controller.state.collectAsState()
        content(MobilePublicLinks(state, controller::dispatch))
    }

    private object NoPlayback : PlaybackRepository {
        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
            error("Unexpected playback")

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = PlaybackNextResult.Ended
    }

    private object FilesStub : StubFilesRepository() {
        override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> = error("Unexpected read")
    }

    private fun openSheet(item: FilesItem) {
        compose.onNodeWithContentDescription("Actions for ${item.name}").performClick()
        compose.onNodeWithTag(MOBILE_FILES_PUBLIC_LINK_ACTION_TAG).performClick()
        compose.onNodeWithTag(MOBILE_PUBLIC_LINK_SHEET_TAG).assertIsDisplayed()
    }

    private fun clipboard(): ClipboardManager =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(ClipboardManager::class.java)

    private fun clipboardText(): String? = clipboard().primaryClip?.getItemAt(0)?.text?.toString()

    private fun hasAnyAncestorTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))

    private fun refusal(status: Int, type: String, message: String): PutioFailure =
        putioRefusal(
            status,
            """{"error_type":"$type","error_message":"$message","status":"ERROR","status_code":$status}""",
        ).toPutioFailure()

    private fun loaded(items: List<FilesItem>): FilesBrowserState {
        val initial = FilesBrowserReducer.start()
        return FilesBrowserReducer.reduce(
            initial.state,
            FilesBrowserEvent.LoadSucceeded(checkNotNull(initial.effect).requestId, FilesPage(items, null)),
        ).state
    }

    private fun item(
        id: Long,
        name: String,
        type: PutioFileType,
        folderType: PutioFolderType = PutioFolderType.REGULAR,
        isShared: Boolean = false,
    ) = FilesItem(
        FilesItemId(id), FilesFolder.Root.id, name, type, 1L, "2026-09-06",
        isShared = isShared, folderType = folderType,
    )
}
