package io.putdotio.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.downloads.DownloadArtifact
import io.putdotio.android.downloads.DownloadEntry
import io.putdotio.android.downloads.DownloadRequest
import io.putdotio.android.downloads.DownloadSources
import io.putdotio.android.downloads.DownloadStatus
import io.putdotio.android.downloads.DownloadsController
import io.putdotio.android.downloads.DownloadsEvent
import io.putdotio.android.downloads.MobileDownloadEngine
import io.putdotio.android.downloads.MobileDownloadStore
import io.putdotio.android.downloads.mobileDirectPlayDownloads
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.ConvertingPlaybackRepository
import io.putdotio.android.playback.DataStoreSwitchedToConvertedFiles
import io.putdotio.android.playback.DirectPlayDownload
import io.putdotio.android.playback.DirectPlayDownloadSource
import io.putdotio.android.playback.MediaCodecDecoderInventory
import io.putdotio.android.playback.OriginalVideoPolicy
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFallbackReason
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.PlaybackPreference
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Live devs-auto API through the app's signed-in session: a switch to converted that resolves to no
 * converted stream, and a Direct play download whose lookup outlasts a restart. Reads only; the
 * download is local and removed.
 */
@RunWith(AndroidJUnit4::class)
class MobilePlaybackPickProofTest {
    @get:Rule
    val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Pick proof requires opt-in", arguments.getString("$LANE.enabled") == "true")
                UUID.fromString(requireNotNull(arguments.getString("$LANE.runId")) { "Missing $LANE.runId" })
                base.evaluate()
            }
        }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val log = Collections.synchronizedList(mutableListOf<String>())

    @Test
    fun aSwitchToConvertedThatResolvesToNoConvertedStreamIsNotRemembered() = proof("switch-unsupported") {
        val fileId = argument("unsupportedFile").toLong()
        val (session, _) = signedIn()
        val rewrites = AtomicInteger()
        val client = PutioClient(
            session.putioClient.config,
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                val playbackRead = request.method == "GET" && request.url.encodedPath == "/v2/files/$fileId" &&
                    request.url.queryParameter("mp4_status") == "1"
                if (!playbackRead || !response.isSuccessful) return@addInterceptor response
                val body = JSONObject(response.body.string())
                val file = body.getJSONObject("file")
                log += "api need_convert=${file.opt("need_convert")} is_mp4_available=${file.opt("is_mp4_available")}"
                file.put("need_convert", false).put("is_mp4_available", false)
                rewrites.incrementAndGet()
                response.newBuilder().body(body.toString().toResponseBody(response.body.contentType())).build()
            }.build(),
        )
        val switched = DataStoreSwitchedToConvertedFiles.get(context)
        switched.remove(fileId)
        val repository = ConvertingPlaybackRepository(
            client,
            playbackPreference = { PlaybackPreference.MP4 },
            originalVideoPolicy = { OriginalVideoPolicy.Check },
            decoders = MediaCodecDecoderInventory(context),
            switchedToConverted = switched,
        )
        val controller = PlaybackController(PlaybackTarget(FilesItemId(fileId), "pick-unsupported"), repository, scope)
        try {
            val original = controller.state.first { it.content !is PlaybackContent.Loading }.content
            log += "opened ${original.describe()}"
            check((original as? PlaybackContent.Ready)?.source?.kind == PlaybackSourceKind.ORIGINAL) {
                "Precondition: the original opens first, got ${original.describe()}"
            }
            assertTrue(controller.dispatch(PlaybackEvent.OriginalRejected(PlaybackFallbackReason.UserSwitched, 0L)))
            val settled = controller.state.first { it.content !is PlaybackContent.Loading }.content
            log += "switched to ${settled.describe()}"
            assertTrue("No playback read went through the interceptor", rewrites.get() > 0)
            assertTrue("The switch resolved to ${settled.describe()}", settled is PlaybackContent.Unsupported)
            delay(STORE_SETTLE_MILLIS)
            controller.close()
            delay(STORE_SETTLE_MILLIS)
            val remembered = switched.contains(fileId)
            log += "remembered=$remembered rewrites=${rewrites.get()}"
            assertFalse("A switch with no converted stream was remembered", remembered)
        } finally {
            withContext(NonCancellable) {
                controller.close()
                forget(switched, fileId)
            }
        }
    }

    @Test
    fun aDirectPlayDownloadThatRestartsBeforeItsLookupAnswersKeepsTheDirectPlayPick() = proof("download-restart") {
        val fileId = FilesItemId(argument("downloadFile").toLong())
        val (session, userId) = signedIn()
        val file = session.putioClient.files.get(fileId.value)
        check(file.fileType == PutioFileType.VIDEO && file.isMp4Available) { "Precondition: a video with an MP4" }
        check(MobileDownloadStore.peek(context, userId, fileId) == null) { "Precondition: no download row yet" }
        val switched = DataStoreSwitchedToConvertedFiles.get(context)
        val directPlay = mobileDirectPlayDownloads(context, session.putioClient)
        val unanswered = CompletableDeferred<DirectPlayDownload?>()
        val asked = CompletableDeferred<Boolean>()
        var downloads: Downloads? = null
        try {
            switched.add(fileId.value)
            val held = Downloads(
                userId,
                DownloadSources(
                    setting = { StreamingMethod.Hls },
                    directPlay = { _, _, chosen ->
                        asked.complete(chosen)
                        unanswered.await()
                    },
                ),
            ).also { downloads = it }
            val controller = held.controller
            assertTrue(
                controller.dispatch(
                    DownloadsEvent.Start(
                        DownloadRequest(
                            fileId,
                            file.name,
                            PutioFileType.VIDEO,
                            file.size,
                            method = StreamingMethod.File,
                        ),
                    ),
                ),
            )
            log += "before restart: lookup asked chosen=${withTimeout(LOOKUP_MILLIS) { asked.await() }}"
            val waiting = MobileDownloadStore.peek(context, userId, fileId)
            log += "before restart: ${waiting?.describe()}"
            check(waiting?.artifact == DownloadArtifact.MP4 && waiting.status == DownloadStatus.Queued) {
                "Precondition: the row waits on its lookup"
            }
            downloads = null
            held.close()

            val answers = mutableListOf<Pair<Boolean, DirectPlayDownload?>>()
            val restarted = Downloads(
                userId,
                DownloadSources(
                    setting = { StreamingMethod.Hls },
                    directPlay = { id, subtitles, chosen ->
                        directPlay.resolve(id, subtitles, chosen = chosen).also { answers += chosen to it }
                    },
                ),
            ).also { downloads = it }
            val decided = withTimeout(LOOKUP_MILLIS) {
                restarted.store.entries.mapNotNull { rows -> rows.firstOrNull { it.fileId == fileId } }
                    .first { answers.isNotEmpty() && it.status != DownloadStatus.Queued }
            }
            log += "after restart: lookup chosen=${answers.single().first} answer=${answers.single().second?.source}"
            log += "after restart: ${decided.describe()}"
            assertEquals(DirectPlayDownloadSource.Original, answers.single().second?.source)
            assertEquals(DownloadArtifact.ORIGINAL, decided.artifact)
            val done = withTimeout(DOWNLOAD_MILLIS) {
                restarted.store.entries.mapNotNull { rows -> rows.firstOrNull { it.fileId == fileId } }
                    .first { it.isCompleted }
            }
            log += "completed: ${done.describe()} original size=${file.size}"
            assertEquals(file.size, (done.status as DownloadStatus.Completed).bytes)
        } catch (error: Throwable) {
            unanswered.complete(null)
            withContext(NonCancellable) {
                runCatching { cleanUp(downloads, userId, fileId, switched) }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
        unanswered.complete(null)
        withContext(NonCancellable) { cleanUp(downloads, userId, fileId, switched) }
    }

    private suspend fun cleanUp(
        downloads: Downloads?,
        userId: Long,
        fileId: FilesItemId,
        switched: DataStoreSwitchedToConvertedFiles,
    ) {
        try {
            val cleanup = downloads ?: Downloads(userId, DownloadSources(directPlay = { _, _, _ -> awaitCancellation() }))
            try {
                cleanup.controller.dispatch(DownloadsEvent.RequestRemoval(setOf(fileId)))
                cleanup.controller.dispatch(DownloadsEvent.ConfirmRemoval)
                val removed = withTimeoutOrNull(DOWNLOAD_MILLIS) {
                    cleanup.store.entries.first { rows -> rows.none { it.fileId == fileId } }
                }
                log += "cleanup: download removed=${removed != null}"
            } finally {
                cleanup.close()
            }
        } finally {
            forget(switched, fileId.value)
        }
    }

    private inner class Downloads(userId: Long, sources: DownloadSources) {
        val store = MobileDownloadStore(context, userId)
        private val engine = MobileDownloadEngine(context, store, userId, scope)
        val controller = runCatching { DownloadsController(store, engine, scope, sources) }
            .onFailure { engine.close() }
            .getOrThrow()

        fun close() {
            controller.close()
            engine.close()
        }
    }

    private suspend fun forget(switched: DataStoreSwitchedToConvertedFiles, fileId: Long) {
        switched.remove(fileId)
        val forgotten = withTimeoutOrNull(STORE_SETTLE_MILLIS * SETTLE_ATTEMPTS) {
            while (switched.contains(fileId)) delay(POLL_MILLIS)
        }
        log += "cleanup: switch mark cleared=${forgotten != null}"
    }

    private suspend fun signedIn(): Pair<MobileOAuthRuntime, Long> {
        val runtime = MobileOAuthRuntime.get(context)
        val state = runtime.awaitSettledSession(SESSION_MILLIS.milliseconds)
        val account = checkNotNull((state as? MobileAuthState.SignedIn)?.account) { "Proof needs a signed-in session" }
        check(account.userId == argument("accountId").toLong()) { "Not the devs-auto account" }
        return runtime to account.userId
    }

    private fun proof(name: String, block: suspend () -> Unit) {
        val directory = proofDirectory("pick-proof-${arguments.getString("$LANE.runId")}")
        try {
            runBlocking(Dispatchers.Main) { withTimeout(TEST_MILLIS) { block() } }
            log += "PASS"
        } catch (error: Throwable) {
            log += "FAIL ${error.javaClass.simpleName}: ${error.message}"
            throw error
        } finally {
            File(directory, "$name.txt").writeText(log.joinToString("\n", postfix = "\n"))
            scope.cancel()
        }
    }

    private fun PlaybackContent.describe(): String = when (this) {
        is PlaybackContent.Ready -> "Ready(${source.kind})"
        else -> javaClass.simpleName
    }

    private fun DownloadEntry.describe(): String =
        "artifact=$artifact status=${status.javaClass.simpleName} directPlayChosen=$directPlayChosen"

    private val arguments get() = InstrumentationRegistry.getArguments()

    private fun argument(name: String): String =
        requireNotNull(arguments.getString("$LANE.$name")) { "Missing $LANE.$name" }

    private companion object {
        const val LANE = "putio.pick"
        const val STORE_SETTLE_MILLIS = 1_000L
        const val LOOKUP_MILLIS = 30_000L
        const val SESSION_MILLIS = 30_000L
        const val DOWNLOAD_MILLIS = 120_000L
        const val TEST_MILLIS = 360_000L
        const val SETTLE_ATTEMPTS = 5
        const val POLL_MILLIS = 100L
    }
}
