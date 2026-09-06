package io.putdotio.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.FilesListQuery
import io.putdotio.sdk.files.FilesSearchQuery
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class AuthenticatedFilesMoveTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Move proof requires explicit opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.move.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun authenticatedMovePreservesCancelAndConfirmsExactParents() {
        val fixture = MoveProofFixture.parse(InstrumentationRegistry.getArguments().getString("putio.move.fixture"))
        val runtime = MobileOAuthRuntime.get(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.waitUntil(TIMEOUT) {
            when (runtime.authController.state.value) {
                MobileAuthState.Initializing, MobileAuthState.RestoringSession,
                is MobileAuthState.ValidatingSession -> false
                else -> true
            }
        }
        val session = runtime.authController.state.value as? MobileAuthState.SignedIn
            ?: throw AssertionError("Proof requires an existing signed-in session")
        check(session.account.userId == fixture.expectedAccountId)
        preflight(fixture, runtime)
        openSource(fixture)
        sortSource(fixture, runtime)
        cancelMove(fixture, runtime)
        requireSession(runtime, session)
        collide(fixture, runtime)
        requireSession(runtime, session)
        submit(fixture.folderName, listOf(fixture.containerId, fixture.destinationId))
        awaitOutcome(fixture.folderName)
        exactParent(runtime, fixture.folderItemId, fixture.folderName, fixture.destinationId)
        requireAbsent(fixture.folderName)
        moveProofScreenshot("folder-result")
        requireSession(runtime, session)
        submit(fixture.fileName, listOf(fixture.containerId))
        awaitOutcome(fixture.fileName)
        exactParent(runtime, fixture.fileItemId, fixture.fileName, fixture.containerId)
        requireAbsent(fixture.fileName)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(row(fixture.fileName)).assertIsDisplayed()
        moveProofScreenshot("ancestor-result")
        compose.onNode(row(fixture.destinationName)).performClick()
        compose.onNode(row(fixture.folderName)).assertIsDisplayed()
        requireSession(runtime, session)
        submit(fixture.folderName, emptyList())
        awaitOutcome(fixture.folderName)
        exactParent(runtime, fixture.folderItemId, fixture.folderName, 0)
        requireAbsent(fixture.folderName)
        moveProofScreenshot("root-result")
        exactParent(runtime, fixture.collisionItemId, fixture.collisionName, fixture.sourceId)
        exactParent(runtime, fixture.collisionPeerId, fixture.collisionName, fixture.destinationId)
        requireSession(runtime, session)
        moveProofApiCheck("final identity") {
            assertTrue("final account identity", runtime.putioClient.account.getInfo().userId == fixture.expectedAccountId)
        }
    }

    private fun preflight(f: MoveProofFixture, runtime: MobileOAuthRuntime) {
        moveProofApiCheck("fixture preflight") {
            assertTrue("preflight account identity", runtime.putioClient.account.getInfo().userId == f.expectedAccountId)
            val expected = listOf(
                Triple(f.containerId, f.containerName, 0L), Triple(f.sourceId, f.sourceName, f.containerId),
                Triple(f.destinationId, f.destinationName, f.containerId),
                Triple(f.folderItemId, f.folderName, f.sourceId), Triple(f.fileItemId, f.fileName, f.sourceId),
                Triple(f.collisionItemId, f.collisionName, f.sourceId),
                Triple(f.collisionPeerId, f.collisionName, f.destinationId),
            )
            for ((index, expectedItem) in expected.withIndex()) {
                val (id, name, parent) = expectedItem
                val item = runtime.putioClient.files.get(id)
                assertTrue("fixture item $index identity", item.id == id)
                assertTrue("fixture item $index name", item.name == name)
                assertTrue("fixture item $index parent", item.parentId == parent)
                if (id == f.fileItemId) {
                    assertTrue("fixture file type", item.fileType != PutioFileType.FOLDER)
                    assertTrue("fixture file size", item.size == f.fileSize)
                } else assertTrue("fixture item $index folder type", item.fileType == PutioFileType.FOLDER)
            }
            val contents = mapOf(
                f.containerId to setOf(f.sourceId, f.destinationId),
                f.sourceId to setOf(f.folderItemId, f.fileItemId, f.collisionItemId),
                f.destinationId to setOf(f.collisionPeerId),
                f.folderItemId to emptySet(), f.collisionItemId to emptySet(), f.collisionPeerId to emptySet(),
            )
            for ((index, expectedContents) in contents.entries.withIndex()) {
                val (parent, ids) = expectedContents
                val page = runtime.putioClient.files.list(parent, FilesListQuery(perPage = 50))
                assertTrue("fixture folder $index complete listing", page.cursor.isNullOrBlank())
                assertTrue("fixture folder $index contents", page.files.map { it.id }.toSet() == ids)
            }
            val search = runtime.putioClient.files.search(FilesSearchQuery(f.containerName, perPage = 50))
            assertTrue("container search complete listing", search.cursor.isNullOrBlank())
            assertTrue("container search exact result", search.files.filter { it.name == f.containerName }
                .singleOrNull()?.id == f.containerId)
            val target = runtime.putioClient.files.search(FilesSearchQuery(f.folderName, perPage = 50))
            assertTrue("target search complete listing", target.cursor.isNullOrBlank())
            assertTrue("target search exact result", target.files.filter { it.name == f.folderName }
                .singleOrNull()?.id == f.folderItemId)
        }
    }

    private fun openSource(f: MoveProofFixture) {
        compose.onNodeWithText(compose.activity.getString(R.string.mobile_destination_search)).performClick()
        waitFor(hasTestTag(MOBILE_SEARCH_FIELD_TAG))
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).performTextReplacement(f.containerName)
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).performImeAction()
        val result = hasText(f.containerName) and hasAnyAncestor(hasTestTag(MOBILE_SEARCH_RESULTS_TAG))
        waitFor(result)
        compose.onNode(result).performClick()
        compose.onNode(row(f.sourceName)).performClick()
        row(f.folderName)
    }

    private fun sortSource(f: MoveProofFixture, runtime: MobileOAuthRuntime) {
        compose.onNodeWithTag(MOBILE_FILES_SORT_TAG).performClick()
        val label = compose.activity.getString(R.string.mobile_files_sort_name_descending)
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onNodeWithTag(MOBILE_FILES_SORT_TAG).fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription] == label
        }
        row(f.folderName)
        moveProofApiCheck("source sort") {
            assertTrue("persisted source sort", runtime.putioClient.files.get(f.sourceId).sortBy == "NAME_DESC")
        }
    }

    private fun cancelMove(f: MoveProofFixture, runtime: MobileOAuthRuntime) {
        val before = compose.onNode(row(f.folderName)).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.mobile_files_actions, f.folderName))
            .performClick()
        openPicker()
        pickFolder(f.containerId)
        pickFolder(f.destinationId)
        moveProofScreenshot("cancel-picker")
        compose.onNodeWithTag(MOBILE_FILES_MOVE_CANCEL_TAG).performClick()
        waitForAbsent(hasTestTag(MOBILE_FILES_MOVE_PICKER_TAG))
        check(compose.onNode(row(f.folderName)).fetchSemanticsNode().boundsInRoot == before)
        exactParent(runtime, f.folderItemId, f.folderName, f.sourceId)
        moveProofApiCheck("cancel preserves source") {
            val source = runtime.putioClient.files.get(f.sourceId)
            assertTrue("Cancel preserves sort", source.sortBy == "NAME_DESC")
            val children = runtime.putioClient.files.list(f.sourceId, FilesListQuery(perPage = 50))
            assertTrue("Cancel source complete listing", children.cursor.isNullOrBlank())
            assertTrue("Cancel preserves contents", children.files.map { it.id }.toSet() ==
                setOf(f.folderItemId, f.fileItemId, f.collisionItemId))
        }
    }

    private fun collide(f: MoveProofFixture, runtime: MobileOAuthRuntime) {
        submit(f.collisionName, listOf(f.containerId, f.destinationId))
        awaitOutcome(f.collisionName, collision = true)
        exactParent(runtime, f.collisionItemId, f.collisionName, f.sourceId)
        exactParent(runtime, f.collisionPeerId, f.collisionName, f.destinationId)
        compose.onNode(row(f.collisionName)).assertIsDisplayed()
        moveProofScreenshot("collision")
    }

    private fun submit(name: String, destinationPath: List<Long>) {
        row(name)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.mobile_files_actions, name))
            .performClick()
        openPicker()
        destinationPath.forEach(::pickFolder)
        compose.onNodeWithTag(MOBILE_FILES_MOVE_HERE_TAG).performClick()
        waitForAbsent(hasTestTag(MOBILE_FILES_MOVE_PICKER_TAG))
    }

    private fun openPicker() {
        val label = compose.activity.getString(R.string.mobile_files_move)
        waitFor(hasText(label))
        compose.onNodeWithText(label).performClick()
        waitFor(hasTestTag(MOBILE_FILES_MOVE_PICKER_TAG))
        waitFor(hasTestTag(MOBILE_FILES_MOVE_FOLDER_TAG) and hasText("Files"))
    }

    private fun pickFolder(id: Long) {
        val tag = mobileFilesMoveFolderTag(FilesItemId(id))
        var requestedPages = 0
        compose.waitUntil(TIMEOUT) {
            try { compose.onNodeWithTag(MOBILE_FILES_MOVE_LIST_TAG).performScrollToNode(hasTestTag(tag)) }
            catch (_: AssertionError) { }
            if (compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size == 1) true
            else {
                val more = compose.onAllNodes(hasTestTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG)).fetchSemanticsNodes()
                if (more.size == 1) {
                    check(requestedPages++ < 4) { "Destination exceeds bounded picker proof" }
                    compose.onNodeWithTag(MOBILE_FILES_MOVE_LIST_TAG)
                        .performScrollToNode(hasTestTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG))
                    compose.onNodeWithTag(MOBILE_FILES_MOVE_LOAD_MORE_TAG).performClick()
                    compose.waitForIdle()
                }
                false
            }
        }
        val name = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        compose.onNodeWithTag(tag).performClick()
        waitFor(hasTestTag(MOBILE_FILES_MOVE_FOLDER_TAG) and hasText(name))
        waitFor(hasTestTag(MOBILE_FILES_MOVE_LIST_TAG))
    }

    private fun awaitOutcome(name: String, collision: Boolean = false) {
        val message = compose.activity.getString(
            if (collision) R.string.mobile_files_move_collision else R.string.mobile_files_move_moved, name,
        )
        waitFor(hasTestTag(MOBILE_FILES_MOVE_OUTCOME_TAG))
        waitFor(hasText(message))
    }

    private fun exactParent(runtime: MobileOAuthRuntime, id: Long, name: String, parent: Long) {
        moveProofApiCheck("exact parent readback") {
            val item = runtime.putioClient.files.get(id)
            assertTrue("readback identity", item.id == id)
            assertTrue("readback name", item.name == name)
            assertTrue("readback parent", item.parentId == parent)
        }
    }

    private fun row(name: String): SemanticsMatcher {
        waitFor(hasTestTag(MOBILE_FILES_LIST_TAG))
        val matcher = hasText(name) and hasAnyAncestor(hasTestTag(MOBILE_FILES_LIST_TAG))
        compose.waitUntil(TIMEOUT) {
            try { compose.onNodeWithTag(MOBILE_FILES_LIST_TAG).performScrollToNode(hasText(name)) }
            catch (_: AssertionError) { }
            val count = compose.onAllNodes(matcher).fetchSemanticsNodes().size
            check(count <= 1) { "Ambiguous fixture row" }
            count == 1
        }
        return matcher
    }

    private fun requireAbsent(name: String) =
        waitForAbsent(hasText(name) and hasAnyAncestor(hasTestTag(MOBILE_FILES_LIST_TAG)))
    private fun requireSession(runtime: MobileOAuthRuntime, session: MobileAuthState.SignedIn) {
        val current = runtime.authController.state.value as? MobileAuthState.SignedIn
        check(current != null && current.sessionId == session.sessionId &&
            current.account.userId == session.account.userId)
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
    }
    private fun waitForAbsent(matcher: SemanticsMatcher) {
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() }
    }
    private companion object { const val TIMEOUT = 30_000L }
}
