package io.putdotio.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CastLoadTest {
    @Test
    fun aSessionThatEndsMidLoadLeavesTheNextSessionReadingReceiverReports() {
        val load = CastLoad()
        val request = load.start()

        load.reset()

        assertFalse(load.inFlight)
        assertFalse(load.settle(request, failure(7L)))
        assertEquals(CastLoadPhase.Idle, load.phase)
    }

    @Test
    fun onlyTheLatestLoadsResultSettlesIt() {
        val load = CastLoad()
        val first = load.start()
        val second = load.start()

        assertFalse(load.settle(first, failure(7L)))
        assertEquals(CastLoadPhase.InFlight(second), load.phase)
        assertEquals(true, load.settle(second, failure(8L)))
        assertEquals(8L, load.failure?.fileId)
    }

    private fun failure(fileId: Long) = CastRemoteStatus(
        fileId = fileId,
        state = CastRemoteState.Failed,
        positionMillis = 0L,
        durationMillis = null,
        sampledAtMillis = 0L,
    )
}
