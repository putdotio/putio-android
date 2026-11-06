package io.putdotio.android.playback

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.account.AccountDisk
import io.putdotio.sdk.account.AccountDownloadToken
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountSettings
import io.putdotio.sdk.files.PlaybackCapabilities
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackPreference
import io.putdotio.sdk.files.PlaybackRequest
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SwitchedToConvertedTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theListKeepsTheNewestSwitchesAndMovesASwitchedFileToTheEnd() {
        val full = (1L..SWITCHED_TO_CONVERTED_LIMIT.toLong()).toList()

        val next = SWITCHED_TO_CONVERTED_LIMIT + 1L

        assertEquals((2L..next).toList(), full.withSwitched(next))
        assertEquals(full - 1L + 1L, full.withSwitched(1L))
        assertEquals(listOf(7L, 3L), listOf(3L, 7L).withSwitched(3L))
        assertEquals(listOf(9L), emptyList<Long>().withSwitched(9L))
    }

    @Test
    fun theStoreRemembersSwitchesWithinItsBoundUntilSignOutClearsIt() = runBlocking {
        val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreSwitchedToConvertedFiles(
                PreferenceDataStoreFactory.create(scope = dataStore) {
                    File(folder.root, "switched.preferences_pb")
                },
                writes,
                limit = 3,
            )
            suspend fun settle() = writes.coroutineContext.job.children.toList().joinAll()

            assertFalse(store.contains(1L))
            listOf(1L, 2L, 3L, 1L, 4L).forEach(store::add)
            settle()

            assertEquals(listOf(3L, 1L, 4L), store.read())
            assertFalse(store.contains(2L))
            assertTrue(store.contains(1L))

            store.clear()
            settle()
            assertEquals(emptyList<Long>(), store.read())
        } finally {
            writes.cancel()
            dataStore.cancel()
        }
    }

    @Test
    fun aSwitchedVideoNeverOpensItsOriginalWhileOtherVideosStillMay() = runBlocking {
        val switched = FakeSwitchedFiles(setOf(Switched.fileId.value))
        val requests = mutableListOf<PlaybackRequest>()
        val repository = SdkPlaybackRepository(
            playbackPreference = { PlaybackPreference.HLS },
            loadAccount = { account() },
            resolvePlayback = {
                requests += it
                io.putdotio.sdk.files.PlaybackResolution.Conversion(PlaybackConversionState.Queued)
            },
        )
        repository.resolve(Switched)
        repository.resolve(Other)
        val decision = OriginalPlaybackDecision(FakeDecoderInventory(emptySet()))
        val converted = PlaybackCapabilities()

        assertEquals(
            listOf(
                converted, converted, converted,
                converted,
                PlaybackCapabilities(originalVideoDecision = decision.forPreference(PlaybackPreference.HLS)),
                PlaybackCapabilities(originalVideoPlayable = true),
            ),
            requests.flatMap { request ->
                OriginalVideoPolicy.entries.map { request.withOriginalVideo(it, decision, switched).capabilities }
            },
        )
    }

    @Test
    fun theConvertingRepositoryRemembersOnlyVideos() {
        val switched = FakeSwitchedFiles(emptySet())
        val delegate = object : PlaybackRepository {
            override suspend fun resolve(
                target: PlaybackTarget,
                convertedOnly: Boolean,
            ): PlaybackRepositoryResult<PlaybackResolution> = error("Not expected")

            override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = error("Not expected")
        }
        val repository = ConvertingPlaybackRepository(delegate, { error("Not expected") }, switched)

        repository.rememberSwitchedToConverted(Other)
        repository.rememberSwitchedToConverted(Track)

        assertEquals(listOf(Other.fileId.value), switched.added)
    }

    @Test
    fun onlyTheViewersSwitchFromAStreamedOriginalIsRemembered() = runBlocking {
        val remembered = mutableListOf<FilesItemId>()
        val repository = object : PlaybackRepository {
            override suspend fun resolve(
                target: PlaybackTarget,
                convertedOnly: Boolean,
            ): PlaybackRepositoryResult<PlaybackResolution> {
                val kind = if (convertedOnly) PlaybackSourceKind.HLS else PlaybackSourceKind.ORIGINAL
                return PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source(kind)))
            }

            override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = error("Not expected")

            override fun rememberSwitchedToConverted(target: PlaybackTarget) {
                remembered += target.fileId
            }
        }
        suspend fun PlaybackController.ready(kind: PlaybackSourceKind) =
            withTimeout(TIMEOUT_MILLIS) { state.first { (it.content as? PlaybackContent.Ready)?.source?.kind == kind } }

        val automatic = PlaybackController(Switched, repository, this)
        try {
            automatic.ready(PlaybackSourceKind.ORIGINAL)
            assertTrue(automatic.dispatch(PlaybackEvent.OriginalRejected(PlaybackFallbackReason.AudioUnsupported, 0L)))
            automatic.ready(PlaybackSourceKind.HLS)
        } finally {
            automatic.close()
        }
        assertEquals(emptyList<FilesItemId>(), remembered)

        val viewer = PlaybackController(Switched, repository, this)
        try {
            viewer.ready(PlaybackSourceKind.ORIGINAL)
            assertTrue(viewer.dispatch(PlaybackEvent.OriginalRejected(PlaybackFallbackReason.UserSwitched, 9_000L)))
            assertEquals(9_000L, viewer.state.value.resumePositionMillis)
            viewer.ready(PlaybackSourceKind.HLS)
            assertFalse(viewer.dispatch(PlaybackEvent.OriginalRejected(PlaybackFallbackReason.UserSwitched, 9_000L)))
        } finally {
            viewer.close()
        }
        assertEquals(listOf(Switched.fileId), remembered)
    }

    private class FakeSwitchedFiles(private val switched: Set<Long>) : SwitchedToConvertedFiles {
        val added = mutableListOf<Long>()

        override suspend fun contains(fileId: Long): Boolean = fileId in switched

        override fun add(fileId: Long) {
            added += fileId
        }
    }

    private fun account(): AccountInfo =
        AccountInfo(
            userId = 1L,
            username = "test",
            mail = "test@example.com",
            avatarUrl = "https://example.com/avatar.png",
            disk = AccountDisk(available = 1L, size = 2L, used = 1L),
            settings = AccountSettings(sortBy = "NAME_ASC", useStartFrom = false),
            accountStatus = "active",
            downloadToken = AccountDownloadToken("download-token"),
        )

    private fun source(kind: PlaybackSourceKind): PlaybackSource =
        PlaybackSource(
            fileId = Switched.fileId.value,
            kind = kind,
            url = PutioCredentialUrl.of("https://api.put.io/v2/files/42/stream?oauth_token=secret"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        val Switched = PlaybackTarget(FilesItemId(42L), "episode.mkv")
        val Other = PlaybackTarget(FilesItemId(43L), "other.mkv")
        val Track = PlaybackTarget(FilesItemId(77L), "track.flac", PlaybackMediaType.AUDIO)
    }
}
