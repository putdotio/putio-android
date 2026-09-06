package io.putdotio.android

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashPage
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.trash.TrashRestoreCheck
import io.putdotio.android.trash.TrashRestoreSubmission
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.files.PutioFileType
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class TrashRestoreUiProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            assumeTrue("Synthetic Trash Restore requires opt-in",
                InstrumentationRegistry.getArguments().getString("putio.trash.restore.ui.enabled") == "true")
            base.evaluate()
        }
    } }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var backOwner: OnBackPressedDispatcherOwner
    private var fallbacks = 0

    @Test
    fun queuedRestoreRetainsRecoveryAndRetriesOnlyReads() = withShell { repository, controller ->
        openTrash()
        compose.onNodeWithText(context.getString(R.string.mobile_trash_loading)).assertIsDisplayed()
        repository.completeList(0, TrashPage(emptyList(), null, 0, 0))
        text(R.string.mobile_trash_empty_title).assertIsDisplayed()
        trashRestoreScreenshot("synthetic-empty")
        text(R.string.mobile_action_refresh).performClick()
        repository.failList(1)
        await { (controller.state.value.content as? TrashContent.Loaded)?.refreshFailure != null }
        trashRestoreScreenshot("synthetic-error")
        text(R.string.mobile_action_retry).performClick()
        repository.completeList(2, TrashPage(listOf(repository.item), FilesCursor("page-a"), 2, 40))
        loadMore()
        repository.failList(3)
        await { (controller.state.value.content as? TrashContent.Loaded)?.pageFailure != null }
        text(R.string.mobile_action_retry).performClick()
        // First-seen ID wins; continuation aggregates are absent. Repeated cursor must terminate.
        repository.completeList(4, TrashPage(listOf(repository.item.copy(name = "Stale duplicate")), FilesCursor("page-a")))
        await { (controller.state.value.content as? TrashContent.Loaded)?.nextCursor == null }
        val loaded = controller.state.value.content as TrashContent.Loaded
        assertEquals(listOf(repository.item), loaded.items)
        assertEquals(2, loaded.total)
        assertEquals(40L, loaded.trashSizeBytes)
        assertEquals(listOf(null, null, null, FilesCursor("page-a"), FilesCursor("page-a")), repository.listCursors)
        select(repository.item)
        compose.onNodeWithText(context.getString(R.string.mobile_action_cancel)).performClick()
        assertEquals(0, repository.restores.size)
        select(repository.item)
        val confirmationId = checkNotNull(controller.state.value.confirmationId)
        compose.onNodeWithTag(MOBILE_TRASH_CONFIRM_TAG).performClick()
        await { repository.restores.size == 1 }
        compose.runOnIdle { assertFalse(controller.dispatch(TrashEvent.ConfirmRestore(confirmationId))) }
        repository.restores[0].complete(FilesRepositoryResult.Success(Unit))
        repository.completeCheck(0, FilesRepositoryResult.Failure(notFound()))
        await { controller.state.value.restoreOutcome?.check == TrashRestoreCheck.UNAVAILABLE }
        text(R.string.mobile_trash_started).assertIsDisplayed()
        assertBackRetains(controller)
        openTrash()
        trashRestoreScreenshot("synthetic-recovery")
        checkStatus()
        repository.completeCheck(1, FilesRepositoryResult.Failure(offline()))
        await { controller.state.value.restoreOutcome?.check == TrashRestoreCheck.FAILED }
        assertEquals(1, repository.restores.size)
        checkStatus()
        val restored = FilesItem(repository.item.id, FilesFolder.Root.id, "Collision renamed été",
            repository.item.type, 40, "2026-09-06")
        repository.completeCheck(2, FilesRepositoryResult.Success(restored))
        repository.failList(5)
        await { (controller.state.value.content as? TrashContent.Loaded)?.refreshFailure != null }
        assertEquals(TrashRestoreCheck.AVAILABLE, controller.state.value.restoreOutcome?.check)
        assertEquals(restored, controller.state.value.restoreOutcome?.resolvedItem)
        assertFalse(controller.state.value.canRestore(repository.item.id))
        text(R.string.mobile_action_retry).performClick()
        repository.completeList(6, TrashPage(emptyList(), null, 0, 0))
        text(R.string.mobile_trash_empty_title).assertIsDisplayed()
        assertEquals(1, repository.restores.size)
        assertEquals(listOf(repository.item.id, repository.item.id, repository.item.id), repository.checkIds)
    }

    @Test
    fun ambiguousRestoreAndAuthenticationFailureNeverRepeatMutation() = withShell { repository, controller ->
        openTrash()
        repository.completeList(0, TrashPage(listOf(repository.item), null, 1, 40))
        select(repository.item)
        compose.onNodeWithTag(MOBILE_TRASH_CONFIRM_TAG).performClick()
        await { repository.restores.size == 1 }
        repository.restores[0].complete(FilesRepositoryResult.Failure(offline()))
        repository.completeCheck(0, FilesRepositoryResult.Failure(offline()))
        await { controller.state.value.restoreOutcome?.check == TrashRestoreCheck.FAILED }
        assertEquals(TrashRestoreSubmission.UNCERTAIN, controller.state.value.restoreOutcome?.submission)
        text(R.string.mobile_trash_uncertain).assertIsDisplayed()
        assertBackRetains(controller)
        openTrash()
        checkStatus()
        val authentication = FilesFailure.AuthenticationRequired(apiFailure(401))
        repository.completeCheck(1, FilesRepositoryResult.Failure(authentication))
        await { controller.state.value.authenticationFailure != null }
        assertNotNull(controller.state.value.restoreOutcome)
        compose.runOnIdle {
            assertFalse(controller.dispatch(TrashEvent.SelectRestore(repository.item.id)))
            assertFalse(controller.dispatch(TrashEvent.ConfirmRestore(1)))
        }
        assertEquals(1, repository.restores.size)
        assertEquals(2, repository.checkIds.size)
    }

    private fun withShell(block: (TrashRestoreControlledRepository, TrashController) -> Unit) {
        val repository = TrashRestoreControlledRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = TrashController(repository, scope)
        val files = FilesBrowserState(listOf(FilesFolderState(FilesFolder.Root,
            FilesContent.Ready(emptyList(), FilesPaging.Complete))), 1)
        try {
            compose.setContent {
                val owner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
                DisposableEffect(owner) {
                    backOwner = owner
                    val fallback = object : OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() { fallbacks += 1 }
                    }
                    owner.onBackPressedDispatcher.addCallback(owner, fallback)
                    onDispose { fallback.remove() }
                }
                TrashRestoreProofShell(controller, files, { false })
            }
            block(repository, controller)
        } finally { controller.close(); scope.cancel() }
    }

    private fun assertBackRetains(controller: TrashController) {
        compose.runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, backOwner.lifecycle.currentState)
            assertTrue(controller.state.value.hasPendingRestore)
            backOwner.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNode(hasText("Account") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).assertIsSelected()
        for (tab in listOf("Account", "Files")) {
            compose.onNode(hasText(tab) and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).performClick().assertIsSelected()
            compose.runOnIdle {
                assertEquals(Lifecycle.State.RESUMED, backOwner.lifecycle.currentState)
                backOwner.onBackPressedDispatcher.onBackPressed()
                assertEquals(0, fallbacks)
                assertTrue(controller.state.value.hasPendingRestore)
            }
        }
    }

    private fun openTrash() {
        compose.onNode(hasText("Account") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).performClick()
        compose.onNodeWithTag(MOBILE_MANAGE_TRASH_TAG).performScrollTo().performClick()
    }
    private fun select(item: TrashItem) {
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText(item.name))
        compose.onNodeWithContentDescription(context.getString(R.string.mobile_trash_restore_named, item.name)).performClick()
    }
    private fun loadMore() = text(R.string.mobile_files_load_more).performClick()
    private fun checkStatus() {
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TRASH_CHECK_TAG))
        compose.onNodeWithTag(MOBILE_TRASH_CHECK_TAG).performClick()
    }
    private fun text(resource: Int): androidx.compose.ui.test.SemanticsNodeInteraction {
        val label = context.getString(resource)
        compose.waitForIdle()
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText(label))
        return compose.onNodeWithText(label)
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000, condition)
    private fun offline() = FilesFailure.Unexpected(IllegalStateException("Synthetic offline"))
    private fun notFound() = FilesFailure.ApiRejected(404, "FileNotFound", apiFailure(404))
    private fun apiFailure(status: Int) = PutioApiException(
        request = PutioRequestData("GET", "/files/7"), resolvedStatusCode = status,
        resolvedErrorType = if (status == 404) "FileNotFound" else "AuthenticationRequired",
        envelope = PutioApiErrorEnvelope(statusCode = status), responseBody = "{}", message = "Synthetic failure",
    )
}

private class TrashRestoreControlledRepository : TrashRepository {
    val item = TrashItem(FilesItemId(7), FilesItemId(12), "Restore été 東京 — missing dates", PutioFileType.FILE, 40)
    val lists = CopyOnWriteArrayList<CompletableDeferred<FilesRepositoryResult<TrashPage>>>()
    val listCursors = CopyOnWriteArrayList<FilesCursor?>()
    val restores = CopyOnWriteArrayList<CompletableDeferred<FilesRepositoryResult<Unit>>>()
    val checkIds = CopyOnWriteArrayList<FilesItemId>()
    private val checks = CopyOnWriteArrayList<CompletableDeferred<FilesRepositoryResult<FilesItem>>>()
    override suspend fun load() = list(null)
    override suspend fun loadNextPage(cursor: FilesCursor) = list(cursor)
    private suspend fun list(cursor: FilesCursor?): FilesRepositoryResult<TrashPage> {
        listCursors += cursor
        val result = CompletableDeferred<FilesRepositoryResult<TrashPage>>()
        lists += result
        return result.await()
    }
    override suspend fun restore(itemId: FilesItemId): FilesRepositoryResult<Unit> {
        check(itemId == item.id)
        val result = CompletableDeferred<FilesRepositoryResult<Unit>>()
        restores += result
        return result.await()
    }
    override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> {
        checkIds += itemId
        val result = CompletableDeferred<FilesRepositoryResult<FilesItem>>()
        checks += result
        return result.await()
    }
    fun completeList(index: Int, page: TrashPage) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        check(lists.size > index) { "Expected list request $index" }
        lists[index].complete(FilesRepositoryResult.Success(page))
    }
    fun failList(index: Int) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        check(lists.size > index) { "Expected list request $index" }
        lists[index].complete(FilesRepositoryResult.Failure(FilesFailure.Unexpected(IllegalStateException("Synthetic offline"))))
    }
    fun completeCheck(index: Int, result: FilesRepositoryResult<FilesItem>) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        check(checks.size > index) { "Expected exact-ID request $index" }
        checks[index].complete(result)
    }
}
