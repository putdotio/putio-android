package io.putdotio.android

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.auth.MobileAuthSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileTransferDraftTest {
    @Test
    fun authPendingShareSurvivesUntilFirstSignInThenClearsOnSessionExit() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer(First))
        draft.reconcileSession(null)
        draft.reconcileSession(null)
        assertEquals(First, draft.state.value.input)
        draft.reconcileSession(MobileAuthSessionId(1))
        assertEquals(First, draft.state.value.input)
        draft.edit(Edited)
        draft.reconcileSession(MobileAuthSessionId(1))
        assertEquals(Edited, draft.state.value.input)
        draft.reconcileSession(null)
        assertEquals(MobileTransferDraftState(), draft.state.value)
        draft.reconcileSession(MobileAuthSessionId(2))
        assertEquals("", draft.state.value.input)
    }

    @Test
    fun changingAccountsClearsBothTheDraftAndPendingReplacement() {
        val draft = MobileTransferDraft()
        draft.reconcileSession(MobileAuthSessionId(1))
        draft.receive(parseMobileSharedTransfer(First))
        draft.receive(parseMobileSharedTransfer(Second))
        assertTrue(draft.state.value.pendingReplacement)
        draft.reconcileSession(MobileAuthSessionId(2))
        draft.useSharedLink()
        assertEquals(MobileTransferDraftState(), draft.state.value)
    }

    @Test
    fun aNewSharePreservesAnEditedDraftUntilTheUserChoosesReplacement() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer(First))
        draft.edit(Edited)
        draft.receive(parseMobileSharedTransfer(Second))
        assertEquals(Edited, draft.state.value.input)
        assertTrue(draft.state.value.pendingReplacement)
        draft.keepDraft()
        assertEquals(Edited, draft.state.value.input)
        assertFalse(draft.state.value.pendingReplacement)
        draft.receive(parseMobileSharedTransfer(First))
        draft.receive(parseMobileSharedTransfer(Second))
        draft.useSharedLink()
        assertEquals(Second, draft.state.value.input)
        assertFalse(draft.state.value.pendingReplacement)
        assertTrue(draft.state.value.open)
    }

    @Test
    fun openEmptyDraftIsNotReplacedAndNavigationAcknowledgementCannotConsumeANewerShare() {
        val draft = MobileTransferDraft()
        draft.open()
        draft.receive(parseMobileSharedTransfer(First))
        val firstRequest = requireNotNull(draft.state.value.incomingRequestId)
        assertEquals("", draft.state.value.input)
        assertTrue(draft.state.value.pendingReplacement)
        draft.receive(parseMobileSharedTransfer(Second))
        val secondRequest = requireNotNull(draft.state.value.incomingRequestId)
        draft.acknowledgeNavigation(firstRequest)
        assertEquals(secondRequest, draft.state.value.incomingRequestId)
        draft.acknowledgeNavigation(secondRequest)
        assertNull(draft.state.value.incomingRequestId)
        draft.useSharedLink()
        assertEquals(Second, draft.state.value.input)
    }

    @Test
    fun validationRequiresOneCompleteLinkAndEditsClearTheShareError() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer("$First and $Second"))
        assertNull(draft.validate())
        assertEquals(MobileShareValidation.MultipleLinks, draft.state.value.validation)
        draft.edit("not a link")
        assertNull(draft.state.value.validation)
        assertNull(draft.validate())
        assertEquals(MobileShareValidation.InvalidLink, draft.state.value.validation)
        draft.edit("  $Edited  ")
        assertEquals(Edited, draft.validate())
        assertEquals(Edited, draft.state.value.input)
        assertNull(draft.state.value.validation)
        assertTrue(draft.state.value.open)
    }

    @Test
    fun runningAddCannotBeEditedDismissedReplacedOrSubmittedTwice() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer(First))
        assertEquals(First, draft.validate())
        draft.setSubmitting(true)
        draft.edit(Edited)
        draft.dismiss()
        draft.receive(parseMobileSharedTransfer(Second))
        draft.useSharedLink()
        assertNull(draft.validate())
        assertEquals(First, draft.state.value.input)
        assertTrue(draft.state.value.open)
        assertTrue(draft.state.value.pendingReplacement)
        draft.submissionSucceeded()
        assertEquals(Second, draft.state.value.input)
        assertTrue(draft.state.value.open)
        assertFalse(draft.state.value.submitting)
        assertFalse(draft.state.value.pendingReplacement)
    }

    @Test
    fun rejectionPreservesTheEditedValueAndSuccessClearsTheFinishedDraft() {
        val draft = MobileTransferDraft()
        draft.receive(parseMobileSharedTransfer(First))
        draft.edit(Edited)
        draft.setSubmitting(true)
        draft.restoreRejectedInput(First)
        assertEquals(Edited, draft.state.value.input)
        assertFalse(draft.state.value.submitting)
        draft.dismiss()
        assertFalse(draft.state.value.open)
        draft.open()
        assertEquals(Edited, draft.state.value.input)
        draft.submissionSucceeded()
        assertEquals(MobileTransferDraftState(), draft.state.value)
    }

    @Test
    fun oversizedEditsKeepTheOriginalAndNeverCreateATruncatedSubmission() {
        val draft = MobileTransferDraft()
        draft.edit(First)
        draft.edit("https://example.invalid/" + "a".repeat(MOBILE_TRANSFER_INPUT_LIMIT))
        assertEquals(First, draft.state.value.input)
        assertEquals(MobileShareValidation.TooLong, draft.state.value.validation)
        assertNull(draft.validate())
        draft.edit(Edited)
        assertEquals(Edited, draft.validate())
        draft.edit("https://example.invalid/" + "東".repeat(MOBILE_TRANSFER_INPUT_LIMIT / 2))
        assertEquals(Edited, draft.state.value.input)
        assertNull(draft.validate())
    }

    @Test
    fun viewModelRetentionUsesMemoryAndANewOwnerStartsEmpty() {
        val store = ViewModelStore()
        try {
            val provider = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
            val first = provider[MobileTransferDraft::class.java]
            first.receive(parseMobileSharedTransfer(First))
            first.edit(Edited)
            val recreatedProvider = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
            val retained = recreatedProvider[MobileTransferDraft::class.java]
            assertSame(first, retained)
            assertEquals(Edited, retained.state.value.input)
            assertFalse(retained.state.value.toString().contains("private-marker"))
            val fresh = MobileTransferDraft()
            assertNotSame(retained, fresh)
            assertEquals(MobileTransferDraftState(), fresh.state.value)
        } finally {
            store.clear()
        }
    }
}

private const val First = "https://example.invalid/first?token=private-marker"
private const val Second = "magnet:?xt=urn:btih:12345"
private const val Edited = "https://example.invalid/edited?token=private-marker"
