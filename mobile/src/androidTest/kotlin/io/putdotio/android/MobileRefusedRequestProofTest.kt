package io.putdotio.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserReducer
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.MobileFilesScreen
import io.putdotio.android.files.copyForTest
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Synthetic proof that a refused request shows put.io's reason; the failure is built in-process, no API calls. */
@RunWith(AndroidJUnit4::class)
class MobileRefusedRequestProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic refused-request proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.refused.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun filesShowPutioReasonForARefusalAndTheAppCopyForAServerError() {
        var status by mutableStateOf(BAD_REQUEST)
        compose.setContent {
            val initial = FilesBrowserReducer.start().state
            val failed = initial.copyForTest(
                stack = listOf(initial.current.copy(content = FilesContent.Failed(refusal(status).toPutioFailure()))),
            )
            PutioTheme {
                Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    MobileFilesScreen(failed, onEvent = {}, onPlayMedia = {})
                }
            }
        }

        compose.onNodeWithText(REASON).assertIsDisplayed()
        screenshot("01-files-refused")

        compose.runOnIdle { status = SERVICE_UNAVAILABLE }
        compose.onNodeWithText("put.io is temporarily unavailable. Try again.").assertIsDisplayed()
        compose.onAllNodesWithText(REASON).assertCountEquals(0)
        screenshot("02-files-server-error")
    }

    private fun refusal(status: Int): PutioApiException {
        val body =
            """{"error_id":null,"error_message":"$REASON","error_type":"BadRequest",""" +
                """"error_uri":"http://api.put.io/v2/docs","extra":{},"status":"ERROR","status_code":$status}"""
        return PutioApiException(
            request = PutioRequestData("GET", "https://api.put.io/v2/files/list"),
            resolvedStatusCode = status,
            resolvedErrorType = "BadRequest",
            envelope = PutioApiErrorEnvelope(
                status = "ERROR",
                statusCode = status,
                errorType = "BadRequest",
                errorMessage = REASON,
            ),
            responseBody = body,
            message = REASON,
        )
    }

    private fun screenshot(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId =
            UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments().getString("putio.refused.runId")))
        val directory =
            File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "refused-proof-$runId")
        check(directory.mkdirs() || directory.isDirectory)
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

private const val BAD_REQUEST = 400
private const val SERVICE_UNAVAILABLE = 503
private const val REASON = "not a folder"
