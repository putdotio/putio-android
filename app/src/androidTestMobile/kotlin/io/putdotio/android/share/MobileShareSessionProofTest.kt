package io.putdotio.android.share

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.MainActivity
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.files.FilesItemId
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * The real share service and MainActivity with a controlled session and an in-process download source;
 * no API call, credential or sign-in UI. A control run proves the lane sees a chooser when one opens.
 */
@RunWith(AndroidJUnit4::class)
class MobileShareSessionProofTest {
    @get:Rule val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Share session proof requires opt-in",
                    arguments.getString("putio.share.session.enabled") == "true")
                require(Build.VERSION.SDK_INT == 37) { "Share session proof requires API 37" }
                runId()
                base.evaluate()
            }
        }
    }

    @Test
    fun signingOutDuringAnExportNeverOpensTheChooser() {
        val auth = MutableStateFlow<MobileAuthState>(signedIn(1L))
        val source = GatedSource(posterJpeg())
        MobileFileShareService.dependenciesForTest = MobileShareDependencies(
            http = OkHttpClient.Builder().addInterceptor(source).build(),
            authState = auth,
            accessToken = { "proof-token" },
        )
        val choosers = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitResumed(scenario)

                source.arm()
                MobileFileShareService.start(context, FilesItemId(1L), "session-proof.jpg")
                source.awaitRequest()
                source.release()
                awaitCondition("control chooser") { choosers.hits == 1 }
                awaitCondition("chooser window") { activeWindowPackage() !in listOf(null, context.packageName) }
                // The sheet slides in after its window takes focus.
                SystemClock.sleep(SETTLE_MS)
                screenshot("control-chooser-opens")
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                awaitResumed(scenario)

                source.arm()
                MobileFileShareService.start(context, FilesItemId(2L), "session-proof.jpg")
                source.awaitRequest()
                screenshot("export-running")
                auth.value = MobileAuthState.SigningOut
                auth.value = MobileAuthState.SignedOut()
                // The download finishes after the session ended; delivery must still drop it.
                source.release()
                awaitCondition("share folder removed") { !MobileFileShareService.shareRoot(context).exists() }
                SystemClock.sleep(SETTLE_MS)
                assertEquals("No chooser may open after sign-out", 1, choosers.hits)
                assertFalse(MobileFileShareService.shareRoot(context).exists())
                awaitResumed(scenario)
                assertEquals(context.packageName, activeWindowPackage())
                screenshot("signed-out-no-chooser")
            }
        } finally {
            source.release()
            instrumentation.removeMonitor(choosers)
            MobileFileShareService.dependenciesForTest = null
        }
    }

    private fun awaitResumed(scenario: ActivityScenario<MainActivity>) {
        awaitCondition("MainActivity resumed") { scenario.state == Lifecycle.State.RESUMED }
    }

    private fun activeWindowPackage(): String? =
        instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()

    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $label" }
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun screenshot(label: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "share-session-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.share.session.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** Holds each download until released, so the session can end while the export is running. */
    private class GatedSource(private val body: ByteArray) : Interceptor {
        @Volatile private var requested = CountDownLatch(1)
        @Volatile private var gate = CountDownLatch(1)

        fun arm() {
            requested = CountDownLatch(1)
            gate = CountDownLatch(1)
        }

        fun awaitRequest() = check(requested.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "No download request" }

        fun release() = gate.countDown()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            check(request.header("Authorization") == "Token proof-token")
            check(!request.url.toString().contains("proof-token"))
            requested.countDown()
            check(gate.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "Download never released" }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("image/jpeg".toMediaType()))
                .build()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
        const val SETTLE_MS = 3_000L

        fun signedIn(session: Long) = MobileAuthState.SignedIn(
            account = MobileAccount(userId = 1L, username = "proof", email = "proof@example.com"),
            sessionId = MobileAuthSessionId(session),
        )

        fun posterJpeg(): ByteArray {
            val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.rgb(0xFD, 0xCE, 0x45))
                drawText("share session proof", 40f, 190f, Paint().apply { textSize = 48f; color = Color.BLACK })
            }
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                bitmap.recycle()
                output.toByteArray()
            }
        }
    }
}
