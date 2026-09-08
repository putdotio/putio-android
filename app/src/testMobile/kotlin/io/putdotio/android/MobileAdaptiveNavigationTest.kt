package io.putdotio.android

import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PutioFileType
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
class MobileLargeTextNavigationTest : NavigationShellFixture(2f) {
    @Test
    fun portraitKeepsContentSpaceAndEveryDestinationLabelReadable() {
        mount()
        assertModalContentSpace()
        assertDrawerLabels()
        selectDrawerDestination("Transfers")
        compose.onNodeWithText("Add transfer").assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        drawerDestination("Transfers").assertIsSelected()
        selectDrawerDestination("Files")
        assertModalContentSpace()
    }

    @Test
    @Config(qualifiers = "en-rUS-w640dp-h320dp-land")
    fun shortLandscapeUsesScrollableLabelledNavigationWithoutASqueezedRail() {
        mount()
        assertModalContentSpace()
        assertDrawerLabels()
        selectDrawerDestination("Account")
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        drawerDestination("Account").performScrollTo().assertIsSelected()
        selectDrawerDestination("Files")
        assertModalContentSpace()
    }

    @Test
    fun backClosesTheDrawerBeforeNavigatingOutOfTheCurrentFolder() {
        val events = mutableListOf<FilesBrowserEvent>()
        mount(nested = true, onFilesEvent = { events += it; true })
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        drawerDestination("Files").assertIsDisplayed()
        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).assertIsDisplayed()
        assertFalse(events.any { it == FilesBrowserEvent.NavigateBack })
        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, events.count { it == FilesBrowserEvent.NavigateBack })
    }

    @Test
    fun backFromTheReopenedDrawerKeepsTheSelectedSearchDestination() {
        mount()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        selectDrawerDestination("Search")
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        drawerDestination("Search").assertIsDisplayed().assertIsSelected()
        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsDisplayed()
    }

    @Test
    fun backWhileTheDrawerOpensKeepsTheSelectedSearchDestination() {
        mount()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        selectDrawerDestination("Search")
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        compose.mainClock.advanceTimeBy(32L)
        val drawer = compose.onNodeWithTag(MOBILE_NAV_DRAWER_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("Back must be exercised before the drawer finishes opening", drawer.right < drawer.width)
        compose.runOnIdle { backOwner.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag(MOBILE_SEARCH_FIELD_TAG).assertIsDisplayed()
    }

    private fun assertModalContentSpace() {
        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_NAV_RAIL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).assertIsDisplayed()
        val list = compose.onNodeWithTag(MOBILE_FILES_LIST_TAG).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("Navigation must leave most of the viewport for content", list.height >= root.height * 0.6f)
    }

    private fun assertDrawerLabels() {
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).performClick()
        listOf("Files", "Search", "Transfers", "Account").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            drawerDestination(label).performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(2f, layout.layoutInput.density.fontScale)
            assertEquals("A destination must not split into narrow fragments", 1, layout.lineCount)
            assertCompleteLabel(label, layout)
        }
    }

    private fun selectDrawerDestination(label: String) {
        drawerDestination(label).performScrollTo().performClick()
        compose.waitForIdle()
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w360dp-h640dp-port")
class MobileOrdinaryNavigationTest : NavigationShellFixture(1f) {
    @Test
    fun ordinaryPhoneKeepsItsLabelledBottomNavigation() {
        mount()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertIsDisplayed()
        assertNavigationLabels(MOBILE_NAV_BAR_TAG)
        compose.onNode(hasText("Transfers") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).performClick()
        compose.onNodeWithText("Add transfer").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-w840dp-h900dp-land")
    fun ordinaryTabletKeepsItsLabelledRail() {
        mount()
        compose.onNodeWithTag(MOBILE_NAV_MENU_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MOBILE_NAV_RAIL_TAG).assertIsDisplayed()
        assertNavigationLabels(MOBILE_NAV_RAIL_TAG)
    }

    private fun assertNavigationLabels(tag: String) {
        listOf("Files", "Search", "Transfers", "Account").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertCompleteLabel(label, layouts.single())
        }
    }
}

abstract class NavigationShellFixture(fontScale: Float) {
    protected val compose = createComposeRule()
    protected lateinit var backOwner: OnBackPressedDispatcherOwner
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        private var previousFontScale = 1f

        override fun before() {
            previousFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
            RuntimeEnvironment.setFontScale(fontScale)
        }

        override fun after() { RuntimeEnvironment.setFontScale(previousFontScale) }
    }).around(compose)

    protected fun drawerDestination(label: String) =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(MOBILE_NAV_DRAWER_TAG)))

    protected fun mount(nested: Boolean = false, onFilesEvent: (FilesBrowserEvent) -> Boolean = { true }) {
        val files = navigationFiles(nested)
        compose.setContent {
            backOwner = checkNotNull(LocalOnBackPressedDispatcherOwner.current)
            PutioTheme {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = files,
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = MobileAccount(42, "Navigation proof", "proof@example.invalid"),
                    playbackRepository = NoNavigationPlayback,
                    sessionId = MobileAuthSessionId(1),
                    onFilesEvent = onFilesEvent,
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
    }
}

private fun navigationFiles(nested: Boolean): FilesBrowserState {
    val initial = FilesBrowserReducer.start()
    val request = (initial.effect as FilesBrowserEffect.LoadFolder).requestId
    val folder = FilesItem(FilesItemId(7), FilesFolder.Root.id, "Shows", PutioFileType.FOLDER, 0, "2026-09-08")
    val root = FilesBrowserReducer.reduce(
        initial.state, FilesBrowserEvent.LoadSucceeded(request, FilesPage(listOf(folder), nextCursor = null)),
    ).state
    return if (nested) FilesBrowserReducer.reduce(root, FilesBrowserEvent.OpenFolder(folder.id)).state else root
}

private object NoNavigationPlayback : PlaybackRepository {
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        error("Navigation must not resolve playback")

    override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
}

private fun assertCompleteLabel(label: String, layout: TextLayoutResult) {
    // Native Robolectric can report didOverflowWidth while every rendered line fits its bounds.
    assertEquals("$label must stay on one line", 1, layout.lineCount)
    assertFalse("$label must not be ellipsized", layout.isLineEllipsized(0))
    assertTrue("$label must fit at its leading edge", layout.getLineLeft(0) >= -1f)
    assertTrue("$label must fit at its trailing edge", layout.getLineRight(0) <= layout.size.width + 1f)
    assertTrue("$label must fit vertically", layout.getLineBottom(0) <= layout.size.height + 1f)
}
