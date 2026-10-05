package io.putdotio.android.share

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import io.putdotio.android.R
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.files.FilesItemId
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Exports one original file into private storage, then hands a content URI to the
 * system chooser. The chooser payload is the stream only: no text, no URL, no
 * credential. The API request carries the session header; the CDN redirect it
 * follows is never surfaced. Every earlier copy is pruned before an export and
 * copies older than a day on launch. A service
 * cannot start an Activity from the background, so the chooser opens from the
 * resumed Activity: immediately when one exists, otherwise from the next one to
 * resume, which a "ready" notification brings back. An export nobody returns for
 * within the timeout is deleted. Each export belongs to the session that started it and
 * lives under that session's directory: leaving that session cancels the export, deletes
 * it and never opens the chooser. The service runs one export at a time, so its own
 * cleanup clears the whole root; the auth runtime's session-exit cleanup can run after
 * the next session started, so it clears only the departed session's directory and
 * whatever earlier processes left.
 */
class MobileFileShareService : Service() {
    private val dependencies by lazy { shareDependencies(this) }
    private val scope by lazy { CoroutineScope(SupervisorJob() + dependencies.main) }
    private val exporter by lazy { MobileShareExporter(this, dependencies) }
    private val notifications = MobileShareNotifications(this)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fileId = intent?.getLongExtra(EXTRA_FILE_ID, -1L)?.takeIf { it > 0L }
        val name = intent?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }
        if (fileId == null || name == null || intent.action == ACTION_CANCEL) {
            // Every start arrives through startForegroundService; the promise must be kept before stopping.
            val label = name ?: getString(R.string.mobile_files_share)
            show(notifications.progress(label, indeterminate = true, progress = 0))
            val previous = job
            job = scope.launch {
                // Join so no progress update from the cancelled export lands after this stop.
                previous?.cancelAndJoin()
                ServiceCompat.stopForeground(this@MobileFileShareService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        show(notifications.progress(name, indeterminate = true, progress = 0))
        val session = dependencies.authState.value.sessionId
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                share(FilesItemId(fileId), name, session)
            } finally {
                // A newer start keeps the service alive; only the latest startId stops it.
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    // The share flow's boundary: every failure ends as the cause-free notice, an ended session as a silent drop.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun share(fileId: FilesItemId, name: String, session: MobileAuthSessionId?) {
        try {
            if (session == null) throw IOException("No session")
            boundTo(session) {
                val file = exporter.export(fileId, name, session) { percent ->
                    show(notifications.progress(name, indeterminate = false, progress = percent))
                }
                deliver(name, session) { shareChooser(file) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SessionEndedException) {
            shareRoot(this).deleteRecursively()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (error: IOException) {
            // A cancelled call surfaces as IOException; cancellation is not a failure to report.
            currentCoroutineContext().ensureActive()
            fail(name)
        } catch (error: RuntimeException) {
            // FileProvider, notification posting and Activity launch report failure as runtime errors.
            currentCoroutineContext().ensureActive()
            fail(name)
        }
    }

    /** Runs [block] while [session] stays current; leaving it cancels [block] and throws [SessionEndedException]. */
    private suspend fun boundTo(session: MobileAuthSessionId, block: suspend () -> Unit) = coroutineScope {
        val watcher = launch {
            dependencies.authState.first { it.sessionId != session }
            throw SessionEndedException()
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }

    /** Foreground updates need no POST_NOTIFICATIONS grant; detaching keeps the result visible. */
    private fun fail(name: String) {
        show(notifications.failed(name))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    @androidx.annotation.VisibleForTesting
    internal suspend fun deliverForTest(chooser: Intent, name: String, session: MobileAuthSessionId) =
        deliver(name, session) { chooser }

    /**
     * A resumed Activity opens the chooser; without one the ready notification brings the app back first.
     * The session is checked again at launch, so a resume that races a sign-out never shows the export.
     */
    private suspend fun deliver(name: String, session: MobileAuthSessionId, chooser: () -> Intent) {
        if (MobileResumedActivity.current == null) show(notifications.ready(name))
        val resumed: Activity? = try {
            withTimeoutOrNull(dependencies.readyTimeout) { MobileResumedActivity.await() }
        } catch (error: CancellationException) {
            // A dismissed or superseded wait drops its export together with the notification.
            shareRoot(this).deleteRecursively()
            throw error
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (resumed == null) {
            shareRoot(this).deleteRecursively()
            return
        }
        if (dependencies.authState.value.sessionId != session) throw SessionEndedException()
        resumed.startActivity(chooser())
    }

    // The type constant is inlined and ServiceCompat drops it below API 29, where the manifest type suffices.
    @SuppressLint("InlinedApi")
    private fun show(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun chooserForTest(file: File): Intent = shareChooser(file)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_FILE_ID = "fileId"
        private const val EXTRA_NAME = "name"
        internal const val ACTION_CANCEL = "io.putdotio.android.action.CANCEL_SHARE"
        private const val NOTIFICATION_ID = 3001
        private const val STALE_EXPORT_MS = 24L * 60L * 60L * 1000L

        /** Replaces the network, session and clock for services created afterwards; tests reset it. */
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var dependenciesForTest: MobileShareDependencies? = null

        /** The network, session and clock the service and the drag provider share. */
        internal fun shareDependencies(context: Context): MobileShareDependencies =
            dependenciesForTest ?: MobileShareDependencies.from(context)

        fun start(context: Context, fileId: FilesItemId, name: String) {
            val intent = Intent(context, MobileFileShareService::class.java)
                .putExtra(EXTRA_FILE_ID, fileId.value)
                .putExtra(EXTRA_NAME, name)
            context.startForegroundService(intent)
        }

        internal fun shareRoot(context: Context): File = File(context.filesDir, "shares")

        /**
         * Session ids restart in every process, so each process keeps its exports under its own
         * directory; a restored session cannot otherwise tell an earlier process's exports from its own.
         */
        private val processShares: String = UUID.randomUUID().toString()

        internal fun sessionShares(context: Context, session: MobileAuthSessionId): File =
            File(File(shareRoot(context), processShares), session.value.toString())

        /**
         * Leaving [session] drops every export it made and every export an earlier process left, but
         * nothing another session of this process made; a recipient already reading keeps its descriptor.
         * A null session is one an earlier process left behind, whose id this process never knew.
         */
        fun endSession(context: Context, session: MobileAuthSessionId?) {
            shareRoot(context).listFiles()
                ?.filter { it.name != processShares }
                ?.forEach { it.deleteRecursively() }
            session?.let { sessionShares(context, it).deleteRecursively() }
        }

        /** The session travels in the header only; the URL is the token-free API endpoint. */
        internal fun downloadRequest(fileId: FilesItemId, token: String): Request = Request.Builder()
            .url("https://api.put.io/v2/files/${fileId.value}/download")
            .header("Authorization", "Token $token")
            .build()

        /** Exports a recipient may still read are kept for a day; older leftovers go on launch. */
        fun pruneStale(context: Context, now: Long = System.currentTimeMillis()) {
            shareRoot(context).listFiles()
                ?.filter { now - it.lastModified() > STALE_EXPORT_MS }
                ?.forEach { it.deleteRecursively() }
        }
    }
}

/** Everything the service reaches outside itself; tests replace the network, session and clock. */
internal class MobileShareDependencies(
    val http: Call.Factory,
    val authState: StateFlow<MobileAuthState>,
    val accessToken: () -> String?,
    val readyTimeout: Duration = 10.minutes,
    val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    val io: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        fun from(context: Context): MobileShareDependencies {
            val runtime = MobileOAuthRuntime.get(context)
            return MobileShareDependencies(
                http = sharedHttp,
                authState = runtime.authController.state,
                accessToken = { runtime.putioClient.config.accessToken },
            )
        }
    }
}

private val sharedHttp: OkHttpClient by lazy { OkHttpClient() }

/** Stream only. Recipients receive a read grant on the content URI and nothing else. */
private fun Context.shareChooser(file: File): Intent {
    val uri = FileProvider.getUriForFile(this, "$packageName.share", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(contentResolver.getType(uri) ?: "application/octet-stream")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private val MobileAuthState.sessionId: MobileAuthSessionId?
    get() = (this as? MobileAuthState.SignedIn)?.sessionId

/** The export's session ended before the chooser opened; the export is dropped without a failure notice. */
private class SessionEndedException : Exception()

/**
 * Keeps the original name readable while making it a safe single path segment: no separators, no
 * control or bidi formatting characters that could disguise the extension, and at most
 * [MAX_NAME_BYTES] UTF-8 bytes, since file systems limit names in bytes, not characters.
 */
internal fun String.sanitizedFileName(): String {
    val visible = buildString {
        this@sanitizedFileName.codePoints().forEach { codePoint ->
            when {
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> append(' ')
                Character.getType(codePoint).toByte() in INVISIBLE_TYPES -> Unit
                else -> appendCodePoint(codePoint)
            }
        }
    }
    val cleaned = visible.trim().replace(UNSAFE_NAME_CHARACTERS, "_")
    return when {
        cleaned.isEmpty() || cleaned == "." || cleaned == ".." -> "file"
        cleaned.utf8Size() <= MAX_NAME_BYTES -> cleaned
        else -> cleaned.truncatedName()
    }
}

/** Truncation keeps a short extension so the chooser still sees the file type. */
private fun String.truncatedName(): String {
    val extension = substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.utf8Size() < MAX_EXTENSION_BYTES }
        ?.let { ".$it" }
        .orEmpty()
    val stem = removeSuffix(extension).takeUtf8Bytes(MAX_NAME_BYTES - extension.utf8Size())
    return (stem + extension).ifEmpty { "file" }
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

/** The longest prefix that fits in [limit] UTF-8 bytes without splitting a code point. */
private fun String.takeUtf8Bytes(limit: Int): String {
    var bytes = 0
    var end = 0
    while (end < length) {
        val next = end + Character.charCount(codePointAt(end))
        val size = substring(end, next).utf8Size()
        if (bytes + size > limit) break
        bytes += size
        end = next
    }
    return substring(0, end)
}

private const val MAX_NAME_BYTES = 200
private const val MAX_EXTENSION_BYTES = 16

/** Controls, formatting (bidi overrides, isolates, zero-width marks), separators and lone surrogates. */
private val INVISIBLE_TYPES = setOf(
    Character.CONTROL,
    Character.FORMAT,
    Character.SURROGATE,
    Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR,
)

private val UNSAFE_NAME_CHARACTERS = Regex("""[/\\ ]+""")
