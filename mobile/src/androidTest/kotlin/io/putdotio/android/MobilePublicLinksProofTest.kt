package io.putdotio.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesContent
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
import io.putdotio.android.sharing.PublicLinksController
import io.putdotio.android.sharing.mobilePublicLinkCopyTag
import io.putdotio.android.sharing.mobilePublicLinkRevokeTag
import io.putdotio.android.sharing.mobilePublicLinkShareTag
import io.putdotio.android.sharing.publicLink
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Synthetic proof of Exclusive access through the production Files route, public links controller,
 * sheet, share chooser and Account list over faked repositories; no API calls.
 */
@RunWith(AndroidJUnit4::class)
class MobilePublicLinksProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic public links proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.publiclinks.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun ownedItemsCreateCopyShareAndRevokeLinksThatAccountLists() {
        val links = FakePublicLinksRepository(listOf(publicLink(id = 3L, fileId = VIDEO.id.value, name = VIDEO.name)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val files = FilesBrowserController(ProofFiles, scope)
        val controller = PublicLinksController(links, scope)
        var showAccount by mutableStateOf(false)
        try {
            compose.setContent {
                val filesState by files.state.collectAsState()
                val linksState by controller.state.collectAsState()
                val publicLinks = MobilePublicLinks(linksState, controller::dispatch)
                PutioTheme {
                    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        if (showAccount) {
                            MobilePublicLinksScreen(publicLinks)
                        } else {
                            MobileFilesRoute(
                                state = filesState,
                                repository = ProofFiles,
                                onEvent = files::dispatch,
                                onPlayMedia = {},
                                confirmedTrashEnabled = true,
                                onAuthenticationRequired = {},
                                onDownloadItem = {},
                                onShareItem = {},
                                publicLinks = publicLinks,
                            )
                        }
                    }
                }
            }
            compose.waitUntil(5_000) { files.state.value.current.content is FilesContent.Ready }

            compose.onNodeWithContentDescription("Actions for ${FRIENDS_FILE.name}").performClick()
            compose.onNodeWithTag(MOBILE_FILES_PUBLIC_LINK_ACTION_TAG).assertDoesNotExist()
            screenshot("01-shared-file-no-exclusive-access")
            compose.onNodeWithText("Download to this device").performClick()

            compose.onNodeWithContentDescription("Actions for ${VIDEO.name}").performClick()
            screenshot("02-owned-file-actions")
            compose.onNodeWithTag(MOBILE_FILES_PUBLIC_LINK_ACTION_TAG).performClick()
            awaitTag(mobilePublicLinkCopyTag(links.links.first().id))
            screenshot("03-sheet-existing-link")

            compose.onNodeWithTag(MOBILE_PUBLIC_LINK_CREATE_TAG).performClick()
            compose.onNodeWithText("Link created. Copy or share it below.").assertIsDisplayed()
            val created = links.links.last()
            check(links.created == listOf(VIDEO.id)) { "Created ${links.created}" }
            screenshot("04-link-created")

            compose.onNodeWithTag(mobilePublicLinkCopyTag(created.id)).performClick()
            compose.onNodeWithText("Copied").assertIsDisplayed()
            screenshot("05-copied")

            compose.onNodeWithTag(mobilePublicLinkShareTag(created.id)).performClick()
            SystemClock.sleep(CHOOSER_SETTLE_MS)
            screenshot("06-share-chooser")
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            SystemClock.sleep(CHOOSER_SETTLE_MS)

            compose.onNodeWithTag(mobilePublicLinkRevokeTag(created.id)).performClick()
            compose.onNodeWithText("Revoke this link?").assertIsDisplayed()
            screenshot("07-revoke-confirm")
            compose.onNodeWithTag(MOBILE_PUBLIC_LINK_REVOKE_CONFIRM_TAG).performClick()
            compose.onNodeWithText("Link is revoked.").assertIsDisplayed()
            compose.onNodeWithTag(mobilePublicLinkCopyTag(created.id)).assertDoesNotExist()
            check(links.revoked == listOf(created.id)) { "Revoked ${links.revoked}" }
            screenshot("08-revoked")

            links.onCreate = { PutioResult.Failure(dailyLimit()) }
            compose.onNodeWithTag(MOBILE_PUBLIC_LINK_CREATE_TAG).performClick()
            compose.onNodeWithText("You have reached the daily public link creation limit.").assertIsDisplayed()
            screenshot("09-daily-limit")

            links.links += publicLink(id = 9L, fileId = FOLDER.id.value, name = FOLDER.name, type = PutioFileType.FOLDER)
            showAccount = true
            awaitTag(MOBILE_PUBLIC_LINKS_LIST_TAG)
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(FOLDER.name).assertIsDisplayed() }.isSuccess }
            screenshot("10-account-list")
        } finally {
            controller.close()
            files.close()
            scope.cancel()
        }
    }

    private fun awaitTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun screenshot(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = UUID.fromString(
            requireNotNull(InstrumentationRegistry.getArguments().getString("putio.publiclinks.runId")),
        )
        val directory = File(
            requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "public-links-proof-$runId",
        )
        check(directory.mkdirs() || directory.isDirectory)
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        // Holds each state long enough for a screen recording to show it.
        SystemClock.sleep(STEP_PAUSE_MS)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

private const val STEP_PAUSE_MS = 1_200L
private const val CHOOSER_SETTLE_MS = 2_000L

private fun item(id: Long, name: String, type: PutioFileType) =
    FilesItem(FilesItemId(id), FilesItemId(0L), name, type, 128_000_000, "2026-10-01T12:00:00Z")

private val VIDEO = item(14, "Harbor film.mp4", PutioFileType.VIDEO)
private val FOLDER = item(20, "Archive été 東京", PutioFileType.FOLDER)
private val FRIENDS_FILE = item(13, "Friend's film.mp4", PutioFileType.VIDEO).copy(isShared = true)

private object ProofFiles : StubFilesRepository() {
    override suspend fun loadFolder(folderId: FilesItemId) =
        PutioResult.Success(FilesPage(listOf(FOLDER, VIDEO, FRIENDS_FILE), null))
}

private fun dailyLimit(): PutioFailure {
    val type = "PUBLIC_SHARE_DAILY_TOTAL_LINK_COUNT_EXCEEDED"
    return PutioApiException(
        request = PutioRequestData("POST", "https://api.put.io/v2/public_share/14"), resolvedStatusCode = 403,
        resolvedErrorType = type, envelope = PutioApiErrorEnvelope(errorType = type, statusCode = 403),
        responseBody = "{}", message = "Daily limit",
    ).toPutioFailure()
}
