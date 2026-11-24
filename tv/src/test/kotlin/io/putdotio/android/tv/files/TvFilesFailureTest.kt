package io.putdotio.android.tv.files

import io.putdotio.android.tv.setTvContent
import androidx.compose.runtime.CompositionLocalProvider
import io.putdotio.android.tv.LocalTvOffline
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioFailure
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.files.FilesFolderOperation
import io.putdotio.android.files.FilesFolderOperationIntent
import io.putdotio.android.files.FilesFolderOperationPhase
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesRequestId
import io.putdotio.android.putioErrorBody
import io.putdotio.android.putioRefusal
import io.putdotio.android.telemetry.PutioDiagnostics
import io.putdotio.android.telemetry.PutioFailureReports
import io.putdotio.android.telemetry.TelemetryFailure
import io.putdotio.android.toPutioFailure
import io.putdotio.android.tv.pressCenter
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    qualifiers = "en-rUS-w960dp-h540dp-television",
    instrumentedPackages = ["io.putdotio.android.telemetry"],
    shadows = [TvFilesFailureTest.RecordingDiagnostics::class],
)
class TvFilesFailureTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before
    @After
    fun resetDiagnostics() {
        RecordingDiagnostics.reset()
    }

    @Test
    fun aServerErrorOnAFolderOffersTryAgain() {
        val events = mutableListOf<FilesBrowserEvent>()
        val failure = PutioFailure.ServerUnavailable(503, PutioConfigurationException("x"))
        compose.showFiles(state = { state(FilesContent.Failed(failure)) }, onEvent = { events += it; true })
        compose.onNodeWithText("Something went wrong on our end").assertIsDisplayed()
        compose.onNodeWithText("Try again in a few minutes, then contact support.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)
    }

    @Test
    fun anUnknownFailureShowsItsErrorIdOnlyWhenOneWasReported() {
        val reported = PutioFailure.Unexpected(IllegalStateException("reported"))
        PutioFailureReports.record(reported, ERROR_ID)
        var failure by mutableStateOf<PutioFailure>(reported)
        compose.showFiles(state = { state(FilesContent.Failed(failure)) })
        compose.onNodeWithText("Something unexpected happened").assertIsDisplayed()
        compose.onNodeWithText("Error id: $ERROR_ID").assertIsDisplayed()

        compose.runOnIdle {
            failure = PutioFailure.InvalidResponse(PutioConfigurationException("diagnostics off"))
        }
        compose.onNodeWithText("Something unexpected happened").assertIsDisplayed()
        compose.onNodeWithText("Error id:", substring = true).assertDoesNotExist()
    }

    @Test
    fun aMissingFolderSaysTheFileIsNotFound() {
        val failure = putioRefusal(404, putioErrorBody(404, "NOT_FOUND")).toPutioFailure()
        compose.showFiles(state = { state(FilesContent.Failed(failure)) })
        compose.onNodeWithText("File not found").assertIsDisplayed()
        compose.onNodeWithText("It may have been moved or deleted.").assertIsDisplayed()
    }

    @Test
    fun aMissingFolderWithPutiosProseReasonKeepsTheAppsNotFoundCopy() {
        val failure = putioRefusal(404, putioErrorBody(404, "File not found.")).toPutioFailure()
        compose.showFiles(state = { state(FilesContent.Failed(failure)) })
        compose.onNodeWithText("File not found").assertIsDisplayed()
        compose.onNodeWithText("It may have been moved or deleted.").assertIsDisplayed()
        compose.onNodeWithText("File not found.").assertDoesNotExist()
    }

    @Test
    fun anOfflineFolderOffersTryNowAndNoSort() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(state = { state(FilesContent.Failed(networkFailure())) }, onEvent = { events += it; true })
        compose.onNodeWithText("Can’t connect to put.io").assertIsDisplayed()
        compose.onNodeWithText("Check your internet connection.").assertIsDisplayed()
        compose.onNodeWithText("Try now").assertIsFocused().performKeyInput { pressCenter() }
        compose.onAllNodesWithText("Sort").assertCountEquals(0)
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.Retry), events)
    }

    @Test
    fun aFailedRefreshShowsTheErrorWithRetryAndKeepsPagingEnabled() {
        val events = mutableListOf<FilesBrowserEvent>()
        compose.showFiles(
            state = {
                state(
                    FilesContent.Ready(
                        listOf(item(1, "a.txt", PutioFileType.TEXT)),
                        FilesPaging.Available(FilesCursor("c")),
                    ),
                    operation = FilesFolderOperation.Failed(
                        networkFailure(),
                        FilesFolderOperationIntent.Refresh,
                        FilesFolderOperationPhase.RELOADING,
                    ),
                )
            },
            onEvent = { events += it; true },
        )
        compose.onNodeWithText("Couldn’t refresh files.").assertIsDisplayed()
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.LoadNextPage), events)
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }
        assertEquals(listOf<FilesBrowserEvent>(FilesBrowserEvent.LoadNextPage, FilesBrowserEvent.Retry), events)
    }

    @Test
    fun aFailedRefreshWhileOfflineLeavesTheRowsToTheNoConnectionBanner() {
        val failedRefresh = state(
            FilesContent.Ready(listOf(item(1, "a.txt", PutioFileType.TEXT)), FilesPaging.Complete),
            operation = FilesFolderOperation.Failed(
                networkFailure(),
                FilesFolderOperationIntent.Refresh,
                FilesFolderOperationPhase.RELOADING,
            ),
        )
        compose.setTvContent {
            CompositionLocalProvider(LocalTvOffline provides true) {
                TvFilesScreen(state = failedRefresh, onEvent = { true }, onPlayMedia = {})
            }
        }
        compose.onNodeWithContentDescription("a.txt").assertIsDisplayed()
        compose.onAllNodesWithText("Couldn’t refresh files.").assertCountEquals(0)
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
    }

    @Test
    fun tryAgainOnAFailedRefreshHandsFocusBackToTheRows() {
        var operation by mutableStateOf<FilesFolderOperation>(
            FilesFolderOperation.Failed(
                networkFailure(),
                FilesFolderOperationIntent.Refresh,
                FilesFolderOperationPhase.RELOADING,
            ),
        )
        compose.showFiles(
            state = {
                state(
                    FilesContent.Ready(
                        listOf(item(1, "a.txt", PutioFileType.TEXT), item(2, "b.txt", PutioFileType.TEXT)),
                        FilesPaging.Complete,
                    ),
                    operation = operation,
                )
            },
        )
        compose.onNodeWithContentDescription("a.txt").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("b.txt").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithText("Try again").assertIsFocused().performKeyInput { pressCenter() }

        compose.runOnIdle {
            operation = FilesFolderOperation.Loading(
                FilesRequestId(3),
                FilesFolderOperationIntent.Refresh,
                FilesFolderOperationPhase.RELOADING,
            )
        }
        compose.onAllNodes(hasTestTag(TV_FILES_ROW_TAG) and isFocused()).assertCountEquals(1)
    }

    @Test
    fun unreportedUnknownFailuresCaptureOnceAndShowReturnedEventId() {
        val unexpected = PutioFailure.Unexpected(IllegalStateException("unreported"))
        val invalid = PutioFailure.InvalidResponse(PutioConfigurationException("bad schema"))
        val secondId = "11111111111111111111111111111111"
        RecordingDiagnostics.nextIds += listOf(ERROR_ID, secondId)
        var failure by mutableStateOf<PutioFailure>(unexpected)
        compose.showFiles(state = { state(FilesContent.Failed(failure)) })

        compose.onNodeWithText("Error id: $ERROR_ID").assertIsDisplayed()
        assertEquals(listOf("unexpected"), RecordingDiagnostics.captured.map { it.code })

        compose.runOnIdle { failure = invalid }
        compose.onNodeWithText("Error id: $secondId").assertIsDisplayed()
        assertEquals(listOf("unexpected", "invalid_response"), RecordingDiagnostics.captured.map { it.code })
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
