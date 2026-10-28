package io.putdotio.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsAnalyticsTest {
    private val change = AccountSettingsChange.Toggle(AccountSettingsKey.History, enabled = true)
    private val saving = SaveStep.Saving(1L, change, saving = true)

    @Test
    fun aSaveCountsOnceItLeavesItsSaveStepWithoutFailingThere() {
        assertEquals(change, SaveStep.Saving(1L, change, saving = false).savedAfter(saving))
        assertEquals(change, SaveStep.Failed(refreshing = true).savedAfter(saving))
        assertEquals(change, SaveStep.Idle.savedAfter(saving))
        assertNull(SaveStep.Failed(refreshing = false).savedAfter(saving))
    }

    @Test
    fun theSameChangeSavingAgainIsARetryOfAMissedFailure() {
        assertNull(SaveStep.Saving(2L, change, saving = true).savedAfter(saving))
        val other = AccountSettingsChange.Toggle(AccountSettingsKey.History, enabled = false)
        assertEquals(change, SaveStep.Saving(2L, other, saving = true).savedAfter(saving))
    }
}
