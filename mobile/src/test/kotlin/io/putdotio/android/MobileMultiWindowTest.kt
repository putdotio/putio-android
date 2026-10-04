package io.putdotio.android

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEffect
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesPage
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.android.settings.readyAndroidAppConfigState
import io.putdotio.sdk.files.PlaybackConversionState
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Split-screen, freeform and keyboard changes, with and without the Activity being recreated. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileMultiWindowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun resizingRotatingAndAttachingAKeyboardKeepTheActivityAndWhatItHolds() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val draft = activity.transferDraft
            val resized = Configuration(activity.resources.configuration).apply {
                screenWidthDp = 1280
                screenHeightDp = 800
                smallestScreenWidthDp = 800
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenLayout = Configuration.SCREENLAYOUT_SIZE_XLARGE or Configuration.SCREENLAYOUT_LONG_NO
                keyboard = Configuration.KEYBOARD_QWERTY
                keyboardHidden = Configuration.KEYBOARDHIDDEN_NO
                hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO
            }

            controller.configurationChange(resized)

            assertSame(activity, controller.get())
            assertSame(draft, controller.get().transferDraft)
        }
    }

    @Test
    fun aDensityChangeStillRecreatesTheActivity() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val draft = activity.transferDraft
            val denser = Configuration(activity.resources.configuration).apply { densityDpi *= 2 }

            controller.configurationChange(denser)

            assertNotSame(activity, controller.get())
            // Recreation keeps the Activity-scoped intake through its view model.
            assertSame(draft, controller.get().transferDraft)
        }
    }

    @Test
    fun theShellKeepsItsDestinationAsTheWindowResizesAndWhenTheActivityIsRecreated() {
        var width by mutableStateOf(400.dp)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Shell(width) }

        compose.onNodeWithTag(MOBILE_NAV_BAR_TAG).assertExists()
        compose.onNode(hasText("Search") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).performClick()

        compose.runOnIdle { width = 900.dp }
        compose.onNode(hasText("Search") and hasAnyAncestor(hasTestTag(MOBILE_NAV_RAIL_TAG))).assertIsSelected()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasText("Search") and hasAnyAncestor(hasTestTag(MOBILE_NAV_RAIL_TAG))).assertIsSelected()

        compose.runOnIdle { width = 360.dp }
        compose.onNode(hasText("Search") and hasAnyAncestor(hasTestTag(MOBILE_NAV_BAR_TAG))).assertIsSelected()
    }

    @androidx.compose.runtime.Composable
    private fun Shell(width: Dp) {
        PutioTheme {
            Box(Modifier.requiredSize(width = width, height = 700.dp)) {
                MobileShell(
                    playbackPlayerFactory = NoAudioSessionFactory,
                    filesState = emptyFiles(),
                    accountSettingsState = readyAccountSettingsState(),
                    appConfigState = readyAndroidAppConfigState(),
                    account = MobileAccount(userId = 42L, username = "user", email = "user@example.com"),
                    playbackRepository = unusedPlayback,
                    sessionId = MobileAuthSessionId(1L),
                    onFilesEvent = { true },
                    onAccountSettingsEvent = {},
                    onPlaybackAuthenticationRequired = {},
                    onSignOut = {},
                )
            }
        }
    }

    private fun emptyFiles(): FilesBrowserState {
        val initial = FilesBrowserReducer.start()
        val requestId = (initial.effect as FilesBrowserEffect.LoadFolder).requestId
        val page = FilesPage(emptyList(), nextCursor = null)
        return FilesBrowserReducer.reduce(initial.state, FilesBrowserEvent.LoadSucceeded(requestId, page)).state
    }

    private val unusedPlayback = object : PlaybackRepository {
        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(PlaybackResolution.Conversion(PlaybackConversionState.Queued))

        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended
    }
}
