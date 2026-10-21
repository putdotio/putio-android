package io.putdotio.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// SDK exception payloads stay redacted; fixed-label JUnit assertion failures pass through intact.
internal fun moveProofApiCheck(stage: String, block: suspend () -> Unit) {
    try { runBlocking { withTimeout(30_000) { block() } } }
    catch (error: Exception) { throw AssertionError("$stage failed (${error.javaClass.simpleName})") }
}

internal fun moveProofScreenshot(label: String) {
    val runId = InstrumentationRegistry.getArguments().getString("putio.move.runId")
    require(runId != null && runId.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
    require(label in setOf("cancel-picker", "collision", "folder-result", "ancestor-result", "root-result",
        "synthetic-back-files", "synthetic-back-account"))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.waitForIdle(100, 3_000)
    saveProofScreenshot(proofDirectory("move-proof-$runId"), label)
}
