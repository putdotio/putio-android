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

internal class MobileFileOpener(private val userId: Long) {
    fun documentUri(context: Context, item: FilesItem): Uri =
        DocumentsContract.buildDocumentUri(
            MobileDocumentsProvider.authority(context),
            PutioDocumentId(userId, item.id).value,
        )

    fun documentUris(): (Context, FilesItem) -> Uri = ::documentUri

    fun openWithAnotherApp(context: Context, item: FilesItem) {
        val uri = documentUri(context, item)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeOf(item.name, item.contentType))
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
