package io.putdotio.android.transfers

import io.putdotio.android.files.FilesFolder
import kotlinx.coroutines.flow.MutableStateFlow

internal interface MobileTransferDraftEdits {
    fun open()

    fun edit(input: String)

    fun removeTorrent()

    /**
     * A `.torrent` picked from the open sheet takes the field's place without asking; the typed links
     * come back when it is removed. A read that failed shows why in the sheet.
     */
    fun attachTorrent(picked: MobileSharedTransfer)

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

    override fun attachTorrent(picked: MobileSharedTransfer) {
        val current = mutableState.value
        if (!current.open || current.submitting) return
        mutableState.value = current.copy(torrent = picked.torrent ?: current.torrent, validation = picked.validation)
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
                // Good lines go; a bad line stays in the field with its reason for the user to fix.
                val lines = checkMobileTransferLines(current.input)
                val links = lines.links.takeIf { it.isNotEmpty() && it.size <= MAX_TRANSFER_LINKS }
                mutableState.value = current.copy(
                    input = if (links != null && lines.badLines.isEmpty()) links.joinLines() else current.input,
                    validation = if (links == null) current.validation ?: lines.problem() else null,
                )
                links?.let { TransfersEvent.Add(it.joinLines(), saveParentId) }
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

private fun MobileTransferLines.problem(): MobileShareValidation =
    if (links.size > MAX_TRANSFER_LINKS) MobileShareValidation.TooManyLinks else MobileShareValidation.InvalidLink
