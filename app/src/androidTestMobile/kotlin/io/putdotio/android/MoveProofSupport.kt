package io.putdotio.android

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal fun moveProofApiCheck(stage: String, block: suspend () -> Unit) {
    try { runBlocking { withTimeout(30_000) { block() } } }
    catch (error: Exception) { throw AssertionError("$stage failed (${error.javaClass.simpleName})") }
}

internal fun moveProofScreenshot(label: String) {
    val runId = InstrumentationRegistry.getArguments().getString("putio.move.runId")
    require(runId != null && runId.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
    require(label in setOf("cancel-picker", "collision", "folder-result", "ancestor-result", "root-result",
        "synthetic-recovery", "synthetic-picker-error", "synthetic-back-files", "synthetic-back-account"))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "move-proof-$runId")
    check(directory.mkdirs() || directory.isDirectory)
    instrumentation.uiAutomation.waitForIdle(100, 3_000)
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        File(directory, "$label.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally { bitmap.recycle() }
}
