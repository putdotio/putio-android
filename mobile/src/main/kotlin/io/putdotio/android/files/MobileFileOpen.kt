package io.putdotio.android.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.putdotio.android.MainActivity
import io.putdotio.android.documents.MobileDocumentsProvider
import io.putdotio.android.documents.PutioDocumentId
import io.putdotio.android.documents.mimeTypeOf
import io.putdotio.sdk.files.PutioFileType

/** The in-app viewers; every other type that the players don't take opens with another app. */
internal enum class MobileFileViewerKind { Image, Pdf, Text }

internal val FilesItem.viewerKind: MobileFileViewerKind?
    get() = when {
        isFolder || id.value <= 0L -> null
        type == PutioFileType.IMAGE -> MobileFileViewerKind.Image
        type == PutioFileType.PDF -> MobileFileViewerKind.Pdf
        type == PutioFileType.TEXT -> MobileFileViewerKind.Text
        else -> null
    }

/**
 * Reaches a file's bytes through the app's own documents provider, the one the system file picker
 * lists: a completed download from disk, otherwise a ranged stream from put.io with the session in
 * the request header. The URI names the account and the file and carries no credential, so a viewer
 * or another app holds only a read grant that the provider revokes when the session ends.
 */
internal class MobileFileOpener(private val userId: Long) {
    fun documentUri(context: Context, item: FilesItem): Uri =
        DocumentsContract.buildDocumentUri(
            MobileDocumentsProvider.authority(context),
            PutioDocumentId(userId, item.id).value,
        )

    /** [documentUri] as a function value, for the detail pane's image preview. */
    fun documentUris(): (Context, FilesItem) -> Uri = ::documentUri

    /**
     * Offers the file to the apps that view its type through the system chooser, which also says when
     * none does. This app is left out: it takes .torrent files as transfers, not its own documents.
     */
    fun openWithAnotherApp(context: Context, item: FilesItem) {
        val uri = documentUri(context, item)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeOf(item.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        view.clipData = ClipData.newRawUri(null, uri)
        val chooser = Intent.createChooser(view, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            // No chooser at all is a broken device; there is nothing more to offer.
        }
    }
}
