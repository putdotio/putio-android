package io.putdotio.android

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobilePlaybackViewModelTest {
    @Test
    fun advancedTargetAndVisitedFilesSurviveActivityRecreation() {
        val repository = ControlledRepository()
        val activityController = Robolectric.buildActivity(PlaybackHostActivity::class.java).setup()

        try {
            val beforeActivity = activityController.get()
            val beforeViewModel = beforeActivity.playbackViewModel(repository)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(beforeViewModel.controller.dispatch(PlaybackEvent.PlayerEnded))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(repository.findNextStarted.isCompleted)

            activityController.recreate()

            val afterActivity = activityController.get()
            val afterViewModel = afterActivity.playbackViewModel(repository)
            repository.nextResult.complete(PlaybackNextResult.Found(NextTarget))
            shadowOf(Looper.getMainLooper()).idle()
            val state = afterViewModel.controller.state.value
            assertNotSame(beforeActivity, afterActivity)
            assertSame(beforeViewModel, afterViewModel)
            assertSame(beforeViewModel.controller, afterViewModel.controller)
            assertEquals(NextTarget, state.target)
            assertEquals(setOf(InitialTarget.fileId, NextTarget.fileId), state.visitedFileIds)
            assertEquals(4L, state.nextRequestValue)
            assertTrue(state.content is PlaybackContent.Ready)
            assertEquals(listOf(InitialTarget, NextTarget), repository.resolvedTargets)
        } finally {
            activityController.close()
        }
    }

    @Test
    fun permanentOwnerDestructionCancelsPendingPlaybackWork() {
        val repository = ControlledRepository()
        val activityController = Robolectric.buildActivity(PlaybackHostActivity::class.java).setup()
        val viewModel = activityController.get().playbackViewModel(repository)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.controller.dispatch(PlaybackEvent.PlayerEnded))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(repository.findNextStarted.isCompleted)

        activityController.close()
        repository.nextResult.complete(PlaybackNextResult.Found(NextTarget))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(InitialTarget), repository.resolvedTargets)
        assertEquals(InitialTarget, viewModel.controller.state.value.target)
        assertTrue(viewModel.controller.state.value.content is PlaybackContent.FindingNext)
        assertFalse(viewModel.controller.dispatch(PlaybackEvent.Retry))
    }

    private fun PlaybackHostActivity.playbackViewModel(
        repository: PlaybackRepository,
    ): MobilePlaybackViewModel =
        ViewModelProvider(
            this,
            mobilePlaybackViewModelFactory(InitialTarget, repository),
        )[MobilePlaybackViewModel::class.java]

    class PlaybackHostActivity : ComponentActivity()

    private class ControlledRepository : PlaybackRepository {
        val findNextStarted = CompletableDeferred<Unit>()
        val nextResult = CompletableDeferred<PlaybackNextResult>()
        val resolvedTargets = mutableListOf<PlaybackTarget>()

        override suspend fun resolve(
            target: PlaybackTarget,
        ): PlaybackRepositoryResult<PlaybackResolution> {
            resolvedTargets += target
            return PlaybackRepositoryResult.Success(
                PlaybackResolution.Ready(
                    PlaybackSource(
                        fileId = target.fileId.value,
                        kind = PlaybackSourceKind.MP4,
                        url = credentialUrl("https://example.com/${target.fileId.value}.mp4"),
                        startFromSeconds = 0.0,
                        subtitles = PlaybackSubtitles.None,
                    ),
                ),
            )
        }

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult {
            findNextStarted.complete(Unit)
            return nextResult.await()
        }
    }

    private companion object {
        val InitialTarget = PlaybackTarget(FilesItemId(42L), "first.mkv")
        val NextTarget = PlaybackTarget(FilesItemId(43L), "second.mkv")

        fun credentialUrl(value: String): PutioCredentialUrl =
            PutioCredentialUrl::class.java
                .getDeclaredConstructor(String::class.java)
                .newInstance(value)
    }
}
