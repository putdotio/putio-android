package io.putdotio.android

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileRetryWhenOnlineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aStoppedAppRetriesNothingUntilItIsStartedAgain() {
        val owner = TestLifecycleOwner()
        var retries = 0
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                RetryWhenOnline(failure = "offline") { retries++ }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }

        compose.mainClock.advanceTimeBy(RETRY_CLOCK_MILLIS * 3)
        compose.runOnIdle { assertEquals(0, retries) }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(RETRY_CLOCK_MILLIS)

        compose.runOnIdle { assertEquals(1, retries) }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = registry
    }
}

private const val RETRY_CLOCK_MILLIS = 30_000L
