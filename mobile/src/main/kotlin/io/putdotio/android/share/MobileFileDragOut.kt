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
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileSessionKey
import io.putdotio.android.auth.sessionKey
import io.putdotio.android.documents.DocumentReader
import io.putdotio.android.documents.RemoteOriginals
import io.putdotio.android.documents.mimeTypeOf
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The drag's own marker, visible only inside this app: which drag of ours this is. */
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

/**
 * Files dragged out of the app, by the random key in their URI. Nothing is copied: opening a drag's URI
 * streams the original from put.io with ranged reads, as the system file picker does, for the session that
 * started the drag and only while it is the signed-in one. Leaving that session stops every read.
 */
internal object MobileFileDrags {
    class Drag(
        val session: MobileSessionKey,
        val fileId: FilesItemId,
        val name: String,
        val sizeBytes: Long,
        val mimeType: String,
    ) {
        val readers: MutableSet<DocumentReader> = ConcurrentHashMap.newKeySet()
    }

    private val lock = Any()
    private val drags = LinkedHashMap<String, Drag>()

    fun begin(session: MobileSessionKey, item: FilesItem): String =
        UUID.randomUUID().toString().also { key ->
            val drag = Drag(session, item.id, item.name.sanitizedFileName(), item.sizeBytes, mimeTypeOf(item.name))
            val evicted = mutableListOf<Drag>()
            synchronized(lock) {
                drags[key] = drag
                // A dropped file stays readable; only the latest drags are kept, so this never grows.
                while (drags.size > MAX_DRAGS) drags.remove(drags.keys.first())?.let(evicted::add)
            }
            evicted.forEach(::stop)
        }

    operator fun get(key: String): Drag? = synchronized(lock) { drags[key] }

    /** A drag nobody took; its URI never reached another app. */
    fun forget(key: String) {
        synchronized(lock) { drags.remove(key) }?.let(::stop)
    }

    /** Leaving a session stops its readers and forgets its drags; a later session's drags stay. */
    fun endSession(session: MobileAuthSessionId?) {
        val ended = synchronized(lock) {
            drags.filterValues { it.session.sessionId == session }.keys.mapNotNull(drags::remove)
        }
        ended.forEach(::stop)
    }

    /**
     * A reader for the drag behind [key]. It returns at once; bytes are fetched as they are read, and each
     * read checks that the drag's session is still the signed-in one.
     */
    fun open(key: String, dependencies: MobileShareDependencies, onReleased: () -> Unit): DocumentReader {
        val drag = get(key)?.takeIf { dependencies.authState.value.sessionKey() == it.session }
            ?: throw FileNotFoundException("No such drag")
        val isCurrent = { dependencies.authState.value.sessionKey() == drag.session }
        val bytes = RemoteOriginals(dependencies.http, dependencies.accessToken).bytes(drag.fileId, drag.sizeBytes)
        lateinit var reader: DocumentReader
        reader = DocumentReader(bytes, drag.session, isCurrent) {
            drag.readers -= reader
            onReleased()
        }
        drag.readers += reader
        // A session that ended while this opened has already stopped the drag's other readers.
        if (get(key) !== drag) {
            reader.revoke()
            throw FileNotFoundException("The session ended")
        }
        return reader
    }

    internal fun clearForTest() {
        synchronized(lock) { drags.values.toList().also { drags.clear() } }.forEach(::stop)
    }

    private fun stop(drag: Drag) {
        drag.readers.forEach(DocumentReader::revoke)
    }

    private const val MAX_DRAGS = 32
}

/**
 * Starts dragging a file out of Files for [session]. The drop target receives the provider URI with a
 * read grant for that drop; the bytes stream from put.io only when it reads them.
 */
internal class MobileFileDragOut(private val context: Context, private val session: MobileSessionKey) {
    fun start(view: View, item: FilesItem, shadow: View.DragShadowBuilder): Boolean {
        if (item.isFolder || item.id.value <= 0L) return false
        val key = MobileFileDrags.begin(session, item)
        val clip = mobileFileDragClip(mobileDragAuthority(context), key, item.name, mimeTypeOf(item.name))
        val started = view.startDragAndDrop(clip, shadow, MobileFileDrag(key, item.id), MOBILE_FILE_DRAG_FLAGS)
        if (!started) MobileFileDrags.forget(key)
        return started
    }

    /** A drag nobody took is forgotten; a dropped one stays readable by the app it landed in. */
    fun ended(drag: MobileFileDrag, dropped: Boolean) {
        if (!dropped) MobileFileDrags.forget(drag.key)
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
