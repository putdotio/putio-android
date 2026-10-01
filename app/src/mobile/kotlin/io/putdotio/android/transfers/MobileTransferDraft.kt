package io.putdotio.android.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.files.FilesFolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class MobileTransferDraftState(
    val input: String = "",
    val torrent: TorrentUpload? = null,
    /** Null saves to the account's default download folder. */
    val destination: FilesFolder? = null,
    val open: Boolean = false,
    val validation: MobileShareValidation? = null,
    val incomingRequestId: Long? = null,
    val pendingReplacement: Boolean = false,
    val submitting: Boolean = false,
) {
    override fun toString(): String =
        "MobileTransferDraftState(<redacted>, torrent=${torrent != null}, destination=${destination?.id}, " +
            "open=$open, validation=$validation, " +
            "incomingRequestId=$incomingRequestId, pendingReplacement=$pendingReplacement, submitting=$submitting)"
}

/** Activity-owned memory only: signed URLs must never enter a saved-state registry. */
class MobileTransferDraft internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    constructor() : this(Dispatchers.IO)

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
            clear(keepDestination = false)
            observedTransfers = false
            lastSuccessfulAdd = null
            lastMutation = TransferMutation.Idle
        }
        boundSessionId = sessionId
    }

    internal fun reconcileTransfers(state: TransfersState) {
        val receipt = state.lastAddReceipt
        if (observedTransfers && receipt != null && receipt.requestId != lastSuccessfulAdd) {
            submissionSucceeded(receipt.rejectedLinks)
        }
        observedTransfers = true
        lastSuccessfulAdd = receipt?.requestId
        setSubmitting((state.mutation as? TransferMutation.Running)?.action is TransferAction.Add)
        if (state.mutation != lastMutation) {
            when (val request = ((state.mutation as? TransferMutation.Failed)?.action as? TransferAction.Add)?.request) {
                is TransferAddRequest.Links -> restoreRejectedInput(request.links.joinLines())
                is TransferAddRequest.Torrent -> restoreRejectedTorrent(request.file)
                null -> Unit
            }
        }
        lastMutation = state.mutation
    }

    internal fun receive(shared: MobileSharedTransfer) {
        val current = mutableState.value
        val requestId = nextRequestId++
        if (current.submitting || current.open || current.input.isNotBlank() || current.torrent != null) {
            replacement = shared
            mutableState.value = current.copy(incomingRequestId = requestId, pendingReplacement = true)
        } else {
            present(shared, requestId)
        }
    }

    /** Reads a shared `.torrent` off the main thread; the result arrives like any other share. */
    internal fun receiveLater(read: () -> MobileSharedTransfer) {
        viewModelScope.launch { receive(withContext(ioDispatcher) { read() }) }
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

    internal fun removeTorrent() {
        if (mutableState.value.submitting) return
        mutableState.value = mutableState.value.copy(torrent = null, validation = null)
    }

    internal fun chooseDestination(folder: FilesFolder?) {
        if (mutableState.value.submitting) return
        mutableState.value = mutableState.value.copy(destination = folder)
    }

    internal fun dismiss() {
        if (mutableState.value.submitting) return
        mutableState.value = mutableState.value.copy(open = false, incomingRequestId = null)
    }

    /** The add to dispatch, or null after marking what the user must fix. */
    internal fun validate(): TransfersEvent? {
        val current = mutableState.value
        if (current.submitting || current.validation == MobileShareValidation.TooLong) return null
        val saveParentId = current.destination?.id?.value
        current.torrent?.let { return TransfersEvent.AddTorrent(it, saveParentId) }
        val valid = TransferSubmission.parseAll(current.input)?.joinLines()
        val invalid = if (current.input.split(LinkSeparator).count(String::isNotEmpty) > MAX_TRANSFER_LINKS) {
            MobileShareValidation.TooManyLinks
        } else {
            MobileShareValidation.InvalidLink
        }
        mutableState.value = current.copy(
            input = valid ?: current.input,
            validation = if (valid == null) current.validation ?: invalid else null,
        )
        return valid?.let { TransfersEvent.Add(it, saveParentId) }
    }

    internal fun setSubmitting(submitting: Boolean) {
        mutableState.value = mutableState.value.copy(submitting = submitting)
    }

    /** put.io refused [rejectedLinks]; they stay in the draft for the user to fix or drop. */
    internal fun submissionSucceeded(rejectedLinks: List<String> = emptyList()) {
        val current = mutableState.value
        val pending = replacement
        when {
            rejectedLinks.isNotEmpty() -> mutableState.value = MobileTransferDraftState(
                input = rejectedLinks.joinToString("\n"),
                destination = current.destination,
                open = true,
                validation = MobileShareValidation.NotAdded,
                incomingRequestId = current.incomingRequestId,
                pendingReplacement = pending != null,
            )
            pending == null -> clear(keepDestination = true)
            else -> present(pending, current.incomingRequestId)
        }
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

    internal fun restoreRejectedTorrent(file: TorrentUpload) {
        val current = mutableState.value
        mutableState.value = current.copy(
            torrent = current.torrent ?: file.takeIf { current.input.isBlank() },
            open = true,
            submitting = false,
        )
    }

    internal fun useSharedLink() {
        if (mutableState.value.submitting) return
        replacement?.let { present(it, requestId = mutableState.value.incomingRequestId) }
    }

    internal fun keepDraft() {
        replacement = null
        mutableState.value = mutableState.value.copy(pendingReplacement = false, incomingRequestId = null)
    }

    private fun present(shared: MobileSharedTransfer, requestId: Long?) {
        replacement = null
        mutableState.value = MobileTransferDraftState(
            input = shared.input,
            torrent = shared.torrent,
            destination = mutableState.value.destination,
            open = true,
            validation = shared.validation,
            incomingRequestId = requestId,
        )
    }

    // A chosen folder lasts for the session, like web's "custom folder in this session".
    private fun clear(keepDestination: Boolean) {
        replacement = null
        mutableState.value = MobileTransferDraftState(
            destination = mutableState.value.destination.takeIf { keepDestination },
        )
    }
}

private val LinkSeparator = Regex("[\\s\\p{Z}\\u0085]+")
