package io.putdotio.android.tv

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.putdotio.android.R
import java.io.File
import java.io.FileNotFoundException

internal fun tvLauncherFallbackArt(context: Context): String =
    Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority("${context.packageName}.launcherart")
        .appendPath(CARD_ART)
        .build()
        .toString()

internal class TvLauncherArtProvider : ContentProvider() {
    @Volatile private var rendered: File? = null

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = PNG

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.pathSegments != listOf(CARD_ART) || mode != "r") throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(current() ?: render(), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun current(): File? = rendered?.takeIf(File::exists)

    @Synchronized
    private fun render(): File {
        current()?.let { return it }
        val context = requireNotNull(context)
        val art = requireNotNull(ContextCompat.getDrawable(context, R.drawable.tv_launcher_card)).toBitmap()
        val file = File(context.cacheDir, CARD_ART)
        val partial = File(context.cacheDir, "$CARD_ART.partial")
        partial.outputStream().use { art.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        if (!partial.renameTo(file)) throw FileNotFoundException(file.path)
        rendered = file
        return file
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}

private const val CARD_ART = "card.png"
private const val PNG = "image/png"
private const val PNG_QUALITY = 100
