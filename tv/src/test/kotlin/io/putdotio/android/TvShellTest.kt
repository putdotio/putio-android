package io.putdotio.android

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.tv.TvDestination
import io.putdotio.android.tv.TvLinkScreen
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

/**
 * Local (JVM) proof of the two TV screens: the device-code screen shows the
 * code and put.io/link, and the signed-in shell lists the four drawer
 * destinations with focus starting in the pane. The on-device counterpart is
 * LaunchSmokeTest plus the harness recording.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
class TvShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun linkScreenShowsCodeAndLinkUrlWithNewCodeFocused() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvLinkScreen(phase = TvLinkPhase.AwaitingLink("GIQTKN"), sessionExpired = false, onRequestNewCode = {})
            }
        }

        compose.onNodeWithContentDescription("Activation code GIQTKN").assertIsDisplayed()
        compose.onNodeWithText("put.io/link").assertIsDisplayed()
        compose.onNodeWithText("Get new code").assertIsFocused()
    }

    @Test
    fun anExpiredSessionIsExplainedWhileTheNewCodeIsLive() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvLinkScreen(phase = TvLinkPhase.AwaitingLink("GIQTKN"), sessionExpired = true, onRequestNewCode = {})
            }
        }

        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Activation code GIQTKN").assertIsDisplayed()
    }

    @Test
    fun expiredCodeExplainsAndOffersANewOne() {
        var requests = 0
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvLinkScreen(
                    phase = TvLinkPhase.Stopped(TvLinkStop.CodeExpired),
                    sessionExpired = true,
                    onRequestNewCode = { requests += 1 },
                )
            }
        }

        compose.onNodeWithText("Your session expired. Sign in again to continue.").assertIsDisplayed()
        compose.onNodeWithText("That code expired. Get a new one to continue.").assertIsDisplayed()
        compose.onNodeWithText("Get new code").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(1, requests)
    }

    @Test
    fun inactiveAccountNoticeShowsAboveEveryPaneWithoutTakingFocusOrBack() {
        val zone = java.time.ZoneId.systemDefault()
        val deletion = java.time.LocalDate.now(zone).plusDays(14).atTime(12, 0).atZone(zone).toInstant()
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        }
        compose.onNodeWithText("Your account has been deactivated 😢").assertIsDisplayed()
        compose.onNodeWithText("Your files are still here, but they are scheduled to be deleted in 14 days.")
            .assertIsDisplayed()
        compose.onAllNodesWithText("app.put.io", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Keep a good thing going!").assertDoesNotExist()

        compose.onNodeWithText("Your files will show up here.").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithText("Your files will show up here.").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Sign out").assertIsFocused()
        compose.onNodeWithText("Your account has been deactivated 😢").assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Your files will show up here.").assertIsFocused()
        compose.onNodeWithText("Your account has been deactivated 😢").assertIsDisplayed()
    }

    @Test
    fun shellListsDestinationsAndDpadMovesBetweenDrawerAndPane() {
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvShell(account = TvAccount(userId = 1, username = "user", email = "user@example.com"), onSignOut = {})
            }
        }

        compose.onNodeWithText("put.io").assertIsDisplayed()
        compose.onNodeWithText("Your files will show up here.").assertIsFocused()

        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).assertIsFocused()
        listOf("Files", "Search", "History", "Account").forEach {
            compose.onNode(hasText(it) and hasClickAction()).assertIsDisplayed()
        }

        compose.onNode(hasText("Files") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Account") and hasClickAction()).assertIsFocused()
        compose.onNode(hasText("Account") and hasClickAction()).performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithText("Signed in as user").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsFocused()
    }

    @Test
    fun searchDestinationFocusesTheFieldAndAResultRequestReturnsToFiles() {
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
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
        }
        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionDown)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithTag(TV_SEARCH_FIELD_TAG).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Search again for tears").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("Search") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription("Search again for tears").assertIsFocused()

        compose.runOnIdle { requested = TvDestination.Files }
        compose.onNodeWithText("Your files will show up here.").assertIsFocused()
        compose.runOnIdle { assertEquals(null, requested) }
    }

    @Test
    fun historyDestinationFocusesTheFirstRowAndAnOpenRequestReturnsToFiles() {
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
        var requested by mutableStateOf<TvDestination?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
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
                    requestedDestination = requested,
                    onDestinationRequestHandled = { requested = null },
                )
            }
        }
        compose.onNodeWithText("Your files will show up here.").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNode(hasText("Files") and hasClickAction()).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithContentDescription("Open Sintel.mp4").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionLeft)
        }
        compose.onNode(hasText("History") and hasClickAction()).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription("Open Sintel.mp4").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        assertEquals(listOf<HistoryEvent>(HistoryEvent.OpenFile(HistoryFileId(5))), events)

        compose.runOnIdle { requested = TvDestination.Files }
        compose.onNodeWithText("Your files will show up here.").assertIsFocused()
        compose.runOnIdle { assertEquals(null, requested) }
    }
}
