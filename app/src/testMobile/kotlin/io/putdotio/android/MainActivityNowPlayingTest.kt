package io.putdotio.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityNowPlayingTest {
    private val openNowPlaying =
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(MobilePlaybackService.ACTION_OPEN_NOW_PLAYING)

    @Test
    fun anUnacknowledgedRequestSurvivesRecreationUntilTheShellActsOnIt() {
        ActivityScenario.launch<MainActivity>(openNowPlaying).use { scenario ->
            scenario.onActivity { assertTrue(it.nowPlayingRequests.pending.value) }

            // The shell had not finished acting (session lookup in flight) when the Activity died.
            scenario.recreate()
            scenario.onActivity { assertTrue(it.nowPlayingRequests.pending.value) }

            scenario.onActivity { it.nowPlayingRequests.acknowledge() }
            scenario.recreate()
            scenario.onActivity { assertFalse(it.nowPlayingRequests.pending.value) }

            scenario.onActivity { it.deliverIntentForTest(Intent(openNowPlaying)) }
            scenario.onActivity { assertTrue(it.nowPlayingRequests.pending.value) }
        }
    }

    @Test
    fun launchIntentIsPublishedOnceAndNeverFromHistory() {
        val tap = Intent(openNowPlaying)
        assertTrue(shouldPublishLaunchIntent(tap, alreadyConsumed = false))
        assertFalse(shouldPublishLaunchIntent(tap, alreadyConsumed = true))
        assertFalse(
            shouldPublishLaunchIntent(
                Intent(openNowPlaying).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY),
                alreadyConsumed = false,
            ),
        )
        assertFalse(shouldPublishLaunchIntent(Intent(Intent.ACTION_MAIN), alreadyConsumed = false))
        assertFalse(shouldPublishLaunchIntent(null, alreadyConsumed = false))
    }

    @Test
    fun aPlainLaunchPublishesNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertFalse(it.nowPlayingRequests.pending.value) }
        }
    }
}
