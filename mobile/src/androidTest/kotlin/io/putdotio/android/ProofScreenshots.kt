package io.putdotio.android

import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun proofDirectory(name: String): File {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return File(requireNotNull(context.getExternalFilesDir(null)), name).also {
        check(it.mkdirs() || it.isDirectory)
    }
}

internal fun saveProofScreenshot(
    directory: File,
    label: String,
    automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation,
) {
    val bitmap = requireNotNull(automation.takeScreenshot())
    try {
        File(directory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        bitmap.recycle()
    }
}
