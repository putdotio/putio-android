package io.putdotio.android

import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
class MobilePlaybackRetentionTest {
    @Test
    fun autoplayRequiresAResumedLifecycle() {
        assertFalse(lifecycleAllowsAutoplay(Lifecycle.State.CREATED))
        assertFalse(lifecycleAllowsAutoplay(Lifecycle.State.STARTED))
        assertTrue(lifecycleAllowsAutoplay(Lifecycle.State.RESUMED))
        assertFalse(
            lifecycleAllowsAutoplay(
                state = Lifecycle.State.RESUMED,
                resumeAfterLifecyclePause = false,
            ),
        )
    }

    @Test
    fun activePlaybackKeepsTheScreenAwake() {
        assertTrue(playbackKeepsScreenOn(playWhenReady = true, Media3Player.STATE_BUFFERING))
        assertTrue(playbackKeepsScreenOn(playWhenReady = true, Media3Player.STATE_READY))
        assertFalse(playbackKeepsScreenOn(playWhenReady = false, Media3Player.STATE_READY))
        assertFalse(playbackKeepsScreenOn(playWhenReady = true, Media3Player.STATE_IDLE))
        assertFalse(playbackKeepsScreenOn(playWhenReady = true, Media3Player.STATE_ENDED))
        assertFalse(
            playbackKeepsScreenOn(
                playWhenReady = true,
                playbackState = Media3Player.STATE_READY,
                playbackSuppressionReason = Media3Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
            ),
        )
    }

    @Test
    fun lifecyclePauseRetainsPositionAndManualPauseIntent() {
        assertEquals(
            RetainedPlayback(positionMillis = 12_345L, resumeAfterLifecyclePause = true),
            retainPlaybackOnPause(positionMillis = 12_345L, playWhenReady = true),
        )
        assertEquals(
            RetainedPlayback(positionMillis = 54_321L, resumeAfterLifecyclePause = false),
            retainPlaybackOnPause(positionMillis = 54_321L, playWhenReady = false),
        )
    }

    @Test
    fun playerEventsPreserveBackgroundPauseIntent() {
        assertEquals(
            PlayerRetentionUpdate.Playback(
                RetainedPlayback(positionMillis = 54_321L, resumeAfterLifecyclePause = true),
            ),
            playerRetentionUpdate(
                event = PlayerRetentionEvent.PlayerError,
                lifecycleState = Lifecycle.State.RESUMED,
                positionMillis = 54_321L,
                playWhenReady = true,
            ),
        )
        assertEquals(
            PlayerRetentionUpdate.Position(54_321L),
            playerRetentionUpdate(
                event = PlayerRetentionEvent.PlayerError,
                lifecycleState = Lifecycle.State.CREATED,
                positionMillis = 54_321L,
                playWhenReady = false,
            ),
        )
        assertEquals(
            PlayerRetentionUpdate.Playback(
                RetainedPlayback(positionMillis = 54_321L, resumeAfterLifecyclePause = true),
            ),
            playerRetentionUpdate(
                event = PlayerRetentionEvent.LifecyclePause,
                lifecycleState = Lifecycle.State.CREATED,
                positionMillis = 54_321L,
                playWhenReady = true,
            ),
        )
    }

    @Test
    fun sourceReplacementUsesLivePositionOnlyForTheActiveFile() {
        assertEquals(
            54_321L,
            replacementPositionMillis(
                activeFileId = 42L,
                replacementFileId = 42L,
                livePositionMillis = 54_321L,
                preparedPositionMillis = 12_345L,
            ),
        )
        assertEquals(
            12_345L,
            replacementPositionMillis(
                activeFileId = 7L,
                replacementFileId = 42L,
                livePositionMillis = 54_321L,
                preparedPositionMillis = 12_345L,
            ),
        )
    }

    @Test
    fun errorPositionSurvivesTheFollowingPlayerDisposal() {
        assertEquals(
            54_321L,
            retainedPositionOnDispose(
                failurePositionMillis = 54_321L,
                livePositionMillis = 12_345L,
            ),
        )
        assertEquals(
            12_345L,
            retainedPositionOnDispose(
                failurePositionMillis = null,
                livePositionMillis = 12_345L,
            ),
        )
    }
}
