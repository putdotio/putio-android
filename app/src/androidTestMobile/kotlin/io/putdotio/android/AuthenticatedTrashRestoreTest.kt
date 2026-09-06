package io.putdotio.android

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.SdkFilesRepository
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AndroidAppConfigRepositoryResult
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.trash.SdkTrashRepository
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashController
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.trash.TrashRestoreCheck
import io.putdotio.android.trash.TrashRestoreSubmission
import io.putdotio.sdk.files.FilesListQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class AuthenticatedTrashRestoreTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            assumeTrue("Trash Restore proof requires explicit opt-in",
                InstrumentationRegistry.getArguments().getString("putio.trash.restore.enabled") == "true")
            base.evaluate()
        }
    } }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var controller: TrashController

    @Test
    fun cancelPreservesTrashAndRestoreMakesTheExactItemAvailable() {
        val args = InstrumentationRegistry.getArguments()
        val fixture = TrashRestoreFixture.parse(args.getString("putio.trash.restore.fixture"),
            args.getString("putio.trash.restore.runId"))
        val runtime = MobileOAuthRuntime.get(context)
        // This rule launches a bare activity; PutioApp's session-restoration effect does not run.
        trashRestoreApiCheck("restore existing session") { runtime.authController.restoreSession() }
        val session = runtime.authController.state.value as? MobileAuthState.SignedIn
            ?: throw AssertionError("Proof requires the existing signed-in session")
        assertTrue("fixture account", session.account.userId == fixture.expectedAccountId)
        val repository = TrashRestoreCountingRepository(SdkTrashRepository(runtime.putioClient), fixture.file.id)
        val settings = settings(runtime)
        val config = trashRestoreApiCheck("read app config") {
            (SdkAndroidAppConfigRepository(runtime.putioClient).load() as? AndroidAppConfigRepositoryResult.Success)
                ?.value ?: throw AssertionError("App config read failed")
        }
        preflight(fixture, runtime, repository)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val filesRepository = SdkFilesRepository(runtime.putioClient)
        val files = FilesBrowserController(filesRepository, scope)
        controller = TrashController(repository, scope)
        try {
            compose.setContent {
                val state by files.state.collectAsState()
                TrashRestoreProofShell(controller, state, files::dispatch, session.account, session.sessionId,
                    filesRepository, settings, config)
            }
            compose.onNode(hasText("Account") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).performClick()
            compose.onNodeWithTag(MOBILE_MANAGE_TRASH_TAG).performScrollTo().performClick()
            openConfirmation(fixture.cancel)
            trashRestoreScreenshot("cancel-confirmation")
            compose.onNodeWithText(context.getString(R.string.mobile_action_cancel)).performClick()
            compose.runOnIdle { assertEquals(0, repository.restores) }
            trashRestoreApiCheck("Cancel readback") {
                requireUnavailable(repository.resolveItem(FilesItemId(fixture.cancel.id)))
                assertTrue("Cancel remains in Trash", boundedTrash(repository).any { it.id.value == fixture.cancel.id })
            }
            requireSession(runtime, session)
            assertEquals(settings, settings(runtime))
            openConfirmation(fixture.file)
            compose.onNodeWithTag(MOBILE_TRASH_CONFIRM_TAG).performClick()
            compose.waitUntil(TIMEOUT) {
                controller.state.value.restoreOutcome?.submission?.let { it != TrashRestoreSubmission.SUBMITTING } == true
            }
            assertEquals(1, repository.restores)
            val submitted = checkNotNull(controller.state.value.restoreOutcome)
            assertTrue("Restore rejected before queueing; retain fixture and require a new explicit attempt",
                submitted.submission != TrashRestoreSubmission.REJECTED)
            if (submitted.submission == TrashRestoreSubmission.ACKNOWLEDGED) {
                compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TRASH_OUTCOME_TAG))
                val started = context.getString(R.string.mobile_trash_started)
                if (compose.onAllNodes(hasText(started)).fetchSemanticsNodes().size == 1) {
                    compose.onNodeWithText(started).assertIsDisplayed()
                    trashRestoreScreenshot("restore-started")
                }
            }
            awaitAvailability(repository)
            val restored = checkNotNull(controller.state.value.restoreOutcome?.resolvedItem)
            requireItem(restored, fixture.file)
            assertEquals(fixture.fileSize, restored.sizeBytes)
            compose.onNodeWithTag(MOBILE_TRASH_OUTCOME_TAG).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.mobile_trash_file_available, restored.name))
                .assertIsDisplayed()
            trashRestoreScreenshot("restore-result")
            trashRestoreApiCheck("final Trash and sentinel readback") {
                val trash = boundedTrash(repository)
                assertTrue("restored target absent after complete bounded traversal", trash.none { it.id.value == fixture.file.id })
                assertTrue("Cancel remains in Trash", trash.any { it.id.value == fixture.cancel.id })
                requireUnavailable(repository.resolveItem(FilesItemId(fixture.cancel.id)))
                requireItem(success(repository.resolveItem(FilesItemId(fixture.sentinel.id))), fixture.sentinel)
                val sentinel = runtime.putioClient.files.list(fixture.sentinel.id, FilesListQuery(perPage = 50))
                assertTrue("sentinel remains empty", sentinel.cursor.isNullOrBlank() && sentinel.files.isEmpty())
                assertTrue("final account identity", runtime.putioClient.account.getInfo().userId == fixture.expectedAccountId)
            }
            compose.onNode(hasText("Files") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).performClick()
            filesRow(fixture.container.name).performClick()
            filesRow(fixture.file.name).assertIsDisplayed()
            filesRow(fixture.sentinel.name).assertIsDisplayed()
            trashRestoreScreenshot("restored-files")
            requireSession(runtime, session)
            assertEquals(settings, settings(runtime))
            assertEquals(1, repository.restores)
            assertTrue("bounded exact-ID checks", repository.checks in 1..14)
            Log.i("TrashRestoreProof", "complete id=${fixture.file.id} checks=${repository.checks} hostGetBudget=1")
        } finally {
            controller.close()
            files.close()
            scope.cancel()
        }
    }

    private fun awaitAvailability(repository: TrashRestoreCountingRepository) {
        while (true) {
            val remaining = 60_000 - (SystemClock.elapsedRealtime() - repository.startedAt)
            assertTrue("Restore deadline exhausted; retain ledger and do not replay", remaining > 0)
            compose.waitUntil(minOf(TIMEOUT, remaining)) {
                controller.state.value.restoreOutcome?.check != TrashRestoreCheck.CHECKING
            }
            if (controller.state.value.restoreOutcome?.check == TrashRestoreCheck.AVAILABLE) return
            assertTrue("Restore queued/unresolved; retain ledger and do not replay or clean up",
                repository.checks < 14 && SystemClock.elapsedRealtime() - repository.startedAt < 60_000)
            compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasTestTag(MOBILE_TRASH_CHECK_TAG))
            compose.onNodeWithTag(MOBILE_TRASH_CHECK_TAG).performClick()
            compose.waitForIdle()
        }
    }

    private fun openConfirmation(item: TrashRestoreFixtureItem) {
        var pages = 1
        while (true) {
            compose.waitUntil(TIMEOUT) {
                val content = controller.state.value.content
                content !is TrashContent.Loading && (content !is TrashContent.Loaded || !content.isLoadingMore)
            }
            val loaded = controller.state.value.content as? TrashContent.Loaded
                ?: throw AssertionError("Trash list failed")
            if (loaded.items.any { it.id.value == item.id }) break
            check(loaded.nextCursor != null && pages++ < 4) { "Owned target exceeds bounded Trash proof" }
            val label = context.getString(R.string.mobile_files_load_more)
            compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
        }
        compose.onNodeWithTag(MOBILE_TRASH_LIST_TAG).performScrollToNode(hasText(item.name))
        compose.onNodeWithContentDescription(context.getString(R.string.mobile_trash_actions_named, item.name)).performClick()
        compose.onNodeWithTag(MOBILE_TRASH_ITEM_RESTORE_TAG).performClick()
        compose.onNodeWithTag(MOBILE_TRASH_CONFIRM_TAG).assertIsDisplayed()
        assertEquals(item.id, controller.state.value.confirmation?.id?.value)
    }

    private fun filesRow(name: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val matcher = hasText(name) and hasAnyAncestor(hasTestTag(MOBILE_FILES_LIST_TAG))
        compose.waitUntil(TIMEOUT) {
            try { compose.onNodeWithTag(MOBILE_FILES_LIST_TAG).performScrollToNode(hasText(name)) }
            catch (_: AssertionError) { }
            compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1
        }
        return compose.onNode(matcher)
    }

    private fun settings(runtime: MobileOAuthRuntime): AccountSettingsPreferences = trashRestoreApiCheck("read account settings") {
        (SdkAccountSettingsRepository(runtime.putioClient).load() as? AccountSettingsRepositoryResult.Success)?.value
            ?: throw AssertionError("Account settings read failed")
    }

    private fun preflight(f: TrashRestoreFixture, runtime: MobileOAuthRuntime, repository: TrashRepository) {
        trashRestoreApiCheck("owned Trash fixture preflight") {
            assertTrue("preflight account identity", runtime.putioClient.account.getInfo().userId == f.expectedAccountId)
            requireItem(success(repository.resolveItem(FilesItemId(f.container.id))), f.container)
            requireItem(success(repository.resolveItem(FilesItemId(f.sentinel.id))), f.sentinel)
            requireUnavailable(repository.resolveItem(FilesItemId(f.file.id)))
            requireUnavailable(repository.resolveItem(FilesItemId(f.cancel.id)))
            val trash = boundedTrash(repository)
            for (item in listOf(f.file, f.cancel)) {
                val found = trash.single { it.id.value == item.id }
                assertTrue("owned Trash name", found.name == item.name)
                assertTrue("owned Trash parent", found.parentId?.value == item.parentId)
                assertTrue("owned Trash kind", found.type.raw == item.fileType)
                assertTrue("owned Trash size", found.sizeBytes == if (item == f.file) f.fileSize else 0L)
            }
            for ((folder, expected) in listOf(f.container.id to setOf(f.sentinel.id), f.sentinel.id to emptySet())) {
                val contents = runtime.putioClient.files.list(folder, FilesListQuery(perPage = 50))
                assertTrue("owned folder complete contents", contents.cursor.isNullOrBlank() &&
                    contents.files.map { it.id }.toSet() == expected)
            }
        }
    }

    private suspend fun boundedTrash(repository: TrashRepository): List<TrashItem> {
        var page = success(repository.load())
        val items = page.items.toMutableList()
        val cursors = mutableSetOf<FilesCursor>()
        var requests = 1
        while (page.nextCursor != null) {
            val cursor = checkNotNull(page.nextCursor)
            check(requests++ < 4 && cursors.add(cursor)) { "Trash traversal exceeds bounded proof" }
            page = success(repository.loadNextPage(cursor))
            items += page.items
        }
        assertTrue("Trash traversal unique IDs", items.map { it.id }.toSet().size == items.size)
        return items
    }

    private fun requireSession(runtime: MobileOAuthRuntime, session: MobileAuthState.SignedIn) {
        val current = runtime.authController.state.value as? MobileAuthState.SignedIn
        assertTrue("unchanged signed-in session", current != null && current.sessionId == session.sessionId &&
            current.account.userId == session.account.userId)
    }

    private fun requireItem(item: FilesItem, expected: TrashRestoreFixtureItem) {
        assertTrue("exact owned ID", item.id.value == expected.id)
        assertTrue("exact owned name", item.name == expected.name)
        assertTrue("exact owned parent", item.parentId?.value == expected.parentId)
        assertTrue("exact owned kind", item.type.raw == expected.fileType)
    }

    private fun requireUnavailable(result: FilesRepositoryResult<FilesItem>) {
        val failure = (result as? FilesRepositoryResult.Failure)?.failure as? FilesFailure.ApiRejected
        assertTrue("exact Files GET must be HTTP 404", failure != null && failure.httpStatusCode == 404 && failure.statusCode == 404)
    }

    private fun <T> success(result: FilesRepositoryResult<T>): T = when (result) {
        is FilesRepositoryResult.Success -> result.value
        is FilesRepositoryResult.Failure -> throw AssertionError("Read failed (${result.failure.javaClass.simpleName})")
    }
    private companion object { const val TIMEOUT = 30_000L }
}

/** Counts at the real app boundary; never stores responses, request URLs or credentials. */
private class TrashRestoreCountingRepository(
    private val delegate: TrashRepository,
    private val ownedRestoreId: Long,
) : TrashRepository by delegate {
    @Volatile var restores = 0
        private set
    @Volatile var checks = 0
        private set
    @Volatile var startedAt = 0L
        private set
    // Reserve the fifteenth exact-ID GET for the host's independent final readback.
    private var lastCheckAt = 0L

    override suspend fun restore(itemId: FilesItemId): FilesRepositoryResult<Unit> {
        restores += 1
        check(itemId.value == ownedRestoreId && restores == 1) { "Unowned or duplicate Restore refused" }
        startedAt = SystemClock.elapsedRealtime()
        Log.i("TrashRestoreProof", "restore id=${itemId.value} count=$restores state=pending startedElapsedMs=$startedAt")
        val result = delegate.restore(itemId)
        Log.i("TrashRestoreProof", "restore id=${itemId.value} count=$restores state=" +
            if (result is FilesRepositoryResult.Success) "acknowledged" else "unknown")
        return result
    }

    override suspend fun resolveItem(itemId: FilesItemId): FilesRepositoryResult<FilesItem> {
        if (itemId.value == ownedRestoreId && restores > 0) {
            check(checks < 14) { "Restore check budget exhausted; retain fixture" }
            val remainingSpacing = 2_000 - (SystemClock.elapsedRealtime() - lastCheckAt)
            if (checks > 0 && remainingSpacing > 0) delay(remainingSpacing)
            check(SystemClock.elapsedRealtime() - startedAt < 60_000) { "Restore deadline exhausted; retain fixture" }
            checks += 1
            lastCheckAt = SystemClock.elapsedRealtime()
            Log.i("TrashRestoreProof", "get id=${itemId.value} count=$checks atElapsedMs=$lastCheckAt")
        }
        return if (itemId.value == ownedRestoreId && restores > 0) {
            withTimeout((60_000 - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(1)) {
                delegate.resolveItem(itemId)
            }
        } else delegate.resolveItem(itemId)
    }
}
