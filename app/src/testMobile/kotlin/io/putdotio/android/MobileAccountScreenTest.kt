package io.putdotio.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobileAccountScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun accountIdentitySettingsAndSignOutAreAvailable() {
        var signedOut = false
        val events = mutableListOf<AccountSettingsEvent>()
        setAccountContent(
            state = readyAccountSettingsState(),
            events = events,
            onSignOut = { signedOut = true },
        )

        compose.onNodeWithText("putio-user").assertIsDisplayed()
        compose.onNodeWithText("user@example.com").assertIsDisplayed()
        compose.onNodeWithText("Show subtitles").performClick()
        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToIndex(8)
        compose.onNodeWithText("Sign out").performClick()

        assertEquals(
            AccountSettingsEvent.ChangeRequested(
                AccountSettingsChange(AccountSettingsKey.ShowSubtitles, enabled = false),
            ),
            events.single(),
        )
        assertTrue(signedOut)
    }

    @Test
    fun turningOffTrashRequiresConfirmation() {
        val events = mutableListOf<AccountSettingsEvent>()
        setAccountContent(
            state = readyAccountSettingsState(),
            events = events,
        )

        compose.onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToIndex(7)
        compose.onNodeWithText("Move deleted files to Trash").performClick()
        compose.onNodeWithText("Turn off Trash?").assertIsDisplayed()
        assertTrue(events.isEmpty())

        compose.onNodeWithText("Turn off").performClick()

        assertEquals(
            AccountSettingsEvent.ChangeRequested(
                AccountSettingsChange(AccountSettingsKey.Trash, enabled = false),
            ),
            events.single(),
        )
    }

    @Test
    fun loadingAndFailuresRemainRecoverable() {
        val events = mutableListOf<AccountSettingsEvent>()
        var state by mutableStateOf(
            AccountSettingsState(
                content = AccountSettingsContent.Loading(AccountSettingsRequestId(1L)),
                mutation = AccountSettingsMutation.Idle,
                nextRequestValue = 2L,
            ),
        )
        compose.setContent {
            PutioTheme {
                MobileAccountScreen(
                    account = Account,
                    settingsState = state,
                    onSettingsEvent = events::add,
                    onSignOut = {},
                )
            }
        }
        compose.onNodeWithText("Loading account settings").assertIsDisplayed()

        compose.runOnIdle {
            state =
                AccountSettingsState(
                    content =
                        AccountSettingsContent.Failed(
                            AccountSettingsFailure.AccessDenied(PutioConfigurationException("restricted")),
                        ),
                    mutation = AccountSettingsMutation.Idle,
                    nextRequestValue = 2L,
                )
        }
        compose.onNodeWithText("This app doesn’t have access to account settings.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(AccountSettingsEvent.RetryLoad, events.single())
    }

    private fun setAccountContent(
        state: AccountSettingsState,
        events: MutableList<AccountSettingsEvent>,
        onSignOut: () -> Unit = {},
    ) {
        compose.setContent {
            PutioTheme {
                MobileAccountScreen(
                    account = Account,
                    settingsState = state,
                    onSettingsEvent = events::add,
                    onSignOut = onSignOut,
                )
            }
        }
    }

    private companion object {
        val Account =
            MobileAccount(
                userId = 42L,
                username = "putio-user",
                email = "user@example.com",
            )
    }
}
