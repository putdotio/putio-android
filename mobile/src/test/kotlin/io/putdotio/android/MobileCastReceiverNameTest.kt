package io.putdotio.android

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileCastReceiverNameTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun aReceiverThatReportsNoNameIsCalledChromecast() {
        val gateway = FakeCastGateway { 0L }.apply { availability.value = CastAvailability.Connected("") }
        var target: String? = null
        compose.setBuiltContent {
            PutioTheme {
                val playerCast = rememberPlayerCast(
                    MobileCastController(gateway, scope) { 0L },
                    castMedia = { _, _ -> error("Nothing is cast") },
                    onOpenPicker = {},
                )
                target = playerCast.casting?.deviceName
                playerCast.button(Modifier)
            }
        }

        compose.onNodeWithTag(MOBILE_CAST_BUTTON_TAG).assertContentDescriptionEquals("Casting to Chromecast")
        compose.runOnIdle { assertEquals("Chromecast", target) }
    }
}
