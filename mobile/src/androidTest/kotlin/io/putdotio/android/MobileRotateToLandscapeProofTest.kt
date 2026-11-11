package io.putdotio.android

import android.app.UiAutomation
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.account.MOBILE_ACCOUNT_LIST_TAG
import io.putdotio.android.account.MobileAccountScreen
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.DefaultMobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerFactory
import io.putdotio.android.playback.MobilePlayerScreen
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.confirmedRotateToLandscape
import io.putdotio.android.playback.playbackState
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigController
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.settings.readyAccountSettingsState
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MobileRotateToLandscapeProofTest {
    private val compose = createAndroidComposeRule<MobileFullscreenProofActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Rotate to landscape proof requires opt-in", arguments.getString(ENABLED) == "true")
                require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "Rotate to landscape proof requires API 30" }
                requireNotNull(arguments.getString(RUN_ID)) { "Missing $RUN_ID" }
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = mutableMapOf<String, JsonElement>()
    private val repository = SdkAndroidAppConfigRepository(
        getConfig = { AppConfig(store.toMap()) },
        saveConfig = { update -> store[update.key] = update.value },
    )
    private var controller by mutableStateOf<AndroidAppConfigController?>(null)
    private var showingVideo by mutableStateOf(false)
    private val savedRotation = listOf(ACCELEROMETER_ROTATION, USER_ROTATION).associateWith(::systemSetting)

    @After
    fun release() {
        automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        savedRotation.forEach { (name, value) -> shell("settings put system $name $value") }
        compose.runOnUiThread { showingVideo = false }
        scope.cancel()
    }

    @Test
    fun theSavedChoiceDecidesWhetherTheVideoTurnsTheWindow() {
        val video = landscapeVideoState()
        val factory = RotateProofPlayerFactory()
        compose.runOnUiThread {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller = AndroidAppConfigController(repository, scope)
        }
        compose.setContent {
            PutioTheme {
                Surface(Modifier.fillMaxSize()) {
                    val current = controller ?: return@Surface
                    val appConfig by current.state.collectAsState()
                    if (showingVideo) {
                        MobilePlayerScreen(
                            state = video,
                            onRetry = { error("Unexpected retry") },
                            onPlayerFailure = { failure, _ -> error("Local video failed: $failure") },
                            onBack = { showingVideo = false },
                            rotateToLandscape = appConfig.confirmedRotateToLandscape(),
                            playerFactory = factory,
                        )
                    } else {
                        MobileAccountScreen(
                            account = Account,
                            sessionId = MobileAuthSessionId(1L),
                            settingsState = readyAccountSettingsState(),
                            appConfigState = appConfig,
                            onSettingsEvent = {},
                            onAppConfigEvent = { current.dispatch(it) },
                            onSignOut = {},
                        )
                    }
                }
            }
        }
        val phone = compose.runOnIdle {
            compose.activity.resources.configuration.smallestScreenWidthDp < TABLET_SMALLEST_WIDTH_DP
        }
        freeze(UiAutomation.ROTATION_FREEZE_0)
        val natural = orientation()

        awaitConfigReady()
        rotationRow().assertIsOn()
        screenshot("account-default-on")
        compose.runOnIdle { assertTrue("Nothing is saved until the user changes it", store.isEmpty()) }

        compose.runOnIdle { showingVideo = true }
        awaitVideo(factory)
        if (phone) {
            awaitOrientation(Configuration.ORIENTATION_LANDSCAPE)
            compose.waitUntil(15_000) { compose.runOnIdle { displayRotation() % 2 == 1 } }
            compose.runOnIdle {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation)
            }
        } else {
            compose.runOnIdle {
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
            }
        }
        screenshot("player-on")

        compose.runOnIdle { showingVideo = false }
        awaitOrientation(natural)
        rotationRow().performClick()
        compose.waitUntil(5_000) {
            compose.runOnIdle { controller?.state?.value?.mutation == AndroidAppConfigMutation.Idle }
        }
        rotationRow().assertIsOff()
        compose.runOnIdle { assertEquals(JsonPrimitive(false), store[ROTATE_KEY]) }
        screenshot("account-off")

        compose.runOnIdle {
            controller?.close()
            controller = AndroidAppConfigController(repository, scope)
        }
        awaitConfigReady()
        rotationRow().assertIsOff()

        compose.runOnIdle { showingVideo = true }
        awaitVideo(factory)
        compose.mainClock.advanceTimeBy(1_000)
        Thread.sleep(1_000)
        compose.runOnIdle {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
            assertEquals(natural, orientation())
        }
        screenshot("player-off-rotation-locked")
        freeze(UiAutomation.ROTATION_FREEZE_90)
        compose.waitUntil(15_000) { compose.runOnIdle { orientation() != natural } }
        compose.runOnIdle {
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, compose.activity.requestedOrientation)
            assertNotEquals(natural, orientation())
        }
        screenshot("player-off-device-turned")
    }

    private fun rotationRow() = compose.run {
        onNodeWithTag(MOBILE_ACCOUNT_LIST_TAG).performScrollToNode(hasText(ROTATE_TITLE))
        onNodeWithText(ROTATE_TITLE)
    }

    private fun awaitConfigReady() = compose.waitUntil(10_000) {
        compose.runOnIdle { controller?.state?.value?.content is AndroidAppConfigContent.Ready }
    }

    private fun awaitVideo(factory: RotateProofPlayerFactory) = compose.waitUntil(30_000) {
        compose.runOnIdle {
            factory.player?.let {
                check(it.playerError == null) { "Video failed: ${it.playerError?.errorCodeName}" }
                it.playbackState == Player.STATE_READY && it.videoSize.width > it.videoSize.height
            } == true
        }
    }

    private fun awaitOrientation(expected: Int) = compose.waitUntil(15_000) {
        compose.runOnIdle { orientation() == expected }
    }

    private fun orientation(): Int = compose.activity.resources.configuration.orientation

    private fun displayRotation(): Int = checkNotNull(compose.activity.display).rotation

    private fun freeze(rotation: Int) {
        automation.setRotation(rotation)
        compose.waitUntil(15_000) {
            compose.runOnIdle { displayRotation() == rotation && orientationMatchesDisplay() }
        }
    }

    private fun orientationMatchesDisplay(): Boolean {
        val bounds = compose.activity.windowManager.currentWindowMetrics.bounds
        val landscape = bounds.width() > bounds.height()
        return landscape == (orientation() == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun systemSetting(name: String): String = shell("settings get system $name").trim()

    private fun shell(command: String): String =
        automation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
        }

    private fun landscapeVideoState(): PlaybackState {
        val file = proofFixture(FIXTURE)
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        return playbackState(
            target = PlaybackTarget(FilesItemId(9_640_001), file.name, PlaybackMediaType.VIDEO),
            content = PlaybackContent.Ready(
                PlaybackSource(
                    fileId = 9_640_001,
                    kind = PlaybackSourceKind.ORIGINAL,
                    url = url,
                    startFromSeconds = 0.0,
                    subtitles = PlaybackSubtitles.None,
                ),
            ),
            nextRequestValue = 1,
        )
    }

    private fun screenshot(label: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        automation.waitForIdle(100, 3_000)
        val run = requireNotNull(arguments.getString(RUN_ID)) { "Missing $RUN_ID" }
        saveProofScreenshot(proofDirectory("rotate-to-landscape-proof-$run"), label)
    }

    private val automation: UiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val arguments get() = InstrumentationRegistry.getArguments()

    private companion object {
        const val ENABLED = "putio.rotate.enabled"
        const val FIXTURE = "putio.rotate.fixture"
        const val RUN_ID = "putio.rotate.runId"
        const val ROTATE_KEY = "auto_rotate_video"
        const val ROTATE_TITLE = "Rotate to landscape"
        const val TABLET_SMALLEST_WIDTH_DP = 600
        const val ACCELEROMETER_ROTATION = "accelerometer_rotation"
        const val USER_ROTATION = "user_rotation"
        val Account = MobileAccount(userId = 640L, username = "rotate-proof", email = "rotate-proof@example.com")
    }
}

private class RotateProofPlayerFactory : MobilePlayerFactory {
    var player: Player? = null
        private set
    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        DefaultMobilePlayerFactory.create(context, mediaType).also { player = it }
    override fun stopAudio(context: Context) = Unit
}
