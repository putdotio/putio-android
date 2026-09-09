package io.putdotio.android

import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivityDeepLinkTest {
    @Test
    fun launchLinkRoutesOnceAndSurvivesRecreationWithoutReplaying() {
        ActivityScenario.launch<MainActivity>(view("https://app.put.io/files/7")).use { scenario ->
            lateinit var requests: MobileDeepLinkRequests
            scenario.onActivity {
                requests = it.deepLinkRequests
                assertEquals(MobileDeepLink.File(FilesItemId(7L)), requests.pending.value)
                assertNull(it.intent.data)
                requests.acknowledge(MobileDeepLink.File(FilesItemId(7L)))
            }
            scenario.recreate()
            scenario.onActivity { assertNull(it.deepLinkRequests.pending.value) }
        }
    }

    @Test
    fun freshLinksRouteWhileHistoryReplaysAreOnlyScrubbed() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            with(controller.get()) {
                val history = view("putio://transfers").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
                deliverIntentForTest(history)
                assertNull(deepLinkRequests.pending.value)
                assertNull(history.data)
                deliverIntentForTest(view("putio://transfers"))
                assertEquals(MobileDeepLink.Transfers, deepLinkRequests.pending.value)
            }
        }
    }

    @Test
    fun aNewProcessRestoringTheConsumedFlagDoesNotRouteTheLaunchLinkAgain() {
        val saved = Bundle()
        Robolectric.buildActivity(MainActivity::class.java, view("putio://trash")).setup().use { controller ->
            controller.saveInstanceState(saved)
        }
        Robolectric.buildActivity(MainActivity::class.java, view("putio://trash"))
            .create(saved).start().resume().visible().use { restored ->
                assertNull(restored.get().deepLinkRequests.pending.value)
            }
    }

    private fun view(uri: String): Intent =
        Intent(Intent.ACTION_VIEW, uri.toUri(), ApplicationProvider.getApplicationContext(), MainActivity::class.java)
}
