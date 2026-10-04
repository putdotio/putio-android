package io.putdotio.android.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.webkit.MimeTypeMap
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** The drag's own marker, visible only inside this app: which export a drag of ours started. */
internal class MobileFileDrag(val key: String, val itemId: FilesItemId)

/** Other apps may read the dropped URI, and only read it; no prefix, write or persistable grant. */
internal const val MOBILE_FILE_DRAG_FLAGS: Int = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ

/**
 * The drag payload: one content URI on this app's drag provider, named by a random key and the file's
 * safe name. No token, account id or API URL travels with it.
 */
internal fun mobileFileDragClip(authority: String, key: String, name: String, mimeType: String): ClipData {
    val uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(authority)
        .appendPath(key)
        .appendPath(name.sanitizedFileName())
        .build()
    return ClipData(ClipDescription(name, arrayOf(mimeType)), ClipData.Item(uri))
}

internal fun mobileDragAuthority(context: Context): String = "${context.packageName}.drag"

internal fun mimeTypeForName(name: String): String =
    name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        ?: "application/octet-stream"

/**
 * Files dragged out of the app, by the random key in their URI. A drop target gets the URI at once,
 * before the original is on the device; the export the drag started fills it in, and the provider
 * waits for it. An entry belongs to the session that started the drag.
 */
internal object MobileDragExports {
    class Export(val session: MobileAuthSessionId, val name: String, val sizeBytes: Long, val mimeType: String) {
        val file: CompletableFuture<File> = CompletableFuture()

        @Volatile private var cancelled = false

        @Volatile private var canceller: (() -> Unit)? = null

        /** Runs [action] when the drag is cancelled, at once if it already was. */
        fun onCancel(action: () -> Unit) {
            canceller = action
            if (cancelled) action()
        }

        fun cancel() {
            cancelled = true
            canceller?.invoke()
        }
    }

    private val exports = ConcurrentHashMap<String, Export>()

    fun begin(session: MobileAuthSessionId, name: String, sizeBytes: Long, mimeType: String): String =
        UUID.randomUUID().toString().also {
            exports[it] = Export(session, name.sanitizedFileName(), sizeBytes, mimeType)
        }

    operator fun get(key: String): Export? = exports[key]

    fun ready(key: String, file: File) {
        exports[key]?.file?.complete(file)
    }

    fun remove(key: String) {
        exports.remove(key)?.file?.completeExceptionally(IOException("Drag export ended"))
    }

    /** A new export deletes every earlier copy, so every other drag ends with it. */
    fun retainOnly(key: String?) {
        exports.keys.filter { it != key }.forEach(::remove)
    }

    /** Stops the export behind a drag nobody took. */
    fun cancel(key: String) {
        exports[key]?.cancel()
        remove(key)
    }

    /** Leaving a session ends its drags; a later session's drags stay. */
    fun endSession(session: MobileAuthSessionId) {
        exports.filterValues { it.session == session }.keys.forEach(::remove)
    }
}

/**
 * Starts dragging a file out of Files for [session]. The drop target receives the provider URI with a
 * read grant for that drop; the original downloads through the share-out export, bound to the same
 * session, while the drag is under way.
 */
internal class MobileFileDragOut(private val context: Context, private val session: MobileAuthSessionId) {
    fun start(view: View, item: FilesItem, shadow: View.DragShadowBuilder): Boolean {
        if (item.isFolder || item.id.value <= 0L) return false
        val mimeType = mimeTypeForName(item.name)
        val key = MobileDragExports.begin(session, item.name, item.sizeBytes, mimeType)
        val clip = mobileFileDragClip(mobileDragAuthority(context), key, item.name, mimeType)
        val started = view.startDragAndDrop(clip, shadow, MobileFileDrag(key, item.id), MOBILE_FILE_DRAG_FLAGS)
        if (started) {
            MobileFileShareService.startDrag(context, item.id, item.name, key)
        } else {
            MobileDragExports.remove(key)
        }
        return started
    }

    /** A drag nobody took stops its download; a dropped one keeps going for the reader. */
    fun ended(drag: MobileFileDrag, dropped: Boolean) {
        if (!dropped) MobileDragExports.cancel(drag.key)
    }
}

/** The file's name on a rounded chip under the pointer; the row itself stays where it is. */
internal class MobileFileDragShadow(
    name: String,
    density: Float,
    fontScale: Float,
    background: Int,
    foreground: Int,
) : View.DragShadowBuilder() {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foreground
        textSize = SHADOW_TEXT_SP * density * fontScale
    }
    private val label = TextUtils.ellipsize(name, paint, SHADOW_MAX_TEXT_DP * density, TextUtils.TruncateAt.END)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
    private val padding = SHADOW_PADDING_DP * density
    private val width = (paint.measureText(label, 0, label.length) + padding * 2).toInt()
    private val height = (paint.fontSpacing + padding * 2).toInt()

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(width, height)
        outShadowTouchPoint.set(width / 2, height / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        val radius = height / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, fill)
        canvas.drawText(label, 0, label.length, padding, padding - paint.fontMetrics.ascent, paint)
    }

    private companion object {
        const val SHADOW_TEXT_SP = 14f
        const val SHADOW_MAX_TEXT_DP = 280f
        const val SHADOW_PADDING_DP = 12f
    }
}
