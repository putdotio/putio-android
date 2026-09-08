package io.putdotio.android

import androidx.lifecycle.ViewModel
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.transfers.TransferAction
import io.putdotio.android.transfers.TransferMutation
import io.putdotio.android.transfers.TransfersRequestId
import io.putdotio.android.transfers.TransfersState
import io.putdotio.android.transfers.TransferSubmission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class MobileTransferDraftState(
    val input: String = "",
    val open: Boolean = false,
    val validation: MobileShareValidation? = null,
    val incomingRequestId: Long? = null,
    val pendingReplacement: Boolean = false,
    val submitting: Boolean = false,
) {
    override fun toString(): String =
        "MobileTransferDraftState(<redacted>, open=$open, validation=$validation, " +
            "incomingRequestId=$incomingRequestId, pendingReplacement=$pendingReplacement, submitting=$submitting)"
}

/** Activity-owned memory only: signed URLs must never enter a saved-state registry. */
class MobileTransferDraft : ViewModel() {
    private val mutableState = MutableStateFlow(MobileTransferDraftState())
    internal val state = mutableState.asStateFlow()
    private var boundSessionId: MobileAuthSessionId? = null
    private var nextRequestId = 1L
    private var replacement: MobileSharedTransfer? = null
    private var observedTransfers = false
    private var lastSuccessfulAdd: TransfersRequestId? = null
    private var lastMutation: TransferMutation = TransferMutation.Idle

    internal fun reconcileSession(sessionId: MobileAuthSessionId?) {
        if (boundSessionId != null && boundSessionId != sessionId) {
            clear()
            observedTransfers = false
            lastSuccessfulAdd = null
            lastMutation = TransferMutation.Idle
        }
        boundSessionId = sessionId
    }

    internal fun reconcileTransfers(state: TransfersState) {
        val success = state.lastSuccessfulAddRequestId
        if (observedTransfers && success != null && success != lastSuccessfulAdd) submissionSucceeded()
        observedTransfers = true
        lastSuccessfulAdd = success
        setSubmitting((state.mutation as? TransferMutation.Running)?.action is TransferAction.Add)
        if (state.mutation != lastMutation) {
            val action = (state.mutation as? TransferMutation.Failed)?.action
            if (action is TransferAction.Add) restoreRejectedInput(action.submission.value)
        }
        lastMutation = state.mutation
    }

    internal fun receive(shared: MobileSharedTransfer) {
        val current = mutableState.value
        val requestId = nextRequestId++
        if (current.submitting || current.open || current.input.isNotBlank()) {
            replacement = shared
            mutableState.value = current.copy(incomingRequestId = requestId, pendingReplacement = true)
        } else {
            present(shared, requestId)
        }
    }

    internal fun acknowledgeNavigation(requestId: Long) {
        if (mutableState.value.incomingRequestId == requestId) {
            mutableState.value = mutableState.value.copy(incomingRequestId = null)
        }
    }

    internal fun open() {
        mutableState.value = mutableState.value.copy(open = true)
    }

    internal fun edit(input: String) {
        val current = mutableState.value
        if (current.submitting) return
        mutableState.value = if (!input.fitsMobileTransferInputLimit()) {
            current.copy(validation = MobileShareValidation.TooLong)
        } else {
            current.copy(input = input, validation = null)
        }
    }

    internal fun dismiss() {
        if (mutableState.value.submitting) return
        mutableState.value = mutableState.value.copy(open = false, incomingRequestId = null)
    }

    internal fun validate(): String? {
        val current = mutableState.value
        if (current.submitting || current.validation == MobileShareValidation.TooLong) return null
        val valid = TransferSubmission.parse(current.input)?.value
        mutableState.value = current.copy(
            input = valid ?: current.input,
            validation = if (valid == null) current.validation ?: MobileShareValidation.InvalidLink else null,
        )
        return valid
    }

    internal fun setSubmitting(submitting: Boolean) {
        mutableState.value = mutableState.value.copy(submitting = submitting)
    }

    internal fun submissionSucceeded() {
        val pending = replacement
        if (pending == null) clear() else present(pending, mutableState.value.incomingRequestId)
    }

    internal fun restoreRejectedInput(input: String) {
        val current = mutableState.value
        mutableState.value = current.copy(
            input = current.input.ifBlank { input.takeIf { it.fitsMobileTransferInputLimit() }.orEmpty() },
            validation = if (!input.fitsMobileTransferInputLimit()) {
                MobileShareValidation.TooLong
            } else {
                current.validation
            },
            open = true,
            submitting = false,
        )
    }

    internal fun useSharedLink() {
        if (mutableState.value.submitting) return
        replacement?.let { present(it, requestId = null) }
    }

    internal fun keepDraft() {
        replacement = null
        mutableState.value = mutableState.value.copy(pendingReplacement = false, incomingRequestId = null)
    }

    private fun present(shared: MobileSharedTransfer, requestId: Long?) {
        replacement = null
        mutableState.value = MobileTransferDraftState(
            input = shared.input,
            open = true,
            validation = shared.validation,
            incomingRequestId = requestId,
        )
    }

    private fun clear() {
        replacement = null
        mutableState.value = MobileTransferDraftState()
    }
}
