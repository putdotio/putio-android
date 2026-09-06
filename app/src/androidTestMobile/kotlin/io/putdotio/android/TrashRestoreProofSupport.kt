package io.putdotio.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesRepository
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.trash.TrashController
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal fun <T> trashRestoreApiCheck(stage: String, block: suspend () -> T): T = try {
    runBlocking { withTimeout(30_000) { block() } }
} catch (error: Exception) {
    throw AssertionError("$stage failed (${error.javaClass.simpleName})")
}

internal fun trashRestoreScreenshot(label: String) {
    val runId = InstrumentationRegistry.getArguments().getString("putio.trash.restore.runId")
    require(runId != null && UUID.fromString(runId).toString() == runId)
    require(label in setOf("cancel-confirmation", "restore-started", "restore-result", "restored-files",
        "synthetic-empty", "synthetic-error", "synthetic-recovery"))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
        "trash-restore-proof-$runId")
    check(directory.mkdirs() || directory.isDirectory)
    instrumentation.uiAutomation.waitForIdle(100, 3_000)
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        File(directory, "$label.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally { bitmap.recycle() }
}

@Composable
internal fun TrashRestoreProofShell(
    controller: TrashController,
    files: FilesBrowserState,
    onFilesEvent: (FilesBrowserEvent) -> Boolean,
    account: MobileAccount = MobileAccount(42, "Synthetic proof", "synthetic@example.invalid"),
    sessionId: MobileAuthSessionId = MobileAuthSessionId(42),
    filesRepository: FilesRepository? = null,
    settings: AccountSettingsPreferences = AccountSettingsPreferences(false, false, false, false),
    config: AndroidAppConfigPreferences = AndroidAppConfigPreferences(),
) {
    PutioTheme {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.background) {
            MobileShell(
                filesState = files, filesRepository = filesRepository, trashController = controller,
                accountSettingsState = AccountSettingsState(AccountSettingsContent.Ready(settings),
                    AccountSettingsMutation.Idle, 1),
                appConfigState = AndroidAppConfigState(AndroidAppConfigContent.Ready(config),
                    AndroidAppConfigMutation.Idle, 1),
                account = account, sessionId = sessionId, playbackRepository = TrashRestoreNoPlayback,
                onFilesEvent = onFilesEvent,
                onAccountSettingsEvent = { error("Unexpected account settings mutation") },
                onAppConfigEvent = { error("Unexpected app configuration mutation") },
                onPlaybackAuthenticationRequired = { error("Unexpected playback authentication") },
                onSignOut = { error("Unexpected sign out") },
            )
        }
    }
}

private object TrashRestoreNoPlayback : PlaybackRepository {
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        error("Unexpected playback in Trash proof")
    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
        error("Unexpected playback in Trash proof")
}
