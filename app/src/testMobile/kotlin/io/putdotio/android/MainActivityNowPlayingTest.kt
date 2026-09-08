package io.putdotio.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityNowPlayingTest {
    @Test
    fun aConsumedNotificationActionIsNotRepublishedAcrossRepeatedRecreation() {
        val intent =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .setAction(MobilePlaybackService.ACTION_OPEN_NOW_PLAYING)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { assertNotNull(it.pendingNowPlayingRequest()) }

            scenario.recreate()
            scenario.onActivity { assertNull(it.pendingNowPlayingRequest()) }

            scenario.recreate()
            scenario.onActivity { assertNull(it.pendingNowPlayingRequest()) }

            // A fresh tap after restoration still arrives.
            scenario.onActivity { it.deliverIntentForTest(Intent(intent)) }
            scenario.onActivity { assertNotNull(it.pendingNowPlayingRequest()) }
        }
    }

    @Test
    fun aPlainLaunchPublishesNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertNull(it.pendingNowPlayingRequest()) }
        }
    }

    private fun MainActivity.pendingNowPlayingRequest(): Unit? =
        runBlocking { withTimeoutOrNull(200) { nowPlayingRequestFlow.first() } }
}
