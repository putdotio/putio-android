package io.putdotio.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.LocalMobileProductAnalyticsPromptMode
import io.putdotio.android.account.MOBILE_PRODUCT_ANALYTICS_ALLOW_TAG
import io.putdotio.android.account.MOBILE_PRODUCT_ANALYTICS_DENY_TAG
import io.putdotio.android.account.MOBILE_PRODUCT_ANALYTICS_PROMPT_TAG
import io.putdotio.android.account.MobileProductAnalyticsPrompt
import io.putdotio.android.account.MobileProductAnalyticsPromptMode
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigReducer
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.DefaultAccountSettingsPreferences
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileProductAnalyticsPromptTest {
    @get:Rule
    val compose = createComposeRule()

    private var settings by mutableStateOf(
        readyAccountSettingsState(DefaultAccountSettingsPreferences.copy(productAnalyticsEnabled = false)),
    )
    private var config by mutableStateOf(readyAndroidAppConfigState(AndroidAppConfigPreferences()))
    private val settingsEvents = mutableListOf<AccountSettingsEvent>()
    private val configEvents = mutableListOf<AndroidAppConfigEvent>()

    @Test
    fun allowSavesTheSettingThenTheAnswerThenCloses() {
        val dismissed = show()

        compose.onNodeWithText("Turn on product analytics?").assertExists()
        compose.onNodeWithTag(MOBILE_PRODUCT_ANALYTICS_ALLOW_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    AccountSettingsEvent.ChangeRequested(
                        AccountSettingsChange(AccountSettingsKey.ProductAnalytics, true),
                    ),
                ),
                settingsEvents,
            )
            assertEquals(emptyList<AndroidAppConfigEvent>(), configEvents)
        }
        compose.onNodeWithTag(MOBILE_PRODUCT_ANALYTICS_DENY_TAG).assertIsNotEnabled()

        compose.runOnIdle {
            settings = AccountSettingsReducer
                .reduce(settings, AccountSettingsEvent.SaveSucceeded(settings.savingId()))
                .state
        }
        compose.runOnIdle {
            assertEquals(listOf(answer), configEvents)
        }
        compose.runOnIdle {
            config = AndroidAppConfigReducer
                .reduce(config, AndroidAppConfigEvent.SaveSucceeded(config.savingId()))
                .state
        }
        compose.waitUntil(5_000) { dismissed.isNotEmpty() }
    }

    @Test
    fun aFailedSettingShowsTheErrorAndRecordsNoAnswer() {
        show()
        compose.onNodeWithTag(MOBILE_PRODUCT_ANALYTICS_ALLOW_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            settings = AccountSettingsReducer.reduce(
                settings,
                AccountSettingsEvent.SaveFailed(
                    settings.savingId(),
                    AccountSettingsFailure.Putio(PutioFailure.Unexpected(RuntimeException("offline"))),
                ),
            ).state
        }
        compose.onNodeWithText("Couldn’t save this setting").performScrollTo().assertExists()
        compose.runOnIdle { assertEquals(emptyList<AndroidAppConfigEvent>(), configEvents) }
    }

    @Test
    fun dontAllowOnAnAccountAlreadyOffRecordsOnlyTheAnswer() {
        show()
        compose.onNodeWithTag(MOBILE_PRODUCT_ANALYTICS_DENY_TAG).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(emptyList<AccountSettingsEvent>(), settingsEvents)
            assertEquals(listOf(answer), configEvents)
        }
    }

    @Test
    fun aLaunchThatOpensALinkSkipsTheAsk() {
        compose.setContent {
            PutioTheme {
                AskingHost(
                    sessionId = MobileAuthSessionId(1L),
                    accountSettingsState = settings,
                    appConfigState = config,
                    opensLaunchLink = { true },
                    onSettingsEvent = {},
                    onAppConfigEvent = {},
                )
            }
        }
        compose.onNodeWithTag(MOBILE_PRODUCT_ANALYTICS_PROMPT_TAG).assertDoesNotExist()
    }

    @Test
    fun aDebugLaunchStaysQuietUnlessTheHarnessOptsIn() {
        assertEquals(MobileProductAnalyticsPromptMode.Ask, MobileProductAnalyticsPromptMode.of(false, false))
        assertEquals(MobileProductAnalyticsPromptMode.Suppressed, MobileProductAnalyticsPromptMode.of(true, false))
        assertEquals(MobileProductAnalyticsPromptMode.Forced, MobileProductAnalyticsPromptMode.of(true, true))

        var mode by mutableStateOf(MobileProductAnalyticsPromptMode.Suppressed)
        compose.setContent {
            PutioTheme {
                key(mode) {
                    CompositionLocalProvider(LocalMobileProductAnalyticsPromptMode provides mode) {
                        ProductAnalyticsPromptHost(
                            sessionId = MobileAuthSessionId(1L),
                            accountSettingsState = settings,
                            appConfigState = readyAndroidAppConfigState(
                                AndroidAppConfigPreferences(productAnalyticsPromptAnswered = true),
                            ),
                            opensLaunchLink = { false },
                            onSettingsEvent = {},
                            onAppConfigEvent = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag(MOBILE_PRODUCT_ANALYTICS_PROMPT_TAG).assertDoesNotExist()
        // Opted in, it asks even though the shared account already answered.
        mode = MobileProductAnalyticsPromptMode.Forced
        compose.onNodeWithTag(MOBILE_PRODUCT_ANALYTICS_PROMPT_TAG).assertExists()
    }

    @Test
    fun theHostAsksWhileAnalyticsIsOffAndUnanswered() {
        compose.setContent {
            PutioTheme {
                AskingHost(
                    sessionId = MobileAuthSessionId(1L),
                    accountSettingsState = settings,
                    appConfigState = config,
                    opensLaunchLink = { false },
                    onSettingsEvent = {},
                    onAppConfigEvent = {},
                )
            }
        }
        compose.onNodeWithTag(MOBILE_PRODUCT_ANALYTICS_PROMPT_TAG).assertExists()
    }

    private fun show(): List<Unit> {
        val dismissed = mutableListOf<Unit>()
        compose.setContent {
            PutioTheme {
                MobileProductAnalyticsPrompt(
                    settingsState = settings,
                    appConfigState = config,
                    onSettingsEvent = { event ->
                        settingsEvents += event
                        settings = AccountSettingsReducer.reduce(settings, event).state
                    },
                    onAppConfigEvent = { event ->
                        configEvents += event
                        config = AndroidAppConfigReducer.reduce(config, event).state
                    },
                    onDismiss = { dismissed += Unit },
                )
            }
        }
        compose.onNodeWithTag(MOBILE_PRODUCT_ANALYTICS_PROMPT_TAG).assertExists()
        return dismissed.also { assertTrue(it.isEmpty()) }
    }

    @Composable
    private fun AskingHost(
        sessionId: MobileAuthSessionId,
        accountSettingsState: AccountSettingsState,
        appConfigState: AndroidAppConfigState,
        opensLaunchLink: () -> Boolean,
        onSettingsEvent: (AccountSettingsEvent) -> Unit,
        onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
    ) {
        CompositionLocalProvider(LocalMobileProductAnalyticsPromptMode provides MobileProductAnalyticsPromptMode.Ask) {
            ProductAnalyticsPromptHost(
                sessionId, accountSettingsState, appConfigState, opensLaunchLink, onSettingsEvent, onAppConfigEvent,
            )
        }
    }

    private fun AccountSettingsState.savingId() = (mutation as AccountSettingsMutation.Saving).requestId

    private fun AndroidAppConfigState.savingId() = (mutation as AndroidAppConfigMutation.Saving).requestId

    private val answer = AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.ProductAnalyticsPromptAnswered)
}
