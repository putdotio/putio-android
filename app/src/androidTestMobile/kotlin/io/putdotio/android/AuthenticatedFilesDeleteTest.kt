package io.putdotio.android

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.files.FilesListQuery
import io.putdotio.sdk.files.FilesSearchQuery
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.trash.TrashContinueQuery
import io.putdotio.sdk.trash.TrashListQuery
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class AuthenticatedFilesDeleteTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Delete proof requires explicit opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.delete.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun authenticatedDeletePreservesSessionAndCancel() {
        val fixture = DeleteProofFixture.parse(InstrumentationRegistry.getArguments().getString("putio.delete.fixture"))
        val runtime = MobileOAuthRuntime.get(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.waitUntil(TIMEOUT) {
            when (runtime.authController.state.value) {
                MobileAuthState.Initializing, MobileAuthState.RestoringSession, is MobileAuthState.ValidatingSession -> false
                else -> true
            }
        }
        val session = runtime.authController.state.value as? MobileAuthState.SignedIn
            ?: throw AssertionError("Proof requires an existing signed-in session")
        check(session.account.userId == fixture.expectedAccountId)
        apiCheck("fixture preflight") {
            check(runtime.putioClient.account.getInfo().userId == fixture.expectedAccountId)
            check(runtime.putioClient.account.getSettings().trashEnabled == fixture.expectedTrashEnabled)
            val container = runtime.putioClient.files.get(fixture.containerId)
            check(container.id == fixture.containerId && container.name == fixture.containerName &&
                container.fileType == PutioFileType.FOLDER)
            val search = runtime.putioClient.files.search(FilesSearchQuery(fixture.containerName, perPage = 50))
            check(search.cursor.isNullOrBlank() && search.files.filter { it.name == fixture.containerName }
                .singleOrNull()?.id == fixture.containerId)
            val children = runtime.putioClient.files.list(fixture.containerId, FilesListQuery(perPage = 50))
            check(children.cursor.isNullOrBlank() && children.files.size == 2)
            for ((id, name) in listOf(fixture.actionItemId to fixture.actionName, fixture.cancelItemId to fixture.cancelName)) {
                val item = runtime.putioClient.files.get(id)
                check(item.id == id && item.name == name && item.parentId == fixture.containerId &&
                    item.fileType == PutioFileType.FOLDER)
                check(children.files.filter { it.name == name }.singleOrNull()?.id == id)
                val contents = runtime.putioClient.files.list(id, FilesListQuery(perPage = 50))
                check(contents.cursor.isNullOrBlank() && contents.files.isEmpty())
                check(!inTrash(runtime, id))
            }
        }
        requireSession(runtime, session)
        openContainer(fixture)
        compose.onNode(row(MOBILE_FILES_LIST_TAG, fixture.actionName)).performClick()
        waitFor(hasText("This folder is empty."))
        deleteProofScreenshot("empty")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        row(MOBILE_FILES_LIST_TAG, fixture.actionName)
        sortDescending(fixture, runtime)
        confirmOrCancel(fixture, fixture.cancelName, cancel = true)
        apiCheck("cancel readback") {
            val item = runtime.putioClient.files.get(fixture.cancelItemId)
            check(item.id == fixture.cancelItemId && item.name == fixture.cancelName && item.parentId == fixture.containerId)
            check(!inTrash(runtime, fixture.cancelItemId))
            check(runtime.putioClient.account.getSettings().trashEnabled == fixture.expectedTrashEnabled)
        }
        requireSession(runtime, session)
        confirmOrCancel(fixture, fixture.actionName, cancel = false)
        waitFor(hasText(compose.activity.getString(R.string.mobile_files_delete_unavailable, fixture.actionName)))
        row(MOBILE_FILES_LIST_TAG, fixture.cancelName)
        check(compose.onAllNodes(hasText(fixture.actionName) and hasAnyAncestor(hasTestTag(MOBILE_FILES_LIST_TAG)))
            .fetchSemanticsNodes().isEmpty())
        deleteProofScreenshot("result")
        apiCheck("delete readback") {
            try {
                runtime.putioClient.files.get(fixture.actionItemId)
                error("Selected item remains readable")
            } catch (error: PutioOperationException) {
                val api = error.underlyingError as? PutioApiException ?: throw error
                check(api.httpStatusCode == 404) { "Exact item read did not establish unavailability" }
            }
            check(inTrash(runtime, fixture.actionItemId) == fixture.expectedTrashEnabled)
            val children = runtime.putioClient.files.list(fixture.containerId, FilesListQuery(perPage = 50))
            check(children.cursor.isNullOrBlank() && children.files.singleOrNull()?.id == fixture.cancelItemId)
            check(runtime.putioClient.account.getInfo().userId == fixture.expectedAccountId)
            check(runtime.putioClient.account.getSettings().trashEnabled == fixture.expectedTrashEnabled)
        }
        requireSession(runtime, session)
    }

    private fun openContainer(fixture: DeleteProofFixture) {
        compose.onNodeWithText(compose.activity.getString(R.string.mobile_destination_search)).performClick()
        waitFor(hasTestTag(MOBILE_SEARCH_FIELD_TAG))
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).performTextReplacement(fixture.containerName)
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).performImeAction()
        compose.onNode(row(MOBILE_SEARCH_RESULTS_TAG, fixture.containerName)).performClick()
        row(MOBILE_FILES_LIST_TAG, fixture.actionName)
    }

    private fun sortDescending(fixture: DeleteProofFixture, runtime: MobileOAuthRuntime) {
        compose.onNodeWithTag(MOBILE_FILES_SORT_TAG).performClick()
        val label = compose.activity.getString(R.string.mobile_files_sort_name_descending)
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onNodeWithTag(MOBILE_FILES_SORT_TAG).fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription] == label
        }
        val action = compose.onNode(row(MOBILE_FILES_LIST_TAG, fixture.actionName)).fetchSemanticsNode()
        val cancel = compose.onNode(row(MOBILE_FILES_LIST_TAG, fixture.cancelName)).fetchSemanticsNode()
        check(cancel.boundsInRoot.top < action.boundsInRoot.top) { "Descending order was not rendered" }
        apiCheck("sort readback") {
            val container = runtime.putioClient.files.get(fixture.containerId)
            check(container.id == fixture.containerId && container.sortBy == "NAME_DESC")
        }
    }

    private fun confirmOrCancel(fixture: DeleteProofFixture, name: String, cancel: Boolean) {
        if (cancel) compose.onNode(row(MOBILE_FILES_LIST_TAG, name)).performTouchInput { longClick() }
        else compose.onNodeWithContentDescription(compose.activity.getString(R.string.mobile_files_actions, name)).performClick()
        val action = if (fixture.expectedTrashEnabled) R.string.mobile_files_trash else R.string.mobile_files_delete
        waitFor(hasText(compose.activity.getString(action)))
        compose.onNodeWithText(compose.activity.getString(action)).performClick()
        val message = compose.activity.getString(
            if (fixture.expectedTrashEnabled) R.string.mobile_files_trash_confirmation else R.string.mobile_files_delete_confirmation, name)
        waitFor(hasText(message))
        deleteProofScreenshot(if (cancel) "cancel-confirmation" else "action-confirmation")
        compose.onNodeWithText(compose.activity.getString(
            if (cancel) R.string.mobile_action_cancel else R.string.mobile_files_confirm_action)).performClick()
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(message)).fetchSemanticsNodes().isEmpty() }
        if (cancel) row(MOBILE_FILES_LIST_TAG, name)
    }

    private suspend fun inTrash(runtime: MobileOAuthRuntime, id: Long): Boolean {
        var page = runtime.putioClient.trash.list(TrashListQuery(perPage = 50))
        repeat(5) { index ->
            if (page.files.any { it.id == id }) return true
            val cursor = page.cursor?.takeIf { it.isNotBlank() } ?: return false
            check(index < 4) { "Trash listing exceeds bounded proof scope" }
            page = runtime.putioClient.trash.continueList(cursor, TrashContinueQuery(perPage = 50))
        }
        error("Incomplete Trash listing")
    }

    private fun row(listTag: String, name: String): SemanticsMatcher {
        waitFor(hasTestTag(listTag))
        val matcher = hasText(name) and hasAnyAncestor(hasTestTag(listTag))
        compose.waitUntil(TIMEOUT) {
            try { compose.onNodeWithTag(listTag).performScrollToNode(hasText(name)) } catch (_: AssertionError) { }
            val count = compose.onAllNodes(matcher).fetchSemanticsNodes().size
            check(count <= 1) { "Ambiguous fixture row" }
            count == 1
        }
        return matcher
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
    }

    private fun requireSession(runtime: MobileOAuthRuntime, session: MobileAuthState.SignedIn) {
        val current = runtime.authController.state.value as? MobileAuthState.SignedIn
        check(current != null && current.sessionId == session.sessionId && current.account.userId == session.account.userId)
    }

    private fun apiCheck(stage: String, block: suspend () -> Unit) {
        try { runBlocking { withTimeout(TIMEOUT) { block() } } }
        catch (error: Exception) { throw AssertionError("$stage failed (${error.javaClass.simpleName})") }
    }

    private companion object { const val TIMEOUT = 30_000L }
}

internal fun deleteProofScreenshot(label: String) {
    val runId = InstrumentationRegistry.getArguments().getString("putio.delete.runId")
    require(runId != null && runId.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "delete-proof-$runId")
    check(directory.mkdirs() || directory.isDirectory)
    instrumentation.uiAutomation.waitForIdle(100, 3_000)
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try { File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
    finally { bitmap.recycle() }
}
