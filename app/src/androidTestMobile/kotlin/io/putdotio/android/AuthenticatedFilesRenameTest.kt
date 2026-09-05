package io.putdotio.android

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
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
import io.putdotio.sdk.files.FilesListQuery
import io.putdotio.sdk.files.FilesSearchQuery
import io.putdotio.sdk.files.PutioFileType
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
class AuthenticatedFilesRenameTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                // Ordinary connected tests must not launch or mutate an authenticated session.
                assumeTrue(
                    "Authenticated rename proof requires explicit opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.rename.enabled") == "true",
                )
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun authenticatedRenamePreservesSessionAndCancel() {
        val fixture = RenameProofFixture.parse(
            InstrumentationRegistry.getArguments().getString("putio.rename.fixture"),
        )
        val runtime = MobileOAuthRuntime.get(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.waitUntil(TIMEOUT_MILLIS) {
            when (runtime.authController.state.value) {
                MobileAuthState.Initializing, MobileAuthState.RestoringSession,
                is MobileAuthState.ValidatingSession -> false
                else -> true
            }
        }
        val session = runtime.authController.state.value as? MobileAuthState.SignedIn
            ?: throw AssertionError("Proof requires an existing signed-in session")
        check(session.account.userId == fixture.expectedAccountId) { "Device account does not match fixture owner" }
        apiCheck("fixture preflight") {
            check(runtime.putioClient.account.getInfo().userId == fixture.expectedAccountId)
            val container = runtime.putioClient.files.get(fixture.containerId)
            check(container.id == fixture.containerId && container.name == fixture.containerName &&
                container.fileType == PutioFileType.FOLDER)
            val results = runtime.putioClient.files.search(FilesSearchQuery(fixture.containerName, perPage = 50))
            check(results.cursor.isNullOrBlank() && results.files.size <= 50) { "Container search exceeds bounded proof scope" }
            check(results.files.filter { it.name == fixture.containerName }.singleOrNull()?.id == fixture.containerId) {
                "Container search is ambiguous"
            }
            val children = runtime.putioClient.files.list(fixture.containerId, FilesListQuery(perPage = 50))
            check(children.cursor.isNullOrBlank() && children.files.size <= 50) { "Fixture folder exceeds bounded proof scope" }
            for ((id, name) in listOf(
                fixture.renameItemId to fixture.renameOriginalName,
                fixture.cancelItemId to fixture.cancelOriginalName,
            )) {
                val item = runtime.putioClient.files.get(id)
                check(item.id == id && item.parentId == fixture.containerId && item.name == name)
                check(item.fileType.isKnown) { "Unknown fixture file type" }
                check(children.files.filter { it.name == name }.singleOrNull()?.id == id) {
                    "Fixture name is ambiguous"
                }
            }
            check(children.files.none { it.name == fixture.renameNewName }) { "Replacement name already exists" }
        }
        requireSession(runtime, session)
        openContainer(fixture)
        renameThroughOverflow(fixture, runtime, session)
        cancelThroughLongPress(fixture, runtime, session)
        requireSession(runtime, session)
        apiCheck("final identity") {
            check(runtime.putioClient.account.getInfo().userId == fixture.expectedAccountId)
        }
        requireSession(runtime, session)
    }

    private fun openContainer(fixture: RenameProofFixture) {
        val searchLabel = compose.activity.getString(R.string.mobile_destination_search)
        compose.onNodeWithText(searchLabel).performClick()
        waitFor(hasTestTag(MOBILE_SEARCH_FIELD_TAG))
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG)
            .performTextReplacement(fixture.containerName)
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).performImeAction()
        waitFor(hasTestTag(MOBILE_SEARCH_RESULTS_TAG))
        val container = waitForRow(MOBILE_SEARCH_RESULTS_TAG, fixture.containerName)
        compose.onNode(container).performClick()
        waitFor(hasTestTag(MOBILE_FILES_LIST_TAG))
    }

    private fun renameThroughOverflow(
        fixture: RenameProofFixture,
        runtime: MobileOAuthRuntime,
        session: MobileAuthState.SignedIn,
    ) {
        waitForRow(MOBILE_FILES_LIST_TAG, fixture.renameOriginalName)
        compose.onNodeWithContentDescription(
            compose.activity.getString(R.string.mobile_files_actions, fixture.renameOriginalName),
        ).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.mobile_files_rename)).performClick()
        requireEditorName(fixture.renameOriginalName)
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG).performTextReplacement(fixture.renameNewName)
        requireSession(runtime, session)
        compose.onNodeWithText(compose.activity.getString(R.string.mobile_files_save)).performClick()
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodes(hasTestTag(MOBILE_FILES_RENAME_FIELD_TAG)).fetchSemanticsNodes().isEmpty()
        }
        waitForRow(MOBILE_FILES_LIST_TAG, fixture.renameNewName)
        apiCheck("rename readback") {
            val renamed = runtime.putioClient.files.get(fixture.renameItemId)
            check(renamed.name == fixture.renameNewName && renamed.parentId == fixture.containerId)
        }
    }

    private fun cancelThroughLongPress(
        fixture: RenameProofFixture,
        runtime: MobileOAuthRuntime,
        session: MobileAuthState.SignedIn,
    ) {
        requireSession(runtime, session)
        val cancelRow = waitForRow(MOBILE_FILES_LIST_TAG, fixture.cancelOriginalName)
        compose.onNode(cancelRow).performTouchInput { longClick() }
        compose.onNodeWithText(compose.activity.getString(R.string.mobile_files_rename)).performClick()
        requireEditorName(fixture.cancelOriginalName)
        compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG)
            .performTextReplacement(fixture.cancelOriginalName + " cancelled draft")
        compose.onNodeWithText(compose.activity.getString(R.string.mobile_action_cancel)).performClick()
        compose.waitUntil(TIMEOUT_MILLIS) {
            compose.onAllNodes(hasTestTag(MOBILE_FILES_RENAME_FIELD_TAG)).fetchSemanticsNodes().isEmpty()
        }
        waitFor(cancelRow)
        apiCheck("cancel readback") {
            val unchanged = runtime.putioClient.files.get(fixture.cancelItemId)
            check(unchanged.name == fixture.cancelOriginalName && unchanged.parentId == fixture.containerId)
        }
    }

    private fun requireEditorName(expected: String) {
        val value = compose.onNodeWithTag(MOBILE_FILES_RENAME_FIELD_TAG)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        check(value == expected) { "Rename editor did not preserve the exact original name" }
    }

    private fun waitForRow(listTag: String, name: String): SemanticsMatcher {
        val row = hasText(name) and hasAnyAncestor(hasTestTag(listTag))
        compose.waitUntil(TIMEOUT_MILLIS) {
            // Server reload may move a renamed item beyond the currently composed viewport.
            val found = try {
                compose.onNodeWithTag(listTag).performScrollToNode(hasText(name))
                true
            } catch (_: AssertionError) {
                false
            }
            val count = compose.onAllNodes(row).fetchSemanticsNodes().size
            check(count <= 1) { "Proof row is ambiguous" }
            found && count == 1
        }
        return row
    }

    private fun requireSession(runtime: MobileOAuthRuntime, session: MobileAuthState.SignedIn) {
        val current = runtime.authController.state.value as? MobileAuthState.SignedIn
        check(current != null && current.sessionId == session.sessionId && current.account.userId == session.account.userId) {
            "Authenticated session changed during proof"
        }
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(TIMEOUT_MILLIS) {
            val count = compose.onAllNodes(matcher).fetchSemanticsNodes().size
            check(count <= 1) { "Proof UI target is ambiguous" }
            count == 1
        }
    }

    private fun apiCheck(stage: String, block: suspend () -> Unit) {
        try {
            runBlocking { withTimeout(TIMEOUT_MILLIS) { block() } }
        } catch (error: Exception) {
            // SDK exceptions can carry response details; report only the failed stage and type.
            throw AssertionError("$stage failed (${error.javaClass.simpleName})")
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
    }
}
