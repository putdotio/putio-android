package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PlaybackConversionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackConversionReducerTest {
    @Test
    fun aCompletedConversionResolvesOnceMoreThenWaitsForTheViewer() {
        val completed = PlaybackReducer.reduce(
            conversion(PlaybackConversionState.Converting(99.0)).let {
                PlaybackReducer.reduce(it, PlaybackEvent.RefreshConversion).state
            },
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(2L),
                PlaybackResolution.Conversion(PlaybackConversionState.Completed),
            ),
        )
        assertEquals(
            PlaybackContent.Conversion(PlaybackConversionState.Completed, PlaybackRequestId(3L)),
            completed.state.content,
        )
        assertEquals(PlaybackEffect.Resolve(Target, PlaybackRequestId(3L)), completed.effect)

        val stillCompleted = PlaybackReducer.reduce(
            completed.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(3L),
                PlaybackResolution.Conversion(PlaybackConversionState.Completed),
            ),
        )
        assertEquals(PlaybackContent.Conversion(PlaybackConversionState.Completed), stillCompleted.state.content)
        assertNull("No refresh loop", stillCompleted.effect)
    }

    @Test
    fun onlyAFailedConversionCanBeStartedAgain() {
        assertFalse(
            PlaybackReducer.reduce(conversion(PlaybackConversionState.Queued), PlaybackEvent.StartConversion).consumed,
        )

        val started = PlaybackReducer.reduce(conversion(PlaybackConversionState.Failed), PlaybackEvent.StartConversion)
        assertEquals(PlaybackEffect.StartConversion(Target, PlaybackRequestId(2L)), started.effect)
        assertEquals(
            PlaybackContent.Conversion(PlaybackConversionState.Failed, PlaybackRequestId(2L)),
            started.state.content,
        )
        val queued = PlaybackReducer.reduce(
            started.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(2L),
                PlaybackResolution.Conversion(PlaybackConversionState.Queued),
            ),
        )
        assertEquals(
            PlaybackContent.Conversion(PlaybackConversionState.Queued),
            queued.state.content,
        )
    }

    @Test
    fun openingAVideoWithNoConversionRequestedStartsOneOnceWithoutATap() {
        val opened = PlaybackReducer.reduce(
            PlaybackReducer.start(Target).state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(1L),
                PlaybackResolution.Conversion(PlaybackConversionState.NotAvailable),
            ),
        )
        assertEquals(PlaybackEffect.StartConversion(Target, PlaybackRequestId(2L)), opened.effect)
        val starting = opened.state.content as PlaybackContent.Conversion
        assertEquals(
            PlaybackContent.Conversion(PlaybackConversionState.NotAvailable, PlaybackRequestId(2L)),
            starting,
        )
        // The status read before the start is not a verdict: nothing to tap while it starts.
        assertTrue(starting.starting)
        assertNull(starting.action)
        assertFalse(PlaybackReducer.reduce(opened.state, PlaybackEvent.StartConversion).consumed)

        val queued = PlaybackReducer.reduce(
            opened.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(2L),
                PlaybackResolution.Conversion(PlaybackConversionState.Queued),
            ),
        )
        assertEquals(
            PlaybackContent.Conversion(PlaybackConversionState.Queued),
            queued.state.content,
        )
        assertNull(queued.effect)

        // A poll that still reads not available after the start does not start another one.
        val polled = PlaybackReducer.reduce(
            PlaybackReducer.reduce(queued.state, PlaybackEvent.RefreshConversion).state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(3L),
                PlaybackResolution.Conversion(PlaybackConversionState.NotAvailable),
            ),
        )
        assertNull("One start per opening", polled.effect)

        // The server still has no conversion after the app asked for one: it cannot be converted.
        val refused = PlaybackReducer.reduce(
            opened.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(2L),
                PlaybackResolution.Conversion(PlaybackConversionState.NotAvailable),
            ),
        )
        assertNull(refused.effect)
        val final = refused.state.content as PlaybackContent.Conversion
        assertFalse(final.starting)
        assertFalse(final.startable)
        assertNull(final.action)
        assertFalse(PlaybackReducer.reduce(refused.state, PlaybackEvent.StartConversion).consumed)
    }

    @Test
    fun reopeningAQueuedRunningOrFailedConversionOnlyReadsIt() {
        listOf(
            PlaybackConversionState.Queued,
            PlaybackConversionState.Converting(35.0),
            PlaybackConversionState.Completed,
            PlaybackConversionState.Failed,
            PlaybackConversionState.Unknown("PAUSED", null),
        ).forEach { state ->
            val reopened = PlaybackReducer.reduce(
                PlaybackReducer.start(Target).state,
                PlaybackEvent.ResolveSucceeded(PlaybackRequestId(1L), PlaybackResolution.Conversion(state)),
            )
            assertFalse("$state starts no conversion", reopened.effect is PlaybackEffect.StartConversion)
        }
        assertEquals(
            PlaybackConversionAction.ConvertAgain,
            (conversion(PlaybackConversionState.Failed).content as PlaybackContent.Conversion).action,
        )
        assertEquals(
            PlaybackConversionAction.CheckAgain,
            (
                conversion(PlaybackConversionState.Unknown("PAUSED", null)).content as PlaybackContent.Conversion
                ).action,
        )
    }

    @Test
    fun aLaterReadOfNotAvailableNeverStartsAConversion() {
        // Opened while queued, then a poll reads not available.
        val queued = conversion(PlaybackConversionState.Queued)
        val polled = PlaybackReducer.reduce(
            PlaybackReducer.reduce(queued, PlaybackEvent.RefreshConversion).state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(2L),
                PlaybackResolution.Conversion(PlaybackConversionState.NotAvailable),
            ),
        )
        assertNull("A poll starts nothing", polled.effect)
        val final = polled.state.content as PlaybackContent.Conversion
        assertFalse(final.starting)
        assertNull(final.action)

        // Opened with an unknown status, then Check again reads not available.
        val unknown = conversion(PlaybackConversionState.Unknown("PAUSED", null))
        val checked = PlaybackReducer.reduce(
            PlaybackReducer.reduce(unknown, PlaybackEvent.RefreshConversion).state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(2L),
                PlaybackResolution.Conversion(PlaybackConversionState.NotAvailable),
            ),
        )
        assertNull("Check again starts nothing", checked.effect)
    }

    @Test
    fun aRetryAfterAFailedReadDoesNotStartTheConversionAgain() {
        val opened = PlaybackReducer.reduce(
            PlaybackReducer.start(Target).state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(1L),
                PlaybackResolution.Conversion(PlaybackConversionState.NotAvailable),
            ),
        )
        assertEquals(PlaybackEffect.StartConversion(Target, PlaybackRequestId(2L)), opened.effect)
        val failed = PlaybackReducer.reduce(
            opened.state,
            PlaybackEvent.ResolveFailed(
                PlaybackRequestId(2L),
                PlaybackFailure.Unexpected(IllegalStateException("offline")),
            ),
        )
        val retried = PlaybackReducer.reduce(failed.state, PlaybackEvent.Retry)
        assertEquals(PlaybackEffect.Resolve(Target, PlaybackRequestId(3L)), retried.effect)
        val reread = PlaybackReducer.reduce(
            retried.state,
            PlaybackEvent.ResolveSucceeded(
                PlaybackRequestId(3L),
                PlaybackResolution.Conversion(PlaybackConversionState.NotAvailable),
            ),
        )
        assertNull("One start per opening", reread.effect)
        assertFalse((reread.state.content as PlaybackContent.Conversion).starting)
    }

    @Test
    fun onlyQueuedAndRunningConversionsPollOnTheirOwn() {
        assertTrue(PlaybackConversionState.Queued.pollsAutomatically)
        assertTrue(PlaybackConversionState.Converting(null).pollsAutomatically)
        assertFalse(PlaybackConversionState.Completed.pollsAutomatically)
        assertFalse(PlaybackConversionState.Failed.pollsAutomatically)
        assertFalse(PlaybackConversionState.NotAvailable.pollsAutomatically)
        assertFalse(PlaybackConversionState.Unknown("PAUSED", null).pollsAutomatically)
    }

    private fun conversion(state: PlaybackConversionState): PlaybackState =
        PlaybackReducer.reduce(
            PlaybackReducer.start(Target).state,
            PlaybackEvent.ResolveSucceeded(PlaybackRequestId(1L), PlaybackResolution.Conversion(state)),
        ).state

    private companion object {
        val Target = PlaybackTarget(FilesItemId(42L), "episode.mkv")
    }
}
