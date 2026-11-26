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

internal fun proofFixture(argument: String): File {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)).canonicalFile
    val path = requireNotNull(InstrumentationRegistry.getArguments().getString(argument)) { "Missing $argument" }
    val file = File(path).canonicalFile
    require(file.toPath().startsWith(directory.toPath())) { "$argument must sit under $directory" }
    require(file.isFile && file.canRead()) { "Unreadable fixture $file" }
    return file
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
