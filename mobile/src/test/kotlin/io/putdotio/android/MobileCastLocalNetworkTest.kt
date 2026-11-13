package io.putdotio.android

import android.Manifest
import android.app.Application
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.putdotio.android.playback.CastAvailability
import io.putdotio.android.playback.MOBILE_CAST_BUTTON_TAG
import io.putdotio.android.playback.MobileCastController
import io.putdotio.android.playback.rememberPlayerCast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileCastLocalNetworkTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val answers = mutableListOf<Boolean>()
    private var prompts = 0
    private var pickerOpened = 0
    private var answersOnHold = false
    private val heldAnswers = mutableListOf<Int>()
    private var buttonShown by mutableStateOf(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gateway = FakeCastGateway { 0L }.apply { availability.value = CastAvailability.NeedsLocalNetwork }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            assertEquals(PERMISSION, input)
            prompts++
            if (answersOnHold) {
                heldAnswers += requestCode
                return
            }
            setRationale(answers.removeFirst())
            dispatchResult(requestCode, false)
        }
    }

    @Test
    fun aRefusalKeepsTheButtonAskingUntilAndroidStopsPromptingThenItOpensTheAppSettings() {
        answers += listOf(true, false)
        setButton()

        tapButton()
        tapButton()
        assertEquals(2, prompts)
        assertEquals(2, gateway.localNetworkAnswers)
        assertNull(shadowOf(app).nextStartedActivity)

        tapButton()

        assertEquals(2, prompts)
        val settings = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals(Uri.parse("package:${app.packageName}"), settings.data)
        assertEquals(0, pickerOpened)
    }

    @Test
    fun deniedRequestsWithoutARationaleStillReachTheAppSettings() {
        answers += listOf(false, false)
        setButton()

        tapButton()
        tapButton()
        assertEquals(2, prompts)
        assertNull(shadowOf(app).nextStartedActivity)

        tapButton()

        assertEquals(2, prompts)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    @Test
    fun anAnswerThatLandsAfterTheControlsHideStillOpensThePicker() {
        answersOnHold = true
        setButton()

        tapButton()
        buttonShown = false
        compose.waitForIdle()
        compose.runOnIdle { registry.dispatchResult(heldAnswers.single(), true) }
        compose.waitForIdle()

        assertEquals(1, gateway.localNetworkAnswers)
        assertEquals(1, pickerOpened)
    }

    private fun setButton() {
        compose.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                },
            ) {
                PutioTheme {
                    val playerCast = rememberPlayerCast(
                        MobileCastController(gateway, scope) { 0L },
                        castMedia = { _, _ -> error("Nothing is cast") },
                        onOpenPicker = { pickerOpened++ },
                    )
                    if (buttonShown) playerCast.button(Modifier)
                }
            }
        }
    }

    private fun tapButton() {
        compose.onNodeWithTag(MOBILE_CAST_BUTTON_TAG).performClick()
        compose.waitForIdle()
    }

    private fun setRationale(show: Boolean) {
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(PERMISSION, show)
    }

    private companion object {
        const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
    }
}
