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
import io.putdotio.android.playback.CastAvailability
import io.putdotio.android.playback.MOBILE_CAST_BUTTON_TAG
import io.putdotio.android.playback.MobileCastButton
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
    private var localNetworkAnswers = 0

    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            assertEquals(PERMISSION, input)
            prompts++
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
        assertEquals(2, localNetworkAnswers)
        assertNull(shadowOf(app).nextStartedActivity)

        tapButton()

        assertEquals(2, prompts)
        val settings = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals(Uri.parse("package:${app.packageName}"), settings.data)
        assertEquals(0, pickerOpened)
    }

    @Test
    fun aDismissedFirstPromptAsksAgainInsteadOfOpeningTheAppSettings() {
        answers += listOf(false, false)
        setButton()

        tapButton()
        tapButton()

        assertEquals(2, prompts)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun aRefusalThatEndsThePromptsOpensTheAppSettingsOnTheNextTap() {
        setRationale(true)
        answers += false
        setButton()

        tapButton()
        tapButton()

        assertEquals(1, prompts)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }

    private fun setButton() {
        compose.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                },
            ) {
                PutioTheme {
                    MobileCastButton(
                        availability = CastAvailability.NeedsLocalNetwork,
                        onClick = { pickerOpened++ },
                        onLocalNetworkAnswer = { localNetworkAnswers++ },
                    )
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
