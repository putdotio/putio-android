package io.putdotio.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.KeystoreAuthTokenStore
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthController
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthConfiguration
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.PutioAuthSessionGateway
import io.putdotio.android.auth.SharedPreferencesPendingOAuthAttemptStore
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MobilePlaybackConfigIntegrationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun confirmedFormatChangeKeepsActivePlaybackAndAppliesToNextResolution() {
        val fixture = PlaybackConfigRootFixture()
        var mounted by mutableStateOf(true)
        try {
            compose.setContent { if (mounted) fixture.Content() }
            compose.waitUntil(5_000L) { compose.runOnIdle { fixture.confirmedFormat == VideoPlaybackType.Hls } }
            compose.waitUntil(5_000L) { compose.onAllNodesWithText("episode.mkv").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("episode.mkv").performClick()
            compose.waitUntil(5_000L) { fixture.server.mediaRequests.contains("/v2/files/42/hls/media.m3u8") }
            compose.onNodeWithTag(MOBILE_VIDEO_PLAYER_TAG).assertIsDisplayed()
            assertEquals(listOf("/v2/files/42"), fixture.server.resolutions.toList())

            compose.runOnIdle {
                assertTrue(
                    fixture.appConfigController.dispatch(
                        AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.VideoPlayback(VideoPlaybackType.Mp4)),
                    ),
                )
            }
            compose.waitUntil(5_000L) { compose.runOnIdle { fixture.confirmedFormat == VideoPlaybackType.Mp4 } }
            compose.waitForIdle()
            compose.onNodeWithTag(MOBILE_VIDEO_PLAYER_TAG).assertIsDisplayed()
            assertEquals(listOf("/v2/files/42"), fixture.server.resolutions.toList())
            assertEquals(listOf("/v2/files/42/hls/media.m3u8"), fixture.server.mediaRequests.toList())

            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("episode.mkv").performClick()
            compose.waitUntil(5_000L) { fixture.server.mediaRequests.contains("/v2/files/42/mp4/stream") }
            assertEquals(listOf("/v2/files/42", "/v2/files/42"), fixture.server.resolutions.toList())
            assertEquals(
                listOf("/v2/files/42/hls/media.m3u8", "/v2/files/42/mp4/stream"),
                fixture.server.mediaRequests.toList(),
            )
            assertEquals(emptyList<String>(), fixture.server.unexpectedRequests.toList())
        } finally {
            try {
                compose.runOnIdle { mounted = false }
                compose.waitForIdle()
            } finally {
                compose.runOnIdle { fixture.close() }
            }
        }
    }
}

private class PlaybackConfigRootFixture : Closeable {
    val server = PlaybackConfigHttpFixture()
    private val client = PutioClient(PutioConfig(accessToken = "synthetic-token", baseUrl = server.baseUrl))
    private val application = RuntimeEnvironment.getApplication()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job)
    private val account = MobileAccount(42L, "test-user", "test@example.invalid")
    private val sessionId = MobileAuthSessionId(1L)
    private val authState = MutableStateFlow<MobileAuthState>(MobileAuthState.SignedIn(account, sessionId))
    private val viewModels = ViewModelStore()
    private val files = own(MobileFilesViewModel(authState))
    private val settings = own(MobileAccountSettingsViewModel(authState))
    private val appConfig = own(MobileAndroidAppConfigViewModel(authState))
    private val search = own(MobileSearchHistoryViewModel(application, authState))
    private val transfers = own(MobileTransfersViewModel(authState))
    private val authController = MobileAuthController(
        oauthConfiguration = MobileOAuthConfiguration.fromClientId("9677"),
        tokenStore = KeystoreAuthTokenStore(application),
        pendingOAuthAttemptStore = SharedPreferencesPendingOAuthAttemptStore(application),
        sessionGateway = PutioAuthSessionGateway(client),
    )
    private val runtime = MobileOAuthRuntime(client, authController, scope)

    val appConfigController
        get() = requireNotNull(appConfig.controllerFor(account.userId, sessionId, SdkAndroidAppConfigRepository(client)))

    val confirmedFormat: VideoPlaybackType?
        get() = appConfigController.state.value.let { state ->
            if (state.mutation == AndroidAppConfigMutation.Idle) {
                (state.content as? AndroidAppConfigContent.Ready)?.preferences?.videoPlaybackType
            } else {
                null
            }
        }

    @Composable
    fun Content() {
        PutioTheme {
            SignedInMobileRoot(
                runtime = runtime,
                account = account,
                sessionId = sessionId,
                filesViewModel = files,
                accountSettingsViewModel = settings,
                appConfigViewModel = appConfig,
                searchHistoryViewModel = search,
                transfersViewModel = transfers,
                authController = authController,
                rootScope = scope,
            )
        }
    }

    override fun close() {
        viewModels.clear()
        runBlocking { withTimeout(2_000L) { job.cancelAndJoin() } }
        client.close()
        server.close()
    }

    private fun <T : ViewModel> own(viewModel: T): T =
        viewModel.also { viewModels.put(it.javaClass.name, it) }
}
