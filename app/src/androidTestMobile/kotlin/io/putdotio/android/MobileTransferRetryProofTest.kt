package io.putdotio.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.transfers.MobileTransfersScreen
import io.putdotio.android.transfers.SdkTransfersRepository
import io.putdotio.android.transfers.TransfersController
import io.putdotio.android.transfers.TransfersReadOperations
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.transfers.Transfer
import io.putdotio.sdk.transfers.TransferStatus
import io.putdotio.sdk.transfers.TransfersCleanResponse
import io.putdotio.sdk.transfers.TransfersListResponse
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Synthetic proof of transfer failure reasons and confirm-free retry; the SDK boundary is faked, no API calls. */
@RunWith(AndroidJUnit4::class)
class MobileTransferRetryProofTest {
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Synthetic transfer retry proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString("putio.transfers.enabled") == "true")
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun failedRowsShowTheServerReasonAndRetryReportsItsResult() {
        val retried = CopyOnWriteArrayList<Long>()
        val server = ConcurrentHashMap(TRANSFERS.associateBy(Transfer::id))
        val repository = SdkTransfersRepository(
            reads = TransfersReadOperations(
                list = { TransfersListResponse(cursor = null, transfers = server.values.sortedBy(Transfer::id), status = "OK") },
                continueList = { _, _ -> error("No second page") },
                get = { id -> requireNotNull(server[id]) },
            ),
            addTransfer = { error("No add in this proof") },
            cancelTransfers = { error("No cancel in this proof") },
            retryTransfer = { id ->
                retried += id
                if (id == REJECTED_ID) throw noErrorToRetry(id)
                requireNotNull(server[id]).copy(status = TransferStatus.IN_QUEUE, errorMessage = null)
                    .also { server[id] = it }
            },
            cleanTransfers = { TransfersCleanResponse(deletedIds = emptyList(), status = "OK") },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = TransfersController(repository, scope)
        try {
            compose.setContent {
                val state by controller.state.collectAsStateWithLifecycle()
                PutioTheme {
                    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        MobileTransfersScreen(state = state, onEvent = { controller.dispatch(it) })
                    }
                }
            }

            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText(SERVER_REASON).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(SERVER_REASON).assertIsDisplayed()
            compose.onNodeWithText("This transfer needs attention.").assertIsDisplayed()
            screenshot("01-failure-reasons")

            compose.onNodeWithContentDescription("Retry transfer $ACCEPTED_NAME").performClick()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("Retrying transfer").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("Retry transfer?").assertCountEquals(0)
            compose.onAllNodesWithText(SERVER_REASON).assertCountEquals(0)
            screenshot("02-retry-accepted")

            compose.onNodeWithContentDescription("Retry transfer $REJECTED_NAME").performClick()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText(REJECTED_MESSAGE).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("Retry transfer?").assertCountEquals(0)
            compose.onAllNodesWithText("Couldn’t update transfer").assertCountEquals(0)
            screenshot("03-retry-rejected")

            assertEquals(listOf(ACCEPTED_ID, REJECTED_ID), retried.toList())
        } finally {
            compose.runOnIdle { controller.close() }
            scope.cancel()
        }
    }

    private fun noErrorToRetry(id: Long) = PutioApiException(
        request = PutioRequestData("POST", "https://api.put.io/v2/transfers/retry?id=$id"),
        resolvedStatusCode = FORBIDDEN,
        resolvedErrorType = null,
        envelope = PutioApiErrorEnvelope(statusCode = FORBIDDEN),
        responseBody = "{}",
        message = "Synthetic retry rejection",
    )

    private fun screenshot(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId =
            UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments().getString("putio.transfers.runId")))
        val directory =
            File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "transfers-proof-$runId")
        check(directory.mkdirs() || directory.isDirectory)
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}

private const val TIMEOUT = 5_000L
private const val FORBIDDEN = 403
private const val ACCEPTED_ID = 21L
private const val REJECTED_ID = 22L
private const val ACCEPTED_NAME = "Harbor film.mp4"
private const val REJECTED_NAME = "Sample folder"
private const val SERVER_REASON = "Downloading text/html is not allowed."
private const val REJECTED_MESSAGE = "Couldn’t retry transfer. It has no error to retry."

private val TRANSFERS = listOf(
    Transfer(
        id = ACCEPTED_ID, name = ACCEPTED_NAME, status = TransferStatus.ERROR,
        errorMessage = SERVER_REASON, createdAt = "2026-09-30T08:00:00",
    ),
    Transfer(id = REJECTED_ID, name = REJECTED_NAME, status = TransferStatus.ERROR, createdAt = "2026-09-30T07:00:00"),
    Transfer(
        id = 23L, name = "Archive été 東京", status = TransferStatus.DOWNLOADING, size = 734_003_200.0,
        percentDone = 42.0, downSpeed = 1_572_864.0, createdAt = "2026-09-30T06:00:00",
    ),
)
