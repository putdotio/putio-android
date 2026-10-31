package io.putdotio.android.tv

import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.MainActivity
import io.putdotio.android.files.AppConfigPinnedFiles
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.PINNED_FILES_KEY
import io.putdotio.android.tv.auth.TvAuthRuntime
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.sdk.config.AppConfigUpdate
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live proof of pins on an install signed in with `devs-auto`: reports the account's prior
 * `pinned_files` value, pins the caller's fixture through the app's store, checks that only that
 * key changed and that Home shows the card, then writes the prior value back, or deletes the key
 * when it had none. Opt-in with `putio.tv.pins.fixture` and `putio.tv.pins.runId`.
 */
@RunWith(AndroidJUnit4::class)
class TvPinsLiveProofTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun pinningWritesOneKeyAndTheKeyIsRestored() = runBlocking {
        val fixture = arguments.getString("putio.tv.pins.fixture")?.toLongOrNull()
        val runId = arguments.getString("putio.tv.pins.runId")
        assumeTrue("TV pins live proof requires a fixture id and a run id", fixture != null && runId != null)
        val runtime = withContext(Dispatchers.Main) { TvAuthRuntime.get(instrumentation.targetContext) }
        withContext(Dispatchers.Main) { runtime.authController.restoreSession(interactive = false) }
        withTimeout(TIMEOUT_MILLIS) { runtime.authController.state.first { it is TvAuthState.SignedIn } }
        val client = runtime.putioClient
        val before = client.appConfig.get()
        val prior = before[PINNED_FILES_KEY]
        report("prior", prior?.toString() ?: "absent")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val pins = AppConfigPinnedFiles(client, scope)
            withTimeout(TIMEOUT_MILLIS) { pins.pins.first { it != null } }
            withContext(Dispatchers.Main) { pins.pin(FilesItemId(checkNotNull(fixture))) }
            val after = withTimeout(TIMEOUT_MILLIS) {
                var read = client.appConfig.get()
                while (read[PINNED_FILES_KEY] == prior && pins.failure.value == null) {
                    delay(POLL_MILLIS)
                    read = client.appConfig.get()
                }
                read
            }
            assertEquals(null, pins.failure.value)
            assertEquals(fixture, pins.pins.value?.firstOrNull()?.value)
            val priorIds = (prior as? JsonArray).orEmpty().filterNot { (it as? JsonPrimitive)?.content == "$fixture" }
            assertEquals(JsonArray(listOf(JsonPrimitive(fixture)) + priorIds), after[PINNED_FILES_KEY])
            assertEquals(before.values - PINNED_FILES_KEY, after.values - PINNED_FILES_KEY)
            report("written", after[PINNED_FILES_KEY].toString())

            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(HOME_SETTLE_MILLIS)
                screenshot(checkNotNull(runId))
            }
        } finally {
            scope.cancel()
            if (prior == null) {
                deleteKey(client.config.baseUrl, checkNotNull(client.config.accessToken))
            } else {
                client.appConfig.save(AppConfigUpdate(PINNED_FILES_KEY, prior))
            }
            val restored = client.appConfig.get()[PINNED_FILES_KEY]
            report("restored", restored?.toString() ?: "absent")
            assertEquals(prior, restored)
        }
    }

    private fun deleteKey(baseUrl: String, token: String) {
        val connection = URL("${baseUrl.trimEnd('/')}/config/$PINNED_FILES_KEY").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "DELETE"
            connection.setRequestProperty("Authorization", "Token $token")
            check(connection.responseCode in 200..299) { "DELETE config key answered ${connection.responseCode}" }
        } finally {
            connection.disconnect()
        }
    }

    private fun report(key: String, value: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("putio.tv.pins.$key", value) })
    }

    private fun screenshot(runId: String) {
        val context = instrumentation.targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-pins-live-$runId")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "home-live-pinned.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 20_000L
        const val HOME_SETTLE_MILLIS = 8_000L
        const val POLL_MILLIS = 500L
    }
}
