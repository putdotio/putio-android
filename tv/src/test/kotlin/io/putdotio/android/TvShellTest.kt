package io.putdotio.android

import androidx.compose.ui.test.assertCountEquals
import io.putdotio.android.tv.setTvContent
import io.putdotio.android.tv.pressCenter
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.TvDestination
import io.putdotio.android.tv.TvLinkScreen
import io.putdotio.android.tv.TvOfflineScreen
import io.putdotio.android.tv.search.TV_SEARCH_FIELD_TAG
import io.putdotio.android.tv.search.TvRecentSearchActions
import io.putdotio.android.tv.search.TvSearchActions
import io.putdotio.android.tv.search.TvSearchScreen
import androidx.compose.ui.test.onNodeWithTag
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.tv.history.TvHistoryScreen
import io.putdotio.android.account.InactiveAccountNotice
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvLinkPhase
import io.putdotio.android.tv.auth.TvLinkStop
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.putdotio.android.search.searchState
import io.putdotio.android.history.historyState

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun linkScreenShowsBothWaysInWithNewCodeFocused() {
        var sessionExpired by mutableStateOf(false)
        compose.setTvContent {
            TvLinkScreen(
                phase = TvLinkPhase.AwaitingLink("GIQTKN"),
                sessionExpired = sessionExpired,
                onRequestNewCode = {},
            )
        }

        compose.onNodeWithText("Sign in to put.io").assertIsDisplayed()
        compose.onNodeWithText("With your phone").assertIsDisplayed()
        compose.onNodeWithText("Scan with your camera and approve.").assertIsDisplayed()
        compose.onNodeWithText("In a browser").assertIsDisplayed()
        compose.onNodeWithText("Go to put.io/link and enter").assertIsDisplayed()
        compose.onNodeWithContentDescription("Activation code GIQTKN").assertIsDisplayed()
        compose.onNodeWithText("Get a new code").assertIsFocused()
        compose.onNodeWithText("This code expired.").assertDoesNotExist()
        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertDoesNotExist()

        compose.runOnIdle { sessionExpired = true }
        compose.onNodeWithText("Welcome back!").assertIsDisplayed()
        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Activation code GIQTKN").assertIsDisplayed()
    }

    @Test
    fun expiredCodeStaysOnScreenUnderTheNoticeAndOffersANewOne() {
        var requests = 0
        compose.setTvContent {
            TvLinkScreen(
                phase = TvLinkPhase.Stopped(TvLinkStop.CodeExpired, code = "GIQTKN"),
                sessionExpired = false,
                onRequestNewCode = { requests += 1 },
            )
        }

        compose.onNodeWithText("This code expired.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Activation code GIQTKN").assertIsDisplayed()
        compose.onNodeWithText("Get a new code").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(1, requests)
    }

    @Test
    fun offlineStartSaysTheSignInIsSavedAndTriesNowOnRequest() {
        var tries = 0
        compose.setTvContent { TvOfflineScreen(onTryNow = { tries += 1 }) }

        compose.onNodeWithText("Can't reach put.io.").assertIsDisplayed()
        compose.onNodeWithText("Your sign-in is saved. The app tries again on its own when the network is back.")
            .assertIsDisplayed()
        compose.onNodeWithText("Try now").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(1, tries)
    }

    @Test
    fun inactiveAccountNoticeShowsAboveEveryPaneWithoutTakingFocusOrBack() {
        val zone = java.time.ZoneId.systemDefault()
        val deletion = java.time.LocalDate.now(zone).plusDays(14).atTime(12, 0).atZone(zone).toInstant()
        compose.setTvContent {
            TvShell(
                account = TvAccount(
                    userId = 1,
                    username = "user",
                    email = "user@example.com",
                    inactiveNotice = InactiveAccountNotice.Deactivated(deletion),
                ),
                onSignOut = {},
            )
        }
        compose.onNodeWithText("Your account has been deactivated.").assertIsDisplayed()
        compose.onNodeWithText("Your files are deleted in 14 days unless you renew.")
            .assertIsDisplayed()
        compose.onAllNodesWithText("app.put.io", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Keep a good thing going!").assertDoesNotExist()

        compose.openTab(TvDestination.Files)
        compose.onNodeWithText("Your files will show up here.").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onTab("Files").assertIsFocused().performKeyInput {
            repeat(3) { pressKey(Key.DirectionRight) }
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithText("Sign out").assertIsFocused()
        compose.onNodeWithText("Your account has been deactivated.").assertIsDisplayed()

        compose.pressBack()
        compose.onTab("Account").assertIsFocused()
        compose.pressBack()
        compose.onTab("Home").assertIsFocused().assertIsSelected()
        compose.onNodeWithText("Your account has been deactivated.").assertIsDisplayed()
    }

    @Test
    fun shellOpensOnHomeAndATabSelectsWhenItTakesFocus() {
        compose.setTvContent {
            TvShell(account = TvAccount(userId = 1, username = "user", email = "user@example.com"), onSignOut = {})
        }

        compose.onNodeWithContentDescription("put.io").assertIsDisplayed()
        listOf("Home", "Files", "Search", "History", "Account").forEach { compose.onTab(it).assertIsDisplayed() }
        compose.onTab("Home").assertIsFocused().assertIsSelected()
        compose.onNodeWithText("Continue watching and pinned files will show up here.").assertIsDisplayed()

        compose.onTab("Home").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onTab("Files").assertIsFocused().assertIsSelected()
        compose.onTab("Home").assertIsNotSelected()
        compose.onNodeWithText("Your files will show up here.").assertIsDisplayed()

        compose.onTab("Files").performKeyInput { repeat(3) { pressKey(Key.DirectionRight) } }
        compose.onTab("Account").assertIsFocused().assertIsSelected()
        compose.onNodeWithText("Signed in as user").assertIsDisplayed()
        compose.onTab("Account").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Sign out").assertIsFocused()
    }

    @Test
    fun withHomeHiddenTheShellOpensOnFilesAndBackInTheTabBarReturnsThere() {
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                showHome = false,
            )
        }

        compose.onAllNodesWithText("Home").assertCountEquals(0)
        compose.onTab("Files").assertIsSelected()
        compose.onNodeWithText("Your files will show up here.").assertIsDisplayed()

        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onTab("Files").performKeyInput { repeat(3) { pressKey(Key.DirectionRight) } }
        compose.onTab("Account").assertIsFocused().assertIsSelected()
        compose.pressBack()
        compose.onTab("Files").assertIsFocused().assertIsSelected()
    }

    @Test
    fun aHomeRequestWhileHomeIsHiddenStaysOnFiles() {
        var requested by mutableStateOf<TvDestination?>(null)
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                showHome = false,
                requestedDestination = requested,
                onDestinationRequestHandled = { requested = null },
            )
        }
        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onTab("Files").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onTab("Search").assertIsSelected()

        requested = TvDestination.Home
        compose.waitForIdle()

        compose.onTab("Files").assertIsSelected()
        compose.onNodeWithText("Your files will show up here.").assertIsDisplayed()
        compose.pressBack()
        compose.onTab("Files").assertIsFocused()
    }

    @Test
    fun searchTabEntersOnTheFieldAndAResultRequestMovesFocusIntoFiles() {
        val search = searchState(
            query = "",
            content = SearchContent.Idle,
            recentTerms = listOf(SearchTerm("tears")),
            nextRequestValue = 5L,
        )
        val actions = TvSearchActions(
            onQueryChanged = {}, onSubmit = {}, onResult = {}, onNextPage = {}, onRetry = {},
            recent = TvRecentSearchActions(onSearch = {}, onEdit = {}, onRetry = {}),
        )
        var requested by mutableStateOf<TvDestination?>(null)
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                searchPane = { paneFocus ->
                    TvSearchScreen(state = search, actions = actions, modifier = Modifier.focusRequester(paneFocus))
                },
                requestedDestination = requested,
                onDestinationRequestHandled = { requested = null },
            )
        }
        compose.openTab(TvDestination.Search)
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Search again for tears").assertIsFocused()

        compose.runOnIdle { requested = TvDestination.Files }
        compose.onTab("Files").assertIsSelected()
        compose.onNodeWithText("Your files will show up here.").assertIsFocused()
        compose.runOnIdle { assertEquals(null, requested) }
    }

    @Test
    fun historyTabEntersOnTheFirstRowAndUpFromItReachesTheTabBar() {
        val history = historyState(
            HistoryContent.Ready(
                listOf(
                    HistoryItem(
                        HistoryEventId(1),
                        "2026-09-10T10:00:00",
                        HistoryEventKind.File(HistoryFileId(5), "Sintel.mp4"),
                    ),
                ),
                HistoryPaging.Complete,
            ),
        )
        val events = mutableListOf<HistoryEvent>()
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                historyPane = { paneFocus ->
                    TvHistoryScreen(
                        state = history,
                        onEvent = { events += it; true },
                        modifier = Modifier.focusRequester(paneFocus),
                    )
                },
            )
        }
        compose.openTab(TvDestination.History)
        compose.onNodeWithContentDescription("Open Sintel.mp4").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        compose.onTab("History").assertIsFocused().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNode(hasText("Clear") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Open Sintel.mp4").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.OpenFile(HistoryFileId(5))), events)
    }
}
