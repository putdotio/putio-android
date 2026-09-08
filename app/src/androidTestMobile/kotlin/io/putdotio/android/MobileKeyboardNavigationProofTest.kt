package io.putdotio.android

import android.os.Build
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryState
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchState
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Normal-font keyboard proof in the real shell, without an account runtime or API calls. */
@RunWith(AndroidJUnit4::class)
class MobileKeyboardNavigationProofTest {
    private val compose = createComposeRule()
    private lateinit var hostView: View
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                assumeTrue("Shell keyboard proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.accessibility.enabled") == "true")
                require(Build.VERSION.SDK_INT == 37)
                require(instrumentation.targetContext.resources.configuration.fontScale == 1f) {
                    "Set system font_scale to 1.0 before normal-font keyboard proof"
                }
                accessibilityProofDirectory()
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun keyboardKeepsNavigationAndTheFocusedSearchEditor() {
        val orientation = InstrumentationRegistry.getArguments().getString("putio.accessibility.orientation")
        require(orientation == "portrait" || orientation == "landscape")
        val navigationTag = if (orientation == "portrait") MOBILE_NAV_BAR_TAG else MOBILE_NAV_RAIL_TAG
        var query by mutableStateOf("")
        val mounted = mutableStateOf(true)
        val playerFactory = ShellProofPlayerFactory(null)
        compose.setContent {
            hostView = LocalView.current
            PutioTheme {
                Surface(Modifier.fillMaxSize()) {
                    if (mounted.value) MobileShell(
                        filesState = accessibilityFiles(),
                        accountSettingsState = accessibilitySettings(),
                        appConfigState = accessibilityAppConfig(),
                        searchHistoryState = MobileSearchHistoryState(
                            SearchState(query, SearchContent.Idle, emptyList(), emptySet(), 1),
                            HistoryState(HistoryContent.Disabled), null,
                        ),
                        searchHistoryActions = MobileSearchHistoryActions(onQueryChanged = { query = it }),
                        transfersSessionId = MobileAuthSessionId(147),
                        account = MobileAccount(147, "Keyboard proof", "proof@example.invalid"),
                        playbackRepository = NoShellProofPlayback,
                        playbackPlayerFactory = playerFactory,
                        sessionId = MobileAuthSessionId(147),
                        onFilesEvent = { true },
                        onAccountSettingsEvent = { error("Unexpected settings mutation") },
                        onPlaybackAuthenticationRequired = { error("Unexpected authentication request") },
                        onSignOut = { error("Unexpected sign out") },
                    )
                }
            }
        }
        try {
            compose.onNodeWithTag(navigationTag).assertIsDisplayed()
            capture("normal-$orientation-files")
            compose.onNode(hasText("Search") and hasAnyAncestor(hasTestTag(navigationTag))).performClick()
            capture("normal-$orientation-search")
            val editor = compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG)
            editor.performClick().performTextInput("Rehearsal")
            compose.waitUntil(5_000) {
                compose.runOnIdle {
                    ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(navigationTag).assertExists()
            compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).assertDoesNotExist()
            editor.assertIsDisplayed().assertIsFocused().assertTextContains("Rehearsal")
            editor.performTextInput(" archive")
            editor.assertIsFocused().assertTextContains("Rehearsal archive")
            capture("normal-$orientation-search-keyboard")
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(5_000) {
                compose.runOnIdle {
                    ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                }
            }
            editor.assertIsDisplayed().assertIsFocused().assertTextContains("Rehearsal archive")
            compose.onNodeWithTag(navigationTag).assertIsDisplayed()
            compose.onNode(hasText("Transfers") and hasAnyAncestor(hasTestTag(navigationTag))).performClick()
            compose.onNodeWithText("Add transfer").assertIsDisplayed()
            capture("normal-$orientation-transfers")
        } catch (failure: Throwable) {
            try {
                capture("normal-$orientation-keyboard-failure")
            } catch (captureFailure: Throwable) {
                failure.addSuppressed(captureFailure)
            }
            throw failure
        } finally {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
        }
    }

    private fun capture(label: String) {
        compose.waitForIdle()
        accessibilityProofScreenshot(label)
    }
}
