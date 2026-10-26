package io.putdotio.android

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.playback.MobileCast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What Cast needs to find receivers on a real device, which the fake gateways never exercise. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileCastStartTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val playServices = MobileCast.playServices

    @After
    fun restorePlayServices() {
        MobileCast.playServices = playServices
    }

    @Test
    fun castStartsBeforeMainActivityResumes() {
        // Cast discovers only for activities that resume after it started.
        val events = mutableListOf<String>()
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreResumed(activity: Activity) {
                events += "resumed"
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(lifecycle)
        // No Play services here, so Cast goes no further than asking for it.
        MobileCast.playServices = {
            events += "cast"
            false
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertEquals("cast", events.firstOrNull())
                assertTrue("resumed" in events)
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(lifecycle)
        }
    }

    @Test
    fun manifestAsksForTheLocalNetworkCastFindsReceiversOn() {
        // Android 17 shows an app that targets it no Cast receivers without this permission.
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        assertTrue("android.permission.ACCESS_LOCAL_NETWORK" in requested)
    }
}
