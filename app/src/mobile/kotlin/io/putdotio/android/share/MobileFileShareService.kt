package io.putdotio.android.share

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import io.putdotio.android.MainActivity
import io.putdotio.android.R
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.files.FilesItemId
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Exports one original file into private storage, then hands a content URI to the
 * system chooser. The chooser payload is the stream only: no text, no URL, no
 * credential. The API request carries the session header; the CDN redirect it
 * follows is never surfaced. Copies are pruned before the next export. A service
 * cannot start an Activity from the background, and the app holds no notification
 * permission, so when the app is not in the foreground the chooser waits for the
 * next resumed Activity and opens from there.
 */
class MobileFileShareService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fileId = intent?.getLongExtra(EXTRA_FILE_ID, -1L)?.takeIf { it > 0L }
        val name = intent?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }
        if (fileId == null || name == null || intent.action == ACTION_CANCEL) {
            // Every start arrives through startForegroundService; the promise must be kept before stopping.
            show(progressNotification(name.orEmpty(), indeterminate = true, progress = 0))
            job?.cancel()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        show(progressNotification(name, indeterminate = true, progress = 0))
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                val file = export(FilesItemId(fileId), name)
                deliver(chooser(file), name)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                fail(name)
            } catch (error: RuntimeException) {
                // FileProvider, notification posting and Activity launch report failure as runtime errors.
                fail(name)
            } finally {
                // A newer start keeps the service alive; only the latest startId stops it.
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun export(fileId: FilesItemId, name: String): File = withContext(Dispatchers.IO) {
        val root = shareRoot(this@MobileFileShareService)
        root.listFiles()?.forEach { it.deleteRecursively() }
        val directory = File(root, fileId.value.toString()).apply { mkdirs() }
        val target = File(directory, name.sanitizedFileName())
        val token = MobileOAuthRuntime.get(this@MobileFileShareService).putioClient.config.accessToken
            ?: throw IOException("No session")
        val request = Request.Builder()
            .url("https://api.put.io/v2/files/${fileId.value}/download")
            .header("Authorization", "Token $token")
            .build()
        val call = http.newCall(request)
        val cancelOnAbort = currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed with ${response.code}")
                val body = response.body ?: throw IOException("Empty body")
                copyWithProgress(body.byteStream(), target, body.contentLength(), name)
            }
        } finally {
            cancelOnAbort.dispose()
        }
        target
    }

    /** Foreground updates need no POST_NOTIFICATIONS grant; detaching keeps the result visible. */
    private fun fail(name: String) {
        show(failedNotification(name))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    /** Visible apps get the chooser directly; otherwise the next resumed Activity opens it. */
    private suspend fun deliver(chooser: Intent, name: String) {
        val state = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        if (state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        show(readyNotification(name, chooser))
        val resumed = withTimeoutOrNull(READY_TIMEOUT_MS) { awaitResumedActivity() }
        if (resumed == null) {
            // The notification keeps the chooser reachable after the service stops.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            return
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        resumed.startActivity(chooser)
    }

    private suspend fun awaitResumedActivity(): Activity = suspendCancellableCoroutine { continuation ->
        val app = application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                app.unregisterActivityLifecycleCallbacks(this)
                if (continuation.isActive) continuation.resume(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        continuation.invokeOnCancellation { app.unregisterActivityLifecycleCallbacks(callbacks) }
    }

    private suspend fun copyWithProgress(input: java.io.InputStream, target: File, total: Long, name: String) {
        var copied = 0L
        var lastPercent = -1
        input.use {
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContextActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total <= 0L) continue
                    val percent = (copied * PERCENT / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        show(progressNotification(name, false, percent))
                    }
                }
            }
        }
    }

    // The type constant is inlined and ServiceCompat drops it below API 29, where the manifest type suffices.
    @SuppressLint("InlinedApi")
    private fun show(notification: Notification) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private suspend fun currentCoroutineContextActive() = currentCoroutineContext().ensureActive()

    @androidx.annotation.VisibleForTesting
    internal fun chooserForTest(file: File): Intent = chooser(file)

    /** Stream only. Recipients receive a read grant on the content URI and nothing else. */
    private fun chooser(file: File): Intent {
        val uri = FileProvider.getUriForFile(this, "$packageName.share", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(contentResolver.getType(uri) ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun progressNotification(name: String, indeterminate: Boolean, progress: Int): Notification =
        baseNotification(getString(R.string.mobile_share_preparing, name))
            .setProgress(PERCENT.toInt(), progress, indeterminate)
            .setOngoing(true)
            .addAction(
                0,
                getString(R.string.mobile_action_cancel),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, MobileFileShareService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    @androidx.annotation.VisibleForTesting
    internal fun readyNotificationForTest(name: String, chooser: Intent): Notification = readyNotification(name, chooser)

    private fun readyNotification(name: String, chooser: Intent): Notification =
        baseNotification(getString(R.string.mobile_share_ready, name))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    READY_REQUEST_CODE,
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            .build()

    private fun failedNotification(name: String): Notification =
        baseNotification(getString(R.string.mobile_share_failed, name)).setAutoCancel(true).build()

    private fun baseNotification(text: String): NotificationCompat.Builder {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.mobile_share_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ph_arrow_circle_down)
            .setContentTitle(getString(R.string.mobile_files_share))
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_FILE_ID = "fileId"
        private const val EXTRA_NAME = "name"
        private const val ACTION_CANCEL = "io.putdotio.android.action.CANCEL_SHARE"
        private const val CHANNEL_ID = "share"
        private const val NOTIFICATION_ID = 3001
        private const val READY_REQUEST_CODE = 1
        private const val READY_TIMEOUT_MS = 10L * 60L * 1000L
        private const val BUFFER_SIZE = 64 * 1024
        private const val PERCENT = 100L
        private val http = OkHttpClient()

        fun start(context: Context, fileId: FilesItemId, name: String) {
            val intent = Intent(context, MobileFileShareService::class.java)
                .putExtra(EXTRA_FILE_ID, fileId.value)
                .putExtra(EXTRA_NAME, name)
            context.startForegroundService(intent)
        }

        internal fun shareRoot(context: Context): File = File(context.filesDir, "shares")
    }
}

/** Keeps the original name readable while making it a safe single path segment. */
internal fun String.sanitizedFileName(): String {
    val cleaned = trim().replace(UNSAFE_NAME_CHARACTERS, "_").ifBlank { "file" }
    return if (cleaned == "." || cleaned == "..") "file" else cleaned.take(MAX_NAME_LENGTH)
}

private const val MAX_NAME_LENGTH = 200

private val UNSAFE_NAME_CHARACTERS = Regex("""[/\\\s]+""")
