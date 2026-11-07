package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.DirectPlayDownload
import io.putdotio.android.playback.DirectPlayDownloadSource
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.files.FileSubtitle
import io.putdotio.sdk.files.PutioFileType
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMethodsTest {
    private val video = DownloadRequest(FilesItemId(7L), "Sintel.mkv", PutioFileType.VIDEO, 1_000L)
    private val tears = video.copy(fileId = FilesItemId(9L), name = "Tears.mkv")
    private val audio = DownloadRequest(FilesItemId(8L), "Track.flac", PutioFileType.AUDIO, 2_000L)
    private val store = FakeDownloadStore()
    private val engine = FakeDownloadEngine()
    private val asked = mutableListOf<Pair<FilesItemId, Boolean>>()

    @Test
    fun standardDownloadsTheConvertedHlsRenditionWithoutAskingAboutTheOriginal() = runBlocking {
        controller(StreamingMethod.Hls, answers = mapOf(video.fileId to original())).use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
            assertTrue(controller.dispatch(DownloadsEvent.Start(audio)))
            engine.await { it.started.size == 2 }

            assertEquals(
                mapOf(video.fileId to DownloadArtifact.HLS, audio.fileId to DownloadArtifact.ORIGINAL),
                engine.started.associate { it.fileId to it.artifact },
            )
            assertEquals(emptyList<Pair<FilesItemId, Boolean>>(), asked)
        }
    }

    @Test
    fun directPlayDownloadsTheAcceptedOriginalElseTheConvertedFileWithTheirSubtitles() = runBlocking {
        val answers = mapOf(
            video.fileId to original(ENGLISH, ELSEWHERE),
            tears.fileId to DirectPlayDownload(DirectPlayDownloadSource.Converted, listOf(ENGLISH)),
        )
        controller(StreamingMethod.File, answers).use { controller ->
            for (request in listOf(video, tears, audio)) assertTrue(controller.dispatch(DownloadsEvent.Start(request)))
            engine.await { it.started.size == 3 }

            val started = engine.started.associateBy { it.fileId }
            assertEquals(DownloadArtifact.ORIGINAL, started.getValue(video.fileId).artifact)
            assertEquals(DownloadArtifact.MP4, started.getValue(tears.fileId).artifact)
            assertEquals(DownloadArtifact.ORIGINAL, started.getValue(audio.fileId).artifact)
            val english = DownloadSubtitle(
                key = "en-key",
                url = "https://api.put.io/v2/files/7/subtitles/en-key",
                format = "srt",
                language = "English",
                languageCode = "eng",
                name = "Sintel.en.srt",
                source = "opensubtitles",
            )
            assertEquals(listOf(english), started.getValue(video.fileId).subtitles)
            assertEquals(listOf(english), started.getValue(tears.fileId).subtitles)
            assertEquals(started.values.toSet(), store.entries.value.toSet())
            assertEquals(setOf(video.fileId to true, tears.fileId to true), asked.toSet())
        }
    }

    @Test
    fun aFileMenuMethodDownloadsThatOneFileAndTheNextFollowsTheSetting() = runBlocking {
        val answers = mapOf(video.fileId to original(), tears.fileId to original())
        controller(StreamingMethod.Hls, answers).use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(video.copy(method = StreamingMethod.File))))
            assertTrue(controller.dispatch(DownloadsEvent.Start(tears)))
            engine.await { it.started.size == 2 }

            assertEquals(
                listOf(video.fileId to DownloadArtifact.ORIGINAL, tears.fileId to DownloadArtifact.HLS),
                engine.started.map { it.fileId to it.artifact },
            )
        }
        controller(StreamingMethod.File, answers).use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(audio.copy(fileId = FilesItemId(30L)))))
            val standard = video.copy(fileId = FilesItemId(31L), method = StreamingMethod.Hls)
            assertTrue(controller.dispatch(DownloadsEvent.Start(standard)))
            engine.await { it.started.size == 4 }
            assertEquals(DownloadArtifact.HLS, engine.started.last().artifact)
            assertEquals(listOf(video.fileId), asked.map { it.first })
        }
    }

    @Test
    fun hiddenSubtitlesAreNotAskedFor() = runBlocking {
        controller(StreamingMethod.File, mapOf(video.fileId to original())).use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(video.copy(subtitlesHidden = true))))
            engine.await { it.started.size == 1 }
            assertEquals(listOf(video.fileId to false), asked)
        }
    }

    @Test
    fun noConvertedCopyAndNoPlayableOriginalFailsUnavailableWithoutATransfer() = runBlocking {
        val unavailable = DirectPlayDownload(DirectPlayDownloadSource.Unavailable)
        controller(StreamingMethod.File, mapOf(video.fileId to unavailable)).use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
            val failed = store.await { rows -> rows.singleOrNull()?.status is DownloadStatus.Failed }.single()
            assertEquals(DownloadStatus.Failed(DownloadFailureReason.UNAVAILABLE, 0L), failed.status)
            settle()
            assertTrue(engine.started.isEmpty())

            assertTrue(controller.dispatch(DownloadsEvent.Retry(video.fileId)))
            store.await { rows -> rows.single().status is DownloadStatus.Failed && asked.size == 2 }
            assertTrue(engine.started.isEmpty())
        }
    }

    @Test
    fun aDirectPlayLookupThatNeverAnswersDownloadsTheConvertedFileNeverHls() = runTest {
        val sources = DownloadSources({ StreamingMethod.File }) { _, _ -> awaitCancellation() }
        val controller = DownloadsController(store, engine, backgroundScope, sources = sources)
        assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
        advanceTimeBy(9.seconds)
        runCurrent()
        assertTrue(engine.started.isEmpty())

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(DownloadArtifact.MP4, engine.started.single().artifact)
        controller.close()
    }

    @Test
    fun directPlayLookupsRunTogetherAndStartsKeepTapOrder() = runTest {
        val episodes = (20L..22L).map { video.copy(fileId = FilesItemId(it), name = "Episode $it.mkv") }
        val sources = DownloadSources({ StreamingMethod.File }) { _, _ -> awaitCancellation() }
        val controller = DownloadsController(store, engine, backgroundScope, sources = sources)
        for (request in listOf(episodes[0], audio, episodes[1], episodes[2])) {
            assertTrue(controller.dispatch(DownloadsEvent.Start(request)))
        }
        advanceTimeBy(9.seconds)
        runCurrent()
        assertTrue(engine.started.isEmpty())

        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(
            listOf(episodes[0].fileId, audio.fileId, episodes[1].fileId, episodes[2].fileId),
            engine.started.map { it.fileId },
        )
        controller.close()
    }

    @Test
    fun aVideoShowsQueuedWhileTheLookupRunsAndACancelledOneNeverStarts() = runBlocking {
        val answer = CompletableDeferred<DirectPlayDownload>()
        val sources = DownloadSources({ StreamingMethod.File }) { _, _ -> answer.await() }
        DownloadsController(store, engine, this, sources = sources).use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Start(video)))
            val waiting = controller.awaitState { it.entry(video.fileId) != null }.entry(video.fileId)
            assertEquals(DownloadStatus.Queued, waiting?.status)
            assertTrue(engine.started.isEmpty())

            assertTrue(controller.dispatch(DownloadsEvent.Cancel(video.fileId)))
            engine.await { it.removed == listOf(video.fileId) }
            engine.finishRemoval(video.fileId, store)
            answer.complete(original())
            controller.awaitState { it.entries.isEmpty() && it.removing.isEmpty() }
            settle()
            assertTrue(engine.started.isEmpty())
        }
    }

    @Test
    fun aDownloadKeepsItsMethodAndHeldBytesWhateverTheSettingIsNow() = runBlocking {
        store.upsert(failed(DownloadArtifact.ORIGINAL, 0L))
        store.upsert(failed(DownloadArtifact.MP4, 10L).copy(fileId = tears.fileId, name = tears.name))
        val hls = failed(DownloadArtifact.HLS, 0L).copy(fileId = FilesItemId(40L))
        store.upsert(hls)
        val answers = mapOf(video.fileId to DirectPlayDownload(DirectPlayDownloadSource.Converted))
        controller(StreamingMethod.Hls, answers).use { controller ->
            for (fileId in listOf(video.fileId, tears.fileId, hls.fileId)) {
                assertTrue(controller.dispatch(DownloadsEvent.Retry(fileId)))
            }
            engine.await { it.started.size == 3 }

            assertEquals(
                listOf(
                    video.fileId to DownloadArtifact.MP4,
                    tears.fileId to DownloadArtifact.MP4,
                    hls.fileId to DownloadArtifact.HLS,
                ),
                engine.started.map { it.fileId to it.artifact },
            )
            assertEquals(listOf(video.fileId), asked.map { it.first })
        }
    }

    @Test
    fun anOriginalOverHlsFromAnEarlierBuildKeepsItsBytesButNeverStartsOverAsOne() = runBlocking {
        val gone = FilesItemId(41L)
        store.upsert(failed(DownloadArtifact.ORIGINAL_HLS, 0L).copy(fileId = video.fileId))
        store.upsert(failed(DownloadArtifact.ORIGINAL_HLS, 10L).copy(fileId = tears.fileId))
        store.upsert(failed(DownloadArtifact.ORIGINAL_HLS, 0L).copy(fileId = gone, status = DownloadStatus.Missing))
        val answers = mapOf(video.fileId to original(), gone to DirectPlayDownload(DirectPlayDownloadSource.Converted))
        controller(StreamingMethod.Hls, answers).use { controller ->
            for (fileId in listOf(video.fileId, tears.fileId, gone)) {
                assertTrue(controller.dispatch(DownloadsEvent.Retry(fileId)))
            }
            engine.await { it.started.size == 3 }

            assertEquals(
                listOf(
                    video.fileId to DownloadArtifact.ORIGINAL,
                    tears.fileId to DownloadArtifact.ORIGINAL_HLS,
                    gone to DownloadArtifact.MP4,
                ),
                engine.started.map { it.fileId to it.artifact },
            )
        }
    }

    @Test
    fun aRetryStartsEvenWhenAReportRewritesItsRowWhileTheLookupRuns() = runBlocking {
        val answer = CompletableDeferred<DirectPlayDownload>()
        store.upsert(failed(DownloadArtifact.MP4, 0L))
        val sources = DownloadSources({ StreamingMethod.File }) { _, _ -> answer.await() }
        DownloadsController(store, engine, this, sources = sources).use { controller ->
            assertTrue(controller.dispatch(DownloadsEvent.Retry(video.fileId)))
            val queued = store.await { rows -> rows.single().status == DownloadStatus.Queued }.single()
            val reported = DownloadStatus.Failed(DownloadFailureReason.UNEXPECTED, 0L)
            store.upsert(queued.copy(status = reported, queuedAt = 1L))

            answer.complete(original())
            engine.await { it.started.size == 1 }
            assertEquals(video.fileId, engine.started.single().fileId)
        }
    }

    private fun CoroutineScope.controller(setting: StreamingMethod, answers: Map<FilesItemId, DirectPlayDownload>) =
        DownloadsController(
            store,
            engine,
            this,
            sources = DownloadSources({ setting }) { fileId, includeSubtitles ->
                asked += fileId to includeSubtitles
                answers[fileId]
            },
        )

    private fun original(vararg subtitles: FileSubtitle) =
        DirectPlayDownload(DirectPlayDownloadSource.Original, subtitles.toList())

    private fun failed(artifact: DownloadArtifact, bytes: Long) = DownloadEntry(
        video.fileId, video.name, PutioFileType.VIDEO, artifact,
        DownloadStatus.Failed(DownloadFailureReason.NETWORK, bytes), createdAt = 1L, accepted = true,
    )

    private suspend fun settle() = repeat(SETTLE_YIELDS) { yield() }

    private suspend fun DownloadsController.awaitState(predicate: (DownloadsState) -> Boolean): DownloadsState =
        withTimeout(5_000L) { state.first(predicate) }

    private companion object {
        const val SETTLE_YIELDS = 10
        val ENGLISH = FileSubtitle(
            format = "srt",
            key = "en-key",
            language = "English",
            languageCode = "eng",
            name = "Sintel.en.srt",
            source = "opensubtitles",
            url = "https://api.put.io/v2/files/7/subtitles/en-key?oauth_token=download",
        )
        val ELSEWHERE = ENGLISH.copy(key = "cdn", url = "https://cdn.example.com/subtitles/en.srt?token=t")
    }
}
