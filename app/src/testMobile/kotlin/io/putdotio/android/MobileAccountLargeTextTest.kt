package io.putdotio.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesSort
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigFailure
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.VideoPlaybackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-port")
class MobileAccountLargeTextTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        private var previousFontScale = 1f
        override fun before() {
            previousFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
            RuntimeEnvironment.setFontScale(2f)
        }
        override fun after() { RuntimeEnvironment.setFontScale(previousFontScale) }
    }).around(compose)

    @Test
    fun selectedValuesLeaveDescriptionsAReadableColumn() {
        mount()
        assertValueAndDescription(
            "Default sort order", "Date modified, newest first",
            "Used by every folder that doesn’t have its own order.",
        )
        assertValueAndDescription(
            "Choose your proxy", "a-long-regional-proxy-name",
            "Route streams and downloads through a put.io proxy closer to you.",
        )
        assertValueAndDescription(
            "Strictly necessary", "Always on",
            "Sign-in, your files, transfers, playback, and these privacy choices. " +
                "Always on; nothing optional is collected here.",
        )
        compose.onNodeWithTag(MOBILE_STRICTLY_NECESSARY_TAG)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        assertValueAndDescription("Video playback", "HLS stream", "Choose how this app streams videos.")
        assertValueAndDescription(
            "App info", BuildConfig.VERSION_NAME, "Version, build, and device details for support.",
        )
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h320dp-land")
    fun shortLandscapePlaybackChooserCanReachAndSelectTheLastOption() {
        val events = mutableListOf<AndroidAppConfigEvent>()
        mount(configEvents = events)
        scrollTo("Video playback")
        compose.onNodeWithText("Video playback").performClick()
        compose.onNodeWithText("Direct MP4").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(
            listOf(AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4))),
            events,
        )
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h320dp-land")
    fun shortLandscapeTrashConfirmationKeepsConsequencesAndBothActionsReachable() {
        val events = mutableListOf<AccountSettingsEvent>()
        mount(settingsEvents = events)
        scrollTo("Move deleted files to Trash")
        compose.onNodeWithText("Move deleted files to Trash").performClick()
        val message = compose.onNodeWithText("Deleted files will no longer be recoverable. Continue?")
        message.assertIsDisplayed()
        assertCompleteText("Deleted files will no longer be recoverable. Continue?")
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        assertTrue(events.isEmpty())
        compose.onNodeWithText("Move deleted files to Trash").performClick()
        compose.onNodeWithText("Turn off").assertIsDisplayed().performClick()
        assertEquals(
            listOf(AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.Trash, false))),
            events,
        )
    }

    @Test
    fun accountLoadFailureKeepsFullWidthRecoveryTextAndDispatchesRetry() {
        val events = mutableListOf<AccountSettingsEvent>()
        mount(
            settings = readyAccountSettingsState().copy(
                content = AccountSettingsContent.Failed(AccountSettingsFailure.Unexpected(IllegalStateException())),
            ),
            settingsEvents = events,
        )
        assertRecovery()
        assertEquals(listOf(AccountSettingsEvent.RetryLoad), events)
    }

    @Test
    fun playbackLoadFailureKeepsFullWidthRecoveryTextAndDispatchesRetry() {
        val events = mutableListOf<AndroidAppConfigEvent>()
        mount(
            config = readyAndroidAppConfigState().copy(
                content = AndroidAppConfigContent.Failed(AndroidAppConfigFailure.Unexpected(IllegalStateException())),
            ),
            configEvents = events,
        )
        assertRecovery()
        assertEquals(listOf(AndroidAppConfigEvent.RetryLoad), events)
    }

    private fun assertRecovery() {
        scrollTo("Try again")
        val retry = compose.onNodeWithText("Try again").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val message = compose.onNodeWithText("put.io is temporarily unavailable. Try again.", useUnmergedTree = true)
        val text = message.fetchSemanticsNode().boundsInRoot
        assertTrue("The retry belongs below recovery text", retry.top >= text.bottom)
        assertTrue("Recovery text must use the row width", text.width >= rootWidth() * 0.75f)
        compose.onNodeWithText("Try again").performClick()
    }

    private fun assertValueAndDescription(title: String, value: String, description: String) {
        scrollTo(description)
        val heading = compose.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val selected = compose.onNodeWithText(value, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val supporting = compose.onNodeWithText(description, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Selected values must not squeeze the heading", selected.top >= heading.bottom)
        assertEquals("Selected value aligns with its heading", heading.left, selected.left, 1f)
        assertTrue("Description must use most of the row width", supporting.width >= rootWidth() * 0.65f)
        assertCompleteText(value)
        assertCompleteText(description)
    }

    private fun assertCompleteText(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale)
        repeat(layout.lineCount) { line ->
            assertFalse("$text must not be ellipsized", layout.isLineEllipsized(line))
            assertTrue("$text fits its width", layout.getLineRight(line) <= layout.size.width + 1f)
            assertTrue("$text fits its height", layout.getLineBottom(line) <= layout.size.height + 1f)
        }
    }

    private fun scrollTo(text: String) {
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText(text))
    }

    private fun rootWidth() = compose.onRoot().fetchSemanticsNode().boundsInRoot.width

    private fun mount(
        settings: AccountSettingsState = readyAccountSettingsState(
            preferences = DefaultAccountSettingsPreferences.copy(
                defaultSort = FilesSort.DATE_MODIFIED_DESCENDING,
                tunnelRoute = TunnelRouteName("a-long-regional-proxy-name"),
            ),
        ),
        config: AndroidAppConfigState = readyAndroidAppConfigState(),
        settingsEvents: MutableList<AccountSettingsEvent> = mutableListOf(),
        configEvents: MutableList<AndroidAppConfigEvent> = mutableListOf(),
    ) {
        compose.setContent {
            PutioTheme {
                MobileAccountScreen(
                    account = MobileAccount(userId = 42, username = "proof", email = "proof@example.com"),
                    sessionId = MobileAuthSessionId(1),
                    settingsState = settings,
                    appConfigState = config,
                    onSettingsEvent = settingsEvents::add,
                    onAppConfigEvent = configEvents::add,
                    onSignOut = {},
                )
            }
        }
    }
}
