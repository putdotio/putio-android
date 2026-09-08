package io.putdotio.android

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.trash.TrashContent
import io.putdotio.android.trash.TrashItem
import io.putdotio.android.trash.TrashState
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

internal const val ACCESSIBILITY_FILE_NAME = "Bodrum été — documentary collection.mp4"

internal fun accessibilityProofOptIn(): TestRule = TestRule { base, _ ->
    object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            assumeTrue("Controlled accessibility proof requires opt-in",
                InstrumentationRegistry.getArguments().getString("putio.accessibility.enabled") == "true")
            require(Build.VERSION.SDK_INT == 37) { "Accessibility proof requires API 37" }
            require(context.resources.configuration.fontScale >= 1.99f) { "Set system font_scale to 2.0 before proof" }
            for (setting in listOf(Settings.Global.ANIMATOR_DURATION_SCALE,
                Settings.Global.WINDOW_ANIMATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE)) {
                require(Settings.Global.getFloat(context.contentResolver, setting, 1f) == 0f) {
                    "Disable system animation scales before proof"
                }
            }
            accessibilityProofDirectory()
            base.evaluate()
        }
    }
}

internal fun accessibilityProofDirectory(): File {
    val runId = UUID.fromString(requireNotNull(
        InstrumentationRegistry.getArguments().getString("putio.accessibility.runId"),
    ))
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    return File(requireNotNull(context.getExternalFilesDir(null)), "accessibility-proof-$runId").also {
        check(it.mkdirs() || it.isDirectory)
    }
}

internal fun accessibilityProofScreenshot(
    label: String,
    automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation,
) {
    require(label.matches(Regex("[a-z0-9-]+")))
    automation.waitForIdle(100, 3_000)
    val bitmap = requireNotNull(automation.takeScreenshot())
    try {
        File(accessibilityProofDirectory(), "$label.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally { bitmap.recycle() }
}

internal fun accessibilityFiles(): FilesBrowserState = FilesBrowserState(listOf(FilesFolderState(
    FilesFolder.Root,
    FilesContent.Ready(listOf(FilesItem(
        FilesItemId(147), FilesFolder.Root.id, ACCESSIBILITY_FILE_NAME, PutioFileType.VIDEO,
        128_000_000, "2026-09-08T12:00:00Z",
    )), FilesPaging.Complete),
)), 1)

internal fun accessibilityTrash(): TrashState = TrashState(TrashContent.Loaded(
    items = listOf(TrashItem(FilesItemId(147), FilesFolder.Root.id, ACCESSIBILITY_FILE_NAME,
        PutioFileType.VIDEO, 128_000_000, "2026-09-08T12:00:00Z", "2026-09-30T12:00:00Z")),
    nextCursor = null, total = 1, trashSizeBytes = 128_000_000,
))

internal fun accessibilitySettings(): AccountSettingsState = AccountSettingsState(
    AccountSettingsContent.Ready(AccountSettingsPreferences(false, true, false, false)),
    AccountSettingsMutation.Idle, 1,
)

internal fun accessibilityAppConfig(): AndroidAppConfigState = AndroidAppConfigState(
    AndroidAppConfigContent.Ready(AndroidAppConfigPreferences()), AndroidAppConfigMutation.Idle, 1,
)
