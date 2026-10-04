package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFolder
import kotlinx.coroutines.flow.MutableStateFlow

internal interface MobileTransferDraftEdits {
    fun open()

    fun edit(input: String)

    fun removeTorrent()

    fun chooseDestination(folder: FilesFolder?)

    fun dismiss()

    /** The add to dispatch, or null after marking what the user must fix. */
    fun validate(): TransfersEvent?

    fun setSubmitting(submitting: Boolean)

    fun restoreRejectedInput(input: String)

    fun restoreRejectedTorrent(file: TorrentUpload)
}

/** Edits that touch only the visible draft; [MobileTransferDraft] keeps share intake and the session. */
internal class MobileTransferDraftEditor(
    private val mutableState: MutableStateFlow<MobileTransferDraftState>,
) : MobileTransferDraftEdits {
    override fun open() {
        mutableState.value = mutableState.value.copy(open = true)
    }

    override fun edit(input: String) {
        val current = mutableState.value
        if (current.submitting) return
        mutableState.value = if (!input.fitsMobileTransferInputLimit()) {
            current.copy(validation = MobileShareValidation.TooLong)
        } else {
            current.copy(input = input, validation = null)
        }
    }

    override fun removeTorrent() {
        if (mutableState.value.submitting) return
        mutableState.value = mutableState.value.copy(torrent = null, validation = null)
    }

    override fun chooseDestination(folder: FilesFolder?) {
        if (mutableState.value.submitting) return
        mutableState.value = mutableState.value.copy(destination = folder)
    }

    override fun dismiss() {
        if (mutableState.value.submitting) return
        mutableState.value = mutableState.value.copy(open = false, incomingRequestId = null)
    }

    override fun validate(): TransfersEvent? {
        val current = mutableState.value
        val saveParentId = current.destination?.id?.value
        return when {
            current.submitting || current.validation == MobileShareValidation.TooLong -> null
            current.torrent != null -> TransfersEvent.AddTorrent(current.torrent, saveParentId)
            else -> {
                val valid = TransferSubmission.parseAll(current.input)?.joinLines()
                mutableState.value = current.copy(
                    input = valid ?: current.input,
                    validation = if (valid == null) current.validation ?: current.linkProblem() else null,
                )
                valid?.let { TransfersEvent.Add(it, saveParentId) }
            }
        }
    }

    override fun setSubmitting(submitting: Boolean) {
        mutableState.value = mutableState.value.copy(submitting = submitting)
    }

    override fun restoreRejectedInput(input: String) {
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

    override fun restoreRejectedTorrent(file: TorrentUpload) {
        val current = mutableState.value
        mutableState.value = current.copy(
            torrent = current.torrent ?: file.takeIf { current.input.isBlank() },
            open = true,
            submitting = false,
        )
    }
}

private fun MobileTransferDraftState.linkProblem(): MobileShareValidation =
    if (input.split(LinkSeparator).count(String::isNotEmpty) > MAX_TRANSFER_LINKS) {
        MobileShareValidation.TooManyLinks
    } else {
        MobileShareValidation.InvalidLink
    }

private val LinkSeparator = Regex("[\\s\\p{Z}\\u0085]+")
