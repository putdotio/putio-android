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
import org.junit.Assert.assertEquals
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
        val repository = AdvancingRepository()
        val activityController = Robolectric.buildActivity(PlaybackHostActivity::class.java).setup()

        try {
            val beforeActivity = activityController.get()
            val beforeViewModel = beforeActivity.playbackViewModel(repository)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(beforeViewModel.controller.dispatch(PlaybackEvent.PlayerEnded))
            shadowOf(Looper.getMainLooper()).idle()

            activityController.recreate()

            val afterActivity = activityController.get()
            val afterViewModel = afterActivity.playbackViewModel(repository)
            val state = afterViewModel.controller.state.value
            assertNotSame(beforeActivity, afterActivity)
            assertSame(beforeViewModel, afterViewModel)
            assertSame(beforeViewModel.controller, afterViewModel.controller)
            assertEquals(NextTarget, state.target)
            assertEquals(setOf(InitialTarget.fileId, NextTarget.fileId), state.visitedFileIds)
            assertTrue(state.content is PlaybackContent.Ready)
        } finally {
            activityController.close()
        }
    }

    private fun PlaybackHostActivity.playbackViewModel(
        repository: PlaybackRepository,
    ): MobilePlaybackViewModel =
        ViewModelProvider(
            this,
            mobilePlaybackViewModelFactory(InitialTarget, repository),
        )[MobilePlaybackViewModel::class.java]

    class PlaybackHostActivity : ComponentActivity()

    private class AdvancingRepository : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
        ): PlaybackRepositoryResult<PlaybackResolution> =
            PlaybackRepositoryResult.Success(
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

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
            PlaybackNextResult.Found(NextTarget)
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
