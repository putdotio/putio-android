package io.putdotio.android.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.files.FilesFolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
class MobileTransferDraft private constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val mutableState: MutableStateFlow<MobileTransferDraftState>,
) : ViewModel(), MobileTransferDraftEdits by MobileTransferDraftEditor(mutableState) {
    internal constructor(ioDispatcher: CoroutineDispatcher) :
        this(ioDispatcher, MutableStateFlow(MobileTransferDraftState()))

    constructor() : this(Dispatchers.IO)

    internal val state = mutableState.asStateFlow()
    private var boundSessionId: MobileAuthSessionId? = null
    private var nextRequestId = 1L
    private var replacement: MobileSharedTransfer? = null
    private var observedTransfers = false
    private var lastSuccessfulAdd: TransfersRequestId? = null
    private var lastMutation: TransferMutation = TransferMutation.Idle
    private var pendingRead: Job? = null
    private val readLock = Mutex()

    internal fun reconcileSession(sessionId: MobileAuthSessionId?) {
        if (boundSessionId != null && boundSessionId != sessionId) {
            pendingRead?.cancel()
            replacement = null
            mutableState.value = MobileTransferDraftState()
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
            val failedAdd = (state.mutation as? TransferMutation.Failed)?.action as? TransferAction.Add
            when (val request = failedAdd?.request) {
                is TransferAddRequest.Links -> restoreRejectedInput(request.links.joinLines())
                is TransferAddRequest.Torrent -> restoreRejectedTorrent(request.file)
                null -> Unit
            }
        }
        lastMutation = state.mutation
    }

    internal fun receive(shared: MobileSharedTransfer) {
        pendingRead?.cancel()
        deliver(shared)
    }

    /**
     * Reads a shared `.torrent` off the main thread; the result arrives like any other share. A newer
     * intake or a session change cancels the read so none lands out of order. A provider may ignore the
     * interrupt, so the lock is held until the read actually returns: at most one runs at a time.
     */
    internal fun receiveLater(read: () -> MobileSharedTransfer) {
        pendingRead?.cancel()
        pendingRead = viewModelScope.launch {
            deliver(readLock.withLock { runInterruptible(ioDispatcher) { read() } })
        }
    }

    private fun deliver(shared: MobileSharedTransfer) {
        val current = mutableState.value
        val requestId = nextRequestId++
        val hasContent = current.input.isNotBlank() || current.torrent != null
        if (current.submitting || current.open || hasContent) {
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
            // A chosen folder lasts for the session, like web's "custom folder in this session".
            pending == null -> mutableState.value = MobileTransferDraftState(destination = current.destination)
            else -> present(pending, current.incomingRequestId)
        }
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
}
