package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesPage
import io.putdotio.android.search.AppConfigRecentSearchStore
import io.putdotio.android.search.RecentSearchConfig
import io.putdotio.android.search.SEARCH_HISTORY_ENABLED_KEY
import io.putdotio.android.search.SEARCH_HISTORY_KEY
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.Collections
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Controlled-state proof of recent searches on the real signed-in TV shell: the production
 * store keeps them in an in-memory `/config` with tv-native's `searchHistory` and
 * `searchHistoryEnabled` keys, and every write is logged. No API calls.
 */
@RunWith(AndroidJUnit4::class)
class TvSearchHistoryProofTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(
                    "TV search history proof requires opt-in",
                    arguments.getString("putio.tv.searchHistory.enabled") == "true",
                )
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    private val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Test
    fun onlySubmittedAndOpenedSearchesAreKeptAndSettingsToggleAndClearThem() {
        compose.mountTvProofSession(dependencies())
        compose.waitUntil(5_000) { hasContentDescription("Open $HOME_FOLDER") }

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { hasContentDescription("Search again for earlier") }
        screenshot("01-stored-term")

        // One key at a time, each after the 300 ms debounce has searched, as D-pad typing does.
        "map".forEach { key ->
            field().assertIsFocused().performTextInput(key.toString())
            Thread.sleep(SETTLE_MILLIS)
            compose.waitUntil(5_000) { hasContentDescription("Open $FOLDER") }
        }
        assertChips("earlier")
        screenshot("02-typed-slowly-nothing-kept")

        // Opening a result keeps the search it came from.
        focus("Open $FOLDER")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { hasContentDescription(INSIDE) }
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { isFocused("Open $FOLDER") }
        assertChips("map", "earlier")
        screenshot("03-opened-result-kept")

        // Search on the keyboard keeps the submitted term.
        focus("Search files")
        submit("harbor")
        compose.waitUntil(5_000) { hasContentDescription("Search again for harbor") }
        assertChips("harbor", "map", "earlier")
        screenshot("04-submitted-kept")

        // Right from the end of the field reaches tv-native's Settings.
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        settings().assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        button("Disable search history").assertIsFocused()
        screenshot("05-settings")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        button("Clear search history").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !hasContentDescription("Search again for harbor") }
        assertChips()
        settings().assertIsFocused()
        screenshot("06-cleared")

        submit("river", from = KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitUntil(5_000) { hasContentDescription("Search again for river") }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        button("Disable search history").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { !hasContentDescription("Search again for river") }

        // Off: a submitted search is not kept, and Settings offers to turn it back on.
        submit("meadow", from = KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitUntil(5_000) { hasContentDescription("Open $FOLDER") }
        assertChips()
        screenshot("07-off-nothing-kept")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        button("Show search history").assertIsFocused()
        assertEquals(0, compose.onAllNodesWithText("Clear search history").fetchSemanticsNodes().size)
        screenshot("08-settings-off")
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        submit("lantern", from = KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitUntil(5_000) { hasContentDescription("Search again for lantern") }
        assertChips("lantern")
        screenshot("09-on-again")

        compose.waitUntil(5_000) { writes.size == EXPECTED_WRITES.size }
        assertEquals(EXPECTED_WRITES, writes.toList())
    }

    private fun dependencies(): TvSessionDependencies {
        val home = proofItem(HOME_FOLDER_ID, HOME_FOLDER, PutioFileType.FOLDER, FilesFolder.Root.id)
        val folder = proofItem(FOLDER_ID, FOLDER, PutioFileType.FOLDER, FilesFolder.Root.id)
        val inside = proofItem(INSIDE_ID, INSIDE, PutioFileType.PDF, folder.id)
        return tvProofDependencies(
            listings = mapOf(
                // Files starts elsewhere, so a result row is only ever Search's.
                FilesFolder.Root.id to FilesPage(listOf(home), null),
                folder.id to FilesPage(listOf(inside), null, parent = folder),
            ),
            searchResults = listOf(folder),
            recentSearchStore = { scope ->
                AppConfigRecentSearchStore(
                    loadConfig = { RecentSearchConfig(enabled = true, terms = listOf("earlier")) },
                    saveTerms = { writes += "$SEARCH_HISTORY_KEY=$it" },
                    saveEnabled = { writes += "$SEARCH_HISTORY_ENABLED_KEY=$it" },
                    parentScope = scope,
                )
            },
        )
    }

    /** Clears the field, types [term] and runs the keyboard's Search action. */
    private fun submit(term: String, from: Int? = null) {
        from?.let(::press)
        field().assertIsFocused().performTextClearance()
        field().performTextInput(term)
        field().performImeAction()
        compose.waitForIdle()
    }

    private fun assertChips(vararg terms: String) {
        val shown = compose.onAllNodesWithContentDescription("Search again for ", substring = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                    ?.removePrefix("Search again for ")
            }
        assertEquals(terms.toList(), shown)
    }

    private fun field(): SemanticsNodeInteraction = compose.onNodeWithContentDescription("Search files")

    private fun settings(): SemanticsNodeInteraction = button("Settings")

    private fun button(label: String): SemanticsNodeInteraction = compose.onNode(hasText(label) and hasClickAction())

    private fun hasContentDescription(label: String) =
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()

    private fun isFocused(label: String) = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
        .any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    /** Walks the D-pad down, then up, until [label] holds focus. */
    private fun focus(label: String) {
        repeat(MAX_STEPS) { if (!isFocused(label)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(MAX_STEPS) { if (!isFocused(label)) press(KeyEvent.KEYCODE_DPAD_UP) }
        compose.onNodeWithContentDescription(label).assertIsFocused()
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
        Thread.sleep(STEP_MILLIS)
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-search-history-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.searchHistory.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val HOME_FOLDER_ID = 43L
        const val HOME_FOLDER = "Other folder"
        const val FOLDER_ID = 44L
        const val INSIDE_ID = 45L
        const val FOLDER = "Sample folder"
        const val INSIDE = "notes.pdf"
        const val MAX_STEPS = 8
        const val STEP_MILLIS = 400L
        const val SETTLE_MILLIS = 800L
        val EXPECTED_WRITES = listOf(
            "$SEARCH_HISTORY_KEY=[map, earlier]",
            "$SEARCH_HISTORY_KEY=[harbor, map, earlier]",
            "$SEARCH_HISTORY_KEY=[]",
            "$SEARCH_HISTORY_KEY=[river]",
            "$SEARCH_HISTORY_KEY=[]",
            "$SEARCH_HISTORY_ENABLED_KEY=false",
            "$SEARCH_HISTORY_ENABLED_KEY=true",
            "$SEARCH_HISTORY_KEY=[lantern]",
        )
    }
}
