package io.putdotio.android

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.account.MobileAccountSettingsError
import io.putdotio.android.account.MobileAppConfigLoadError
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.MobileFilesNotice
import io.putdotio.android.files.MobileFilesScreen
import io.putdotio.android.files.filesBrowserState
import io.putdotio.android.playback.MobilePlaybackFailureState
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.sharing.MobilePublicLinks
import io.putdotio.android.sharing.MobilePublicLinksScreen
import io.putdotio.android.sharing.PublicLinksContent
import io.putdotio.android.sharing.PublicLinksState
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.PutioFailureReports
import io.putdotio.android.telemetry.TelemetryFailure
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.MOBILE_TRANSFERS_LIST_TAG
import io.putdotio.android.transfers.MobileTransfersScreen
import io.putdotio.android.transfers.TransferCursor
import io.putdotio.android.transfers.TransfersContent
import io.putdotio.android.transfers.TransfersPaging
import io.putdotio.android.transfers.TransfersRefresh
import io.putdotio.android.transfers.transfersState
import io.putdotio.sdk.errors.PutioConfigurationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [35],
    qualifiers = "en-rUS",
    instrumentedPackages = ["io.putdotio.android.telemetry"],
    shadows = [MobileFailureReportsTest.RecordingDiagnostics::class],
)
class MobileFailureReportsTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    @After
    fun resetDiagnostics() {
        RecordingDiagnostics.reset()
    }

    @Test
    fun filesUnexpectedFailureCapturesOnceShowsEventIdAndSkipsDuplicateOnRecompose() {
        val failure = PutioFailure.Unexpected(IllegalStateException("files boom"))
        RecordingDiagnostics.nextId = ERROR_ID
        var tick by mutableStateOf(0)
        compose.setBuiltContent {
            PutioTheme {
                MobileFilesScreen(
                    state = failedFilesState(failure),
                    onEvent = {},
                    onPlayMedia = {},
                    pinned = if (tick >= 0) emptyList() else emptyList(),
                )
            }
        }

        compose.onNodeWithText("Error id: $ERROR_ID", substring = true).assertIsDisplayed()
        assertEquals(listOf("unexpected"), RecordingDiagnostics.captured.map { it.code })
        assertEquals("unexpected", RecordingDiagnostics.captured.single().tags["failure_kind"])

        compose.runOnIdle { tick += 1 }
        compose.waitForIdle()

        assertEquals(1, RecordingDiagnostics.captured.size)
    }

    @Test
    fun filesInvalidResponseCapturesOnceAndShowsEventId() {
        val failure = PutioFailure.InvalidResponse(PutioConfigurationException("bad schema"))
        RecordingDiagnostics.nextId = ERROR_ID
        compose.setBuiltContent {
            PutioTheme {
                MobileFilesScreen(
                    state = failedFilesState(failure),
                    onEvent = {},
                    onPlayMedia = {},
                )
            }
        }

        compose.onNodeWithText("Error id: $ERROR_ID", substring = true).assertIsDisplayed()
        assertEquals(listOf("invalid_response"), RecordingDiagnostics.captured.map { it.code })
        assertEquals("invalid_response", RecordingDiagnostics.captured.single().tags["failure_kind"])
    }

    @Test
    fun filesNetworkFailureDoesNotCaptureOrShowEventId() {
        val failure = PutioFailure.NetworkUnavailable(IllegalStateException("offline"))
        RecordingDiagnostics.nextId = ERROR_ID
        compose.setBuiltContent {
            PutioTheme {
                MobileFilesScreen(
                    state = failedFilesState(failure),
                    onEvent = {},
                    onPlayMedia = {},
                )
            }
        }

        compose.onNodeWithText("Error id:", substring = true).assertDoesNotExist()
        assertTrue(RecordingDiagnostics.captured.isEmpty())
    }

    @Test
    fun accountSettingsAndAppConfigLoadFailuresCaptureAndShowEventId() {
        val accountFailure = PutioFailure.Unexpected(IllegalStateException("account boom"))
        val appConfigFailure = PutioFailure.InvalidResponse(PutioConfigurationException("config schema"))
        val secondId = "11111111111111111111111111111111"
        RecordingDiagnostics.nextIds += listOf(ERROR_ID, secondId)
        compose.setBuiltContent {
            PutioTheme {
                MobileAccountSettingsError(AccountSettingsFailure.Putio(accountFailure), onRetry = {})
                MobileAppConfigLoadError(appConfigFailure, onRetry = {})
            }
        }

        compose.onNodeWithText("Error id: $ERROR_ID", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Error id: $secondId", substring = true).assertIsDisplayed()
        assertEquals(listOf("unexpected", "invalid_response"), RecordingDiagnostics.captured.map { it.code })
    }

    @Test
    fun publicLinksFailureCapturesOnceAndShowsEventId() {
        val failure = PutioFailure.InvalidResponse(PutioConfigurationException("links schema"))
        RecordingDiagnostics.nextId = ERROR_ID
        compose.setBuiltContent {
            PutioTheme {
                MobilePublicLinksScreen(
                    MobilePublicLinks(PublicLinksState(content = PublicLinksContent.Failed(failure))) { true },
                )
            }
        }

        compose.onNodeWithText("Error id: $ERROR_ID", substring = true).assertIsDisplayed()
        assertEquals(listOf("invalid_response"), RecordingDiagnostics.captured.map { it.code })
    }

    @Test
    fun filesNoticeCapturesUnexpectedOnceAndIncludesEventId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failure = PutioFailure.Unexpected(IllegalStateException("delete failed"))
        RecordingDiagnostics.nextId = ERROR_ID
        val notice = MobileFilesNotice(R.string.mobile_files_mark_watched_failed, "clip.mkv", failure)

        val firstText = notice.text(context)
        val secondText = notice.text(context)

        assertTrue(firstText.contains("Error id: $ERROR_ID"))
        assertEquals(firstText, secondText)
        assertEquals(listOf("unexpected"), RecordingDiagnostics.captured.map { it.code })
    }

    @Test
    fun diagnosticsOffKeepsFallbackCopyWithoutEventId() {
        val failure = PutioFailure.Unexpected(IllegalStateException("diagnostics off"))
        RecordingDiagnostics.nextId = null
        compose.setBuiltContent {
            PutioTheme {
                MobileFilesScreen(
                    state = failedFilesState(failure),
                    onEvent = {},
                    onPlayMedia = {},
                )
            }
        }

        compose.onNodeWithText("put.io is temporarily unavailable. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Error id:", substring = true).assertDoesNotExist()
        assertEquals(1, RecordingDiagnostics.captured.size)
    }

    @Test
    fun playbackFailureRespectsRecordedPlayerFaultAndCapturesResolutionUnknown() {
        val audioFault = PutioFailure.Unexpected(
            PlaybackException("audio", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED),
        )
        val videoFault = PutioFailure.Unexpected(
            PlaybackException("video", null, PlaybackException.ERROR_CODE_DECODING_FAILED),
        )
        val resolutionFailure = PutioFailure.InvalidResponse(PutioConfigurationException("resolve schema"))
        val recordedVideoId = "22222222222222222222222222222222"
        PutioFailureReports.record(videoFault, recordedVideoId)
        RecordingDiagnostics.nextId = ERROR_ID

        compose.setBuiltContent {
            PutioTheme {
                MobilePlaybackFailureState(
                    title = "Couldn’t play this audio",
                    failure = PlaybackFailure.Putio(audioFault),
                    onRetry = {},
                    video = false,
                )
                MobilePlaybackFailureState(
                    title = "Couldn’t play this video",
                    failure = PlaybackFailure.Putio(videoFault),
                    onRetry = {},
                    video = true,
                )
                MobilePlaybackFailureState(
                    title = "Couldn’t play this video",
                    failure = PlaybackFailure.Putio(resolutionFailure),
                    onRetry = {},
                    video = true,
                )
            }
        }

        compose.onNodeWithText("Error id: $recordedVideoId", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Error id: $ERROR_ID", substring = true).assertIsDisplayed()
        assertFalse(RecordingDiagnostics.captured.any { it.code == "unexpected" })
        assertEquals(listOf("invalid_response"), RecordingDiagnostics.captured.map { it.code })
    }

    @Test
    fun filesAndTransfersInlineBannersCaptureUnknownAndShowEventId() {
        val opId = ERROR_ID
        val fPageId = "11111111111111111111111111111111"
        val refreshId = "22222222222222222222222222222222"
        val tPageId = "33333333333333333333333333333333"
        RecordingDiagnostics.nextIds += listOf(opId, fPageId, refreshId, tPageId)
        val filesState = filesBrowserState(
            stack = listOf(
                FilesFolderState(
                    folder = FilesFolder.Root,
                    content = FilesContent.Empty(
                        FilesPaging.Failed(FilesCursor("f"), PutioFailure.Unexpected(IllegalStateException("fp"))),
                    ),
                    operation = FilesFolderOperation.Failed(
                        PutioFailure.Unexpected(IllegalStateException("fo")),
                        FilesFolderOperationIntent.Refresh,
                        FilesFolderOperationPhase.RELOADING,
                    ),
                ),
            ),
            nextRequestValue = 2L,
        )
        val xferState = transfersState(
            content = TransfersContent.Ready(
                listOf(transferRow(id = 1L, status = AppTransferStatus.Downloading)),
                TransfersPaging.Failed(TransferCursor("t"), PutioFailure.Unexpected(IllegalStateException("tp"))),
            ),
            refresh = TransfersRefresh.Failed(PutioFailure.InvalidResponse(PutioConfigurationException("tr"))),
        )
        var showTransfers by mutableStateOf(false)
        compose.setBuiltContent {
            PutioTheme {
                if (!showTransfers) MobileFilesScreen(filesState, {}, {}) else MobileTransfersScreen(xferState, {})
            }
        }
        compose.onNodeWithText("Error id: $opId", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Error id: $fPageId", substring = true).assertIsDisplayed()
        compose.runOnIdle { showTransfers = true }
        compose.onNodeWithText("Error id: $refreshId", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(MOBILE_TRANSFERS_LIST_TAG)
            .performScrollToNode(hasText("Error id: $tPageId", substring = true))
        compose.onNodeWithText("Error id: $tPageId", substring = true).assertIsDisplayed()
        assertEquals(
            listOf("unexpected", "unexpected", "invalid_response", "unexpected"),
            RecordingDiagnostics.captured.map { it.code },
        )
    }

    @Implements(PutioDiagnostics::class)
    class RecordingDiagnostics {
        @Suppress("UnusedParameter")
        @Implementation
        fun capture(failure: TelemetryFailure): String? {
            captured += failure
            return if (nextIds.isNotEmpty()) nextIds.removeFirst() else nextId
        }

        companion object {
            val captured = mutableListOf<TelemetryFailure>()
            val nextIds = ArrayDeque<String?>()
            var nextId: String? = null

            fun reset() {
                captured.clear()
                nextIds.clear()
                nextId = null
            }
        }
    }

    private companion object {
        const val ERROR_ID = "0f8fad5bd9cb469fa165708677289ba1"
    }
}

private fun failedFilesState(failure: PutioFailure): FilesBrowserState =
    filesBrowserState(
        stack = listOf(
            FilesFolderState(
                folder = FilesFolder.Root,
                content = FilesContent.Failed(failure),
            ),
        ),
        nextRequestValue = 2L,
    )
