package io.putdotio.android.tv

import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesOpenOrigin
import io.putdotio.android.files.FilesPage
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioApiErrorEnvelope
import io.putdotio.sdk.files.FileDeleteResult
import io.putdotio.android.files.FilesDeleteMode
import io.putdotio.android.files.FilesStreamUrlResult
import io.putdotio.android.files.FilesStreamUrls
import io.putdotio.android.files.FilesWatchedRepository
import io.putdotio.android.files.StubFilesRepository
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryEventKind
import io.putdotio.android.history.HistoryFileId
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.HistoryNoticeType
import io.putdotio.android.history.HistoryPage
import io.putdotio.android.history.HistoryPaging
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.history.HistoryTransferId
import io.putdotio.android.search.RecentSearchStoreOwner
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsRepository
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigRepository
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PlaybackPreference
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.android.trash.TrashBulkSelection
import io.putdotio.android.trash.TrashEvent
import io.putdotio.android.trash.TrashPage
import io.putdotio.android.trash.TrashRepository
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.home.TvMemoryContinueWatchingStore
import io.putdotio.android.tv.home.TvShowHomeStore
import io.putdotio.android.tv.watchnext.TvWatchNextMedia
import io.putdotio.android.tv.watchnext.TvWatchNextRecorder
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvSessionViewModelTest {
    private val auth = MutableStateFlow<TvAuthState>(signedIn(1))
    private val stores = mutableListOf<FakeRecentSearchStore>()
    private val watched = FakeWatchedRepository()
    private val playbackRepository = FakePlaybackRepository()
    private var appConfigPreferences = AndroidAppConfigPreferences()
    /** Listing an item's own id returns it as the parent, as the API does for a file. */
    private val listedItems = mutableMapOf<FilesItemId, FilesItem>()
    private val heldListings = mutableMapOf<FilesItemId, CompletableDeferred<Unit>>()
    private var historyItems = emptyList<HistoryItem>()
    /** The root listing's pages, continued by `page-N` cursors; empty lists an empty root. */
    private var rootPages = emptyList<FilesPage>()
    private var streamResult: (FilesItemId) -> FilesStreamUrlResult = {
        FilesStreamUrlResult.Ready("https://api.put.io/v2/files/${it.value}/stream?oauth_token=t")
    }
    private val dependencies = TvSessionDependencies(
        filesRepository = object : StubFilesRepository() {
            override suspend fun loadFolder(folderId: FilesItemId): PutioResult<FilesPage> {
                heldListings[folderId]?.await()
                rootPages.firstOrNull()?.takeIf { folderId == FilesFolder.Root.id }?.let {
                    return PutioResult.Success(it)
                }
                return PutioResult.Success(FilesPage(emptyList(), null, parent = listedItems[folderId]))
            }

            override suspend fun loadNextPage(cursor: FilesCursor): PutioResult<FilesPage> =
                PutioResult.Success(rootPages[cursor.value.removePrefix("page-").toInt()])
        },
        searchRepository = object : SearchRepository {
            override suspend fun search(term: SearchTerm) =
                PutioResult.Success(SearchPage(emptyList(), nextCursor = null, total = 0))

            override suspend fun loadNextPage(cursor: FilesCursor) = error("No continuation expected")
        },
        historyRepository = object : HistoryRepository {
            override suspend fun load(before: HistoryEventId?) =
                PutioResult.Success(HistoryPage(historyItems, hasMore = false))

            override suspend fun clear() = PutioResult.Success(Unit)
        },
        trashRepository = StubTrashRepository,
        settingsRepository = StubAccountSettingsRepository,
        appConfigRepository = object : AndroidAppConfigRepository {
            override suspend fun load() = PutioResult.Success(appConfigPreferences)

            override suspend fun save(change: AndroidAppConfigChange) = PutioResult.Success(Unit)
        },
        watchedRepository = watched,
        streamUrls = FilesStreamUrls { streamResult(it) },
        filesItemResolver = object : FilesItemResolver {
            override suspend fun resolveItem(itemId: FilesItemId) =
                PutioResult.Success(media(itemId.value, "resolved-${itemId.value}", PutioFileType.VIDEO))
        },
        recentSearchStore = { FakeRecentSearchStore().also { stores += it } },
        playbackRepository = { preference -> playbackRepository.also { it.preference = preference } },
        writePlaybackPosition = { _, _ -> PlaybackRepositoryResult.Success(Unit) },
    )

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun `the same session reuses its controllers and a new session replaces them`() {
        val viewModel = TvSessionViewModel(auth)
        val first = checkNotNull(viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))
        first.filesFocusMemory[0L] = 7L

        assertSame(first, viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))

        auth.value = signedIn(2)
        val second = checkNotNull(viewModel.sessionFor(account(), TvAuthSessionId(2), dependencies))
        assertNotSame(first, second)
        assertNull(second.filesFocusMemory[0L])
        assertFalse(first.files.dispatch(FilesBrowserEvent.Refresh))
        assertFalse(first.search.updateQuery("late"))
        assertFalse(first.trash.dispatch(TrashEvent.Open))
        assertTrue(stores.first().closed)
        assertFalse(stores.last().closed)
    }

    @Test
    fun `a history row resolves its file for Files to open`() = runTest {
        val viewModel = TvSessionViewModel(auth)
        val session = checkNotNull(viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))

        assertTrue(session.history.dispatch(HistoryEvent.OpenFile(HistoryFileId(55))))

        assertEquals("resolved-55", session.historyOpens.first().name)
        assertNull(session.historyOpenFailure.value)
    }

    @Test
    fun `History lists every event the phone lists`() {
        historyItems = listOf(
            HistoryItem(
                HistoryEventId(5),
                "2026-09-12T11:00:00",
                HistoryEventKind.File(id = null, name = "Removed share.mp4"),
            ),
            HistoryItem(
                HistoryEventId(3),
                "2026-09-12T09:30:00",
                HistoryEventKind.Notice(HistoryNoticeType.Upload, "clip.mp4"),
            ),
            HistoryItem(
                HistoryEventId(2),
                "2026-09-12T09:00:00",
                HistoryEventKind.Transfer(HistoryTransferId(20), HistoryFileId(21), "Sample folder"),
            ),
            HistoryItem(HistoryEventId(1), "2026-09-12T08:00:00", HistoryEventKind.Other("zip_created")),
        )
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))

        assertEquals(
            HistoryContent.Ready(historyItems, HistoryPaging.Complete),
            session.history.state.value.content,
        )
    }

    @Test
    fun `dismissing a history open failure keeps a session verdict`() = runTest {
        val rejected = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))
        val deps = TvSessionDependencies(
            filesRepository = dependencies.filesRepository,
            searchRepository = dependencies.searchRepository,
            historyRepository = dependencies.historyRepository,
            trashRepository = dependencies.trashRepository,
            settingsRepository = dependencies.settingsRepository,
            appConfigRepository = dependencies.appConfigRepository,
            watchedRepository = dependencies.watchedRepository,
            streamUrls = dependencies.streamUrls,
            filesItemResolver = object : FilesItemResolver {
                override suspend fun resolveItem(itemId: FilesItemId) = PutioResult.Failure(rejected)
            },
            recentSearchStore = dependencies.recentSearchStore,
            playbackRepository = dependencies.playbackRepository,
            writePlaybackPosition = dependencies.writePlaybackPosition,
        )
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), deps))

        session.history.dispatch(HistoryEvent.OpenFile(HistoryFileId(55)))
        assertEquals(rejected, session.historyOpenFailure.value)

        session.dismissHistoryOpenFailure()
        assertEquals(rejected, session.historyOpenFailure.value)
    }

    @Test
    fun `marking watched writes the duration and the listing row follows`() = runTest {
        val viewModel = TvSessionViewModel(auth)
        val session = checkNotNull(viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))
        val video = streamItem.copy(playback = FilesPlaybackProgress(0.0, 120.0))
        session.setWatched(video, watched = true)
        assertEquals(listOf(FilesItemId(9) to 120.0), watched.calls)
        assertNull(session.fileActionFailure.value)

        session.setWatched(video.copy(playback = FilesPlaybackProgress(120.0, 120.0)), watched = false)
        assertEquals(FilesItemId(9) to null, watched.calls.last())
    }

    @Test
    fun `a failed watched write is reported and a session verdict survives dismissal`() = runTest {
        val rejected = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))
        watched.failure = rejected
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        val video = streamItem.copy(playback = FilesPlaybackProgress(30.0, 120.0))

        session.setWatched(video, watched = false)
        assertEquals(rejected, session.fileActionFailure.value)
        session.dismissFileActionFailure()
        assertEquals(rejected, session.fileActionFailure.value)
        assertEquals(
            "https://api.put.io/v2/files/9/stream?oauth_token=t",
            (session.originalStreamUrl(video.id) as FilesStreamUrlResult.Ready).url,
        )
    }

    @Test
    fun `a rejected stream lookup is the session verdict and survives dismissal`() = runTest {
        val rejected = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))
        streamResult = { FilesStreamUrlResult.Failure(rejected) }
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))

        val result = session.originalStreamUrl(streamItem.id)

        assertEquals(FilesStreamUrlResult.Failure(rejected), result)
        assertEquals(rejected, session.fileActionFailure.value)
        session.dismissFileActionFailure()
        assertEquals(rejected, session.fileActionFailure.value)
    }

    @Test
    fun `a missing download token or other stream failure is no session verdict`() = runTest {
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))

        streamResult = { FilesStreamUrlResult.DownloadTokenUnavailable }
        assertEquals(FilesStreamUrlResult.DownloadTokenUnavailable, session.originalStreamUrl(streamItem.id))
        assertNull(session.fileActionFailure.value)

        val offline = FilesStreamUrlResult.Failure(PutioFailure.Unexpected(IllegalStateException("offline")))
        streamResult = { offline }
        assertEquals(offline, session.originalStreamUrl(streamItem.id))
        assertNull(session.fileActionFailure.value)
    }

    @Test
    fun `a session is refused when it is not the signed-in one`() {
        val viewModel = TvSessionViewModel(auth)

        assertNull(viewModel.sessionFor(account(), TvAuthSessionId(9), dependencies))
        assertNull(viewModel.sessionFor(account(userId = 7), TvAuthSessionId(1), dependencies))
    }

    @Test
    fun `playing a video resolves it on the session with the account's playback type`() {
        appConfigPreferences = AndroidAppConfigPreferences(videoPlaybackType = VideoPlaybackType.Mp4)
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))

        session.play(media(9, "clip.mp4", PutioFileType.VIDEO).copy(playback = FilesPlaybackProgress(30.0, 840.0)))

        val playback = checkNotNull(session.playback.value)
        assertEquals(
            PlaybackTarget(FilesItemId(9), "clip.mp4", PlaybackMediaType.VIDEO, 840.0),
            playback.state.value.target,
        )
        assertTrue(playback.state.value.content is PlaybackContent.Ready)
        assertEquals(listOf(PlaybackPreference.MP4), playbackRepository.preferencesSeen)
    }

    @Test
    fun `stopping playback returns to the shell and closes the controller`() {
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        session.play(media(9, "clip.mp4", PutioFileType.VIDEO))
        val first = checkNotNull(session.playback.value)

        session.play(media(10, "song.mp3", PutioFileType.AUDIO))
        val second = checkNotNull(session.playback.value)
        assertFalse("A replaced playback is closed", first.dispatch(PlaybackEvent.Retry))
        assertEquals(PlaybackMediaType.AUDIO, second.state.value.target.mediaType)

        session.stopPlayback()
        assertNull(session.playback.value)
        assertFalse(second.dispatch(PlaybackEvent.Retry))
    }

    @Test
    fun `leaving a video autoplay moved on to focuses that video's Files row`() {
        playbackRepository.next[FilesItemId(9)] = PlaybackTarget(FilesItemId(10), "Harbor film 2.mp4")
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        session.filesFocusMemory[0L] = 9L

        session.play(media(9, "Harbor film.mp4", PutioFileType.VIDEO))
        val playback = checkNotNull(session.playback.value)
        assertTrue(playback.dispatch(PlaybackEvent.PlayerEnded))
        assertEquals(FilesItemId(10), playback.state.value.target.fileId)
        assertTrue(playback.state.value.content is PlaybackContent.Ready)

        session.stopPlayback()
        assertEquals(10L, session.filesFocusMemory[0L])
    }

    @Test
    fun `leaving a video autoplay moved on to beyond the loaded rows reads on to that row`() {
        rootPages = (0 until 3).map { page ->
            FilesPage(
                items = (1L..50L).map {
                    media(page * 100L + it, "Harbor film ${page * 100L + it}.mp4", PutioFileType.VIDEO)
                },
                nextCursor = FilesCursor("page-${page + 1}").takeIf { page < 2 },
            )
        }
        playbackRepository.next[FilesItemId(9)] = PlaybackTarget(FilesItemId(203), "Harbor film 203.mp4")
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        assertEquals(50, (session.files.state.value.current.content as FilesContent.Ready).items.size)

        session.play(media(9, "Harbor film 9.mp4", PutioFileType.VIDEO))
        val playback = checkNotNull(session.playback.value)
        assertTrue(playback.dispatch(PlaybackEvent.PlayerEnded))
        session.stopPlayback()

        assertEquals(203L, session.filesFocusMemory[0L])
        val content = session.files.state.value.current.content as FilesContent.Ready
        assertEquals(150, content.items.size)
        assertEquals(102, content.viewport.firstVisibleItemIndex)
    }

    @Test
    fun `leaving a video that played alone keeps the focused Files row`() {
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        session.filesFocusMemory[0L] = 9L

        session.play(media(9, "Harbor film.mp4", PutioFileType.VIDEO))
        session.stopPlayback()

        assertEquals(mapOf(0L to 9L), session.filesFocusMemory)
    }

    @Test
    fun `only media plays and signing out ends playback`() {
        val viewModel = TvSessionViewModel(auth)
        val session = checkNotNull(viewModel.sessionFor(account(), TvAuthSessionId(1), dependencies))
        session.play(media(3, "notes.txt", PutioFileType.TEXT))
        assertNull(session.playback.value)

        session.play(media(9, "clip.mp4", PutioFileType.VIDEO))
        val playback = checkNotNull(session.playback.value)
        assertNotNull(session.playbackReporting.lease(9))
        auth.value = TvAuthState.Initializing

        assertNull(session.playback.value)
        assertNull("Signing out ends position write-back", session.playbackReporting.lease(9))
        assertFalse(playback.dispatch(PlaybackEvent.Retry))
        session.play(media(9, "clip.mp4", PutioFileType.VIDEO))
        assertNull("A closed session starts nothing", session.playback.value)
        assertEquals(listOf(FilesItemId(9)), playbackRepository.resolved.map { it.fileId })
    }

    @Test
    fun `a media pick plays over its pane with the duration its own listing carries`() {
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        val filesBefore = session.files.state.value
        val clip = media(9, "clip.mp4", PutioFileType.VIDEO)
        listedItems[clip.id] = clip.copy(playback = FilesPlaybackProgress(0.0, 840.0))

        assertEquals(TvExternalOpen.PLAYING, session.openExternal(clip, FilesOpenOrigin.SEARCH))

        val target = checkNotNull(session.playback.value).state.value.target
        assertEquals(PlaybackTarget(clip.id, "clip.mp4", PlaybackMediaType.VIDEO, 840.0), target)
        assertSame("Files keeps its location", filesBefore, session.files.state.value)
    }

    @Test
    fun `a media pick without a listed duration still plays`() {
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))

        assertEquals(TvExternalOpen.PLAYING,
            session.openExternal(media(10, "song.mp3", PutioFileType.AUDIO), FilesOpenOrigin.HISTORY))

        assertEquals(PlaybackTarget(FilesItemId(10), "song.mp3", PlaybackMediaType.AUDIO),
            checkNotNull(session.playback.value).state.value.target)
    }

    @Test
    fun `a newer pick in Files cancels media still waiting for its duration`() {
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        val clip = media(9, "clip.mp4", PutioFileType.VIDEO)
        val listing = CompletableDeferred<Unit>().also { heldListings[clip.id] = it }

        assertEquals(TvExternalOpen.PLAYING, session.openExternal(clip, FilesOpenOrigin.SEARCH))
        assertEquals(TvExternalOpen.IN_FILES,
            session.openExternal(media(44, "Documents", PutioFileType.FOLDER), FilesOpenOrigin.SEARCH))
        listing.complete(Unit)

        assertNull(session.playback.value)
        assertEquals(FilesItemId(44), session.files.state.value.current.folder.id)
    }

    @Test
    fun `a document pick opens its folder in Files with focus on it`() {
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        val notes = media(11, "notes.pdf", PutioFileType.PDF).copy(parentId = FilesItemId(44))

        assertEquals(TvExternalOpen.IN_FILES, session.openExternal(notes, FilesOpenOrigin.HISTORY))

        val current = session.files.state.value.current
        assertEquals(FilesItemId(44), current.folder.id)
        assertEquals(FilesOpenOrigin.HISTORY, current.openedFrom)
        assertEquals(11L, session.filesFocusMemory[44L])
        assertNull(session.playback.value)
    }

    @Test
    fun `Open folder shows a video's folder in Files without playing it`() {
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        val clip = media(12, "clip.mp4", PutioFileType.VIDEO).copy(parentId = FilesItemId(44))

        assertEquals(TvExternalOpen.IN_FILES, session.openExternal(clip, FilesOpenOrigin.HISTORY, playMedia = false))

        assertEquals(FilesItemId(44), session.files.state.value.current.folder.id)
        assertEquals(12L, session.filesFocusMemory[44L])
        assertNull(session.playback.value)
    }

    @Test
    fun `a folder pick opens that folder in Files`() {
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))

        val folder = media(44, "Documents", PutioFileType.FOLDER)
        assertEquals(TvExternalOpen.IN_FILES, session.openExternal(folder, FilesOpenOrigin.SEARCH))

        val path = session.files.state.value.path
        assertEquals(listOf(FilesFolder.Root, FilesFolder(FilesItemId(44), "Documents")), path)
        assertTrue(session.filesFocusMemory.isEmpty())
    }

    @Test
    fun `a Watch Next card plays its saved position without asking, as a search result does not`() = runTest {
        playbackRepository.savedSeconds = 45.0
        val listed = media(55, "resolved-55", PutioFileType.VIDEO).copy(playback = FilesPlaybackProgress(45.0, 600.0))
        listedItems[listed.id] = listed
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))

        session.links.open(FilesItemId(55), continueWatching = true)

        assertEquals(TvExternalOpen.PLAYING, session.links.opens.first())
        val continued = checkNotNull(session.playback.value).state.value
        assertTrue(continued.content is PlaybackContent.Ready)
        assertEquals(45_000L, continued.resumePositionMillis)

        session.links.open(FilesItemId(55), continueWatching = false)

        assertEquals(TvExternalOpen.PLAYING, session.links.opens.first())
        assertTrue(checkNotNull(session.playback.value).state.value.content is PlaybackContent.AwaitingResume)
    }

    @Test
    fun `the detail's Resume and Start over answer the resume prompt the row asks`() = runTest {
        playbackRepository.savedSeconds = 45.0
        val video = media(55, "Blue.Lights.mkv", PutioFileType.VIDEO)
            .copy(playback = FilesPlaybackProgress(45.0, 600.0))
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))

        session.play(video)
        assertTrue(checkNotNull(session.playback.value).state.value.content is PlaybackContent.AwaitingResume)

        session.play(video, TvPlayStart.Resume)
        val resumed = checkNotNull(session.playback.value).state.value
        assertTrue(resumed.content is PlaybackContent.Ready)
        assertEquals(45_000L, resumed.resumePositionMillis)

        session.play(video, TvPlayStart.StartOver)
        val restarted = checkNotNull(session.playback.value).state.value
        assertTrue(restarted.content is PlaybackContent.Ready)
        assertEquals(0L, restarted.resumePositionMillis)
    }

    @Test
    fun `a Watch Next card whose file is gone leaves the row and Files explains why`() = runTest {
        val gone = PutioFailure.ApiRejected(404, "NotFound", PutioConfigurationException("404"))
        val recorder = FakeWatchNextRecorder()
        val deps = dependencies.copy(
            filesItemResolver = object : FilesItemResolver {
                override suspend fun resolveItem(itemId: FilesItemId) = PutioResult.Failure(gone)
            },
            watchNext = { _, _ -> recorder },
        )
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), deps))

        session.links.open(FilesItemId(55), continueWatching = true)

        assertEquals(gone, session.links.failure.value)
        assertEquals(listOf(55L), recorder.gone)
        assertNull(session.playback.value)
        session.links.dismissFailure()
        assertNull(session.links.failure.value)

        session.links.open(FilesItemId(56), continueWatching = false)
        assertEquals("A search result is not a card", listOf(55L), recorder.gone)
    }

    @Test
    fun `saved positions reach Watch Next with the played video's title, duration and still`() = runTest {
        val recorder = FakeWatchNextRecorder()
        val session = checkNotNull(
            TvSessionViewModel(auth).sessionFor(
                account(),
                TvAuthSessionId(1),
                dependencies.copy(watchNext = { _, _ -> recorder }),
            ),
        )
        val video = media(9, "clip.mp4", PutioFileType.VIDEO).copy(
            playback = FilesPlaybackProgress(0.0, 600.0),
            screenshotUrl = "https://api.put.io/screenshots/9.jpg",
        )

        session.play(video)
        session.stopPlayback()
        // The exit write lands after playback has gone.
        session.watchNextPlayback.saved(9L, 120.0)
        session.watchNextPlayback.saved(10L, 30.0)
        session.setWatched(video, watched = true)

        val media = TvWatchNextMedia(9L, "clip.mp4", 600.0, "https://api.put.io/screenshots/9.jpg", isVideo = true)
        assertEquals(listOf(media to 120.0, media to 600.0), recorder.saved)
    }

    @Test
    fun `a video played on TV joins the account's Continue watching and marking it watched removes it`() = runTest {
        val stores = mutableMapOf<Long, TvMemoryContinueWatchingStore>()
        val session = checkNotNull(
            TvSessionViewModel(auth).sessionFor(
                account(),
                TvAuthSessionId(1),
                dependencies.copy(
                    continueWatchingStore = { userId -> stores.getOrPut(userId) { TvMemoryContinueWatchingStore() } },
                ),
            ),
        )
        val video = media(9, "clip.mp4", PutioFileType.VIDEO).copy(playback = FilesPlaybackProgress(0.0, 600.0))

        session.play(video)
        session.stopPlayback()
        session.positionSaved(9L, 120.0)

        val entry = session.continueWatching.entries.value.single()
        assertEquals("clip.mp4" to 480L, entry.name to entry.remainingSeconds)
        assertEquals(listOf(entry), stores.getValue(42L).read())

        session.setWatched(video, watched = true)
        assertTrue(session.continueWatching.entries.value.isEmpty())
    }

    @Test
    fun `deleting a folder in Files takes its videos out of Continue watching`() = runTest {
        var deleted = false
        rootPages = listOf(FilesPage(listOf(media(30, "Season 1", PutioFileType.FOLDER)), nextCursor = null))
        val session = checkNotNull(
            TvSessionViewModel(auth).sessionFor(
                account(),
                TvAuthSessionId(1),
                dependencies.copy(
                    filesRepository = object : StubFilesRepository() {
                        override suspend fun loadFolder(folderId: FilesItemId) = PutioResult.Success(rootPages.single())

                        override suspend fun delete(itemIds: List<FilesItemId>, mode: FilesDeleteMode) =
                            PutioResult.Success(FileDeleteResult(status = "OK")).also { deleted = true }

                        override suspend fun resolveItem(itemId: FilesItemId) = PutioResult.Failure(notFound())
                    },
                    filesItemResolver = object : FilesItemResolver {
                        override suspend fun resolveItem(itemId: FilesItemId): PutioResult<FilesItem> =
                            if (deleted) {
                                PutioResult.Failure(notFound())
                            } else {
                                PutioResult.Success(media(itemId.value, "clip.mp4", PutioFileType.VIDEO))
                            }
                    },
                ),
            ),
        )
        val video = media(9, "clip.mp4", PutioFileType.VIDEO).copy(playback = FilesPlaybackProgress(0.0, 600.0))
        session.play(video)
        session.stopPlayback()
        session.positionSaved(9L, 120.0)
        assertEquals(listOf(FilesItemId(9)), session.continueWatching.entries.value.map { it.fileId })

        session.files.dispatch(FilesBrowserEvent.Delete(FilesFolder.Root.id, FilesItemId(30), FilesDeleteMode.TRASH))

        assertTrue(session.continueWatching.entries.value.isEmpty())
    }

    @Test
    fun `moving a file to Trash from Files reloads a Trash listing already open`() = runTest {
        var trashLoads = 0
        rootPages = listOf(FilesPage(listOf(media(30, "clip.mp4", PutioFileType.VIDEO)), nextCursor = null))
        val session = checkNotNull(
            TvSessionViewModel(auth).sessionFor(
                account(),
                TvAuthSessionId(1),
                dependencies.copy(
                    filesRepository = object : StubFilesRepository() {
                        override suspend fun loadFolder(folderId: FilesItemId) = PutioResult.Success(rootPages.single())

                        override suspend fun delete(itemIds: List<FilesItemId>, mode: FilesDeleteMode) =
                            PutioResult.Success(FileDeleteResult(status = "OK"))

                        override suspend fun resolveItem(itemId: FilesItemId) = PutioResult.Failure(notFound())
                    },
                    trashRepository = object : TrashRepository by StubTrashRepository {
                        override suspend fun load() = StubTrashRepository.load().also { trashLoads++ }
                    },
                ),
            ),
        )
        session.trash.dispatch(TrashEvent.Open)
        assertEquals(1, trashLoads)

        session.files.dispatch(FilesBrowserEvent.Delete(FilesFolder.Root.id, FilesItemId(30), FilesDeleteMode.TRASH))

        assertEquals(2, trashLoads)
    }

    @Test
    fun `leaving a video started from Home hands Home the file that played last`() {
        val session = checkNotNull(TvSessionViewModel(auth).sessionFor(account(), TvAuthSessionId(1), dependencies))
        val video = media(9, "clip.mp4", PutioFileType.VIDEO).copy(playback = FilesPlaybackProgress(0.0, 600.0))

        session.openExternal(video, FilesOpenOrigin.HOME)
        session.stopPlayback()
        assertEquals(FilesItemId(9), session.homeReturn.file.value)

        session.homeReturn.handled()
        session.play(video)
        session.stopPlayback()
        assertEquals(null, session.homeReturn.file.value)
    }

    @Test
    fun `Show Home is kept per account on this TV`() {
        val store = TvShowHomeStore.Memory()
        val session = checkNotNull(
            TvSessionViewModel(auth).sessionFor(
                account(),
                TvAuthSessionId(1),
                dependencies.copy(showHomeStore = { store }),
            ),
        )
        assertTrue(session.showHome.shown.value)

        session.showHome.set(false)

        assertFalse(session.showHome.shown.value)
        assertFalse(store.read())
    }

    @Test
    fun `each session reconciles its Watch Next cards and a 401 there is the session's verdict`() = runTest {
        val rejected = PutioFailure.AuthenticationRequired(PutioConfigurationException("401"))
        val recorder = FakeWatchNextRecorder(verdict = rejected)
        val session = checkNotNull(
            TvSessionViewModel(auth).sessionFor(
                account(),
                TvAuthSessionId(1),
                dependencies.copy(watchNext = { userId, _ -> recorder.also { it.userId = userId } }),
            ),
        )

        assertEquals(42L, recorder.userId)
        assertEquals(listOf("resolved-55"), recorder.reconciled)
        assertEquals(rejected, session.fileActionFailure.value)
    }

    private class FakeWatchNextRecorder(
        private val verdict: PutioFailure.AuthenticationRequired? = null,
    ) : TvWatchNextRecorder {
        var userId: Long? = null
        val saved = mutableListOf<Pair<TvWatchNextMedia, Double>>()
        val gone = mutableListOf<Long>()
        val reconciled = mutableListOf<String>()

        override fun positionSaved(media: TvWatchNextMedia, positionSeconds: Double) {
            saved += media to positionSeconds
        }

        override fun fileGone(fileId: Long) {
            gone += fileId
        }

        override suspend fun reconcile(
            resolve: suspend (FilesItemId) -> PutioResult<FilesItem>,
        ): PutioFailure.AuthenticationRequired? {
            (resolve(FilesItemId(55)) as? PutioResult.Success)?.let { reconciled += it.value.name }
            return verdict
        }
    }

    private fun media(id: Long, name: String, type: PutioFileType) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesItemId(0L),
        name = name,
        type = type,
        sizeBytes = 1L,
        createdAt = "2026-04-20T10:00:00Z",
    )

    private fun notFound(): PutioFailure = PutioFailure.ApiRejected(
        statusCode = 404,
        errorType = "NotFound",
        cause = PutioApiException(
            request = PutioRequestData("GET", "https://api.put.io/v2/files/9"),
            resolvedStatusCode = 404,
            httpStatusCode = 404,
            resolvedErrorType = null,
            envelope = PutioApiErrorEnvelope(statusCode = 404, errorType = null),
            responseBody = "{}",
            message = "Not found",
        ),
    )

    private fun account(userId: Long = 42) =
        TvAccount(userId = userId, username = "u", email = "u@example.com", historyEnabled = true)

    private fun signedIn(session: Long) = TvAuthState.SignedIn(account(), TvAuthSessionId(session))

    private object StubTrashRepository : TrashRepository {
        override suspend fun load() = PutioResult.Success(TrashPage(emptyList(), nextCursor = null))
        override suspend fun loadNextPage(cursor: FilesCursor) = error("No continuation expected")
        override suspend fun restore(itemId: FilesItemId) = PutioResult.Success(Unit)
        override suspend fun resolveItem(itemId: FilesItemId) = error("No check expected")
        override suspend fun deleteItem(itemId: FilesItemId) = PutioResult.Success(Unit)
        override suspend fun restoreAll(selection: TrashBulkSelection) = PutioResult.Success(Unit)
        override suspend fun empty() = PutioResult.Success(Unit)
    }

    private class FakeWatchedRepository : FilesWatchedRepository {
        val calls = mutableListOf<Pair<FilesItemId, Double?>>()
        var failure: PutioFailure? = null

        override suspend fun setPosition(itemId: FilesItemId, seconds: Double): PutioResult<Unit> {
            calls += itemId to seconds
            return failure?.let { PutioResult.Failure(it) } ?: PutioResult.Success(Unit)
        }

        override suspend fun clearPosition(itemId: FilesItemId): PutioResult<Unit> {
            calls += itemId to null
            return failure?.let { PutioResult.Failure(it) } ?: PutioResult.Success(Unit)
        }
    }

    private object StubAccountSettingsRepository : AccountSettingsRepository {
        override suspend fun load() = AccountSettingsRepositoryResult.Success(
            AccountSettingsPreferences(
                historyEnabled = true,
                trashEnabled = true,
                showSubtitles = true,
                autoSelectSubtitles = true,
            ),
        )

        override suspend fun save(change: AccountSettingsChange) = AccountSettingsRepositoryResult.Success(Unit)

        override suspend fun loadTunnelRoutes() = error("No route list expected")
    }

    private class FakePlaybackRepository : PlaybackRepository {
        val resolved = mutableListOf<PlaybackTarget>()
        var preference: () -> PlaybackPreference = { error("No preference wired") }
        val preferencesSeen = mutableListOf<PlaybackPreference>()

        /** The account's saved position for every file, with resume playback on when positive. */
        var savedSeconds = 0.0

        override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
            resolved += target
            preferencesSeen += preference()
            return PlaybackRepositoryResult.Success(
                PlaybackResolution.Ready(source(target.fileId.value), useStartFrom = savedSeconds > 0.0),
            )
        }

        /** The video after each one in its folder; anything else is the folder's last. */
        val next = mutableMapOf<FilesItemId, PlaybackTarget>()

        override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
            next[target.fileId]?.let(PlaybackNextResult::Found) ?: PlaybackNextResult.Ended

        private fun source(fileId: Long) = PlaybackSource(
            fileId = fileId,
            kind = PlaybackSourceKind.HLS,
            url = PutioCredentialUrl::class.java
                .getDeclaredConstructor(String::class.java)
                .newInstance("https://api.put.io/v2/files/$fileId/hls/media.m3u8?token=t"),
            startFromSeconds = savedSeconds,
            subtitles = PlaybackSubtitles.None,
        )
    }

    private class FakeRecentSearchStore : RecentSearchStoreOwner {
        override val terms = MutableStateFlow<List<SearchTerm>>(emptyList())
        override val enabled = MutableStateFlow<Boolean?>(true)
        override val failure = MutableStateFlow<PutioFailure?>(null)
        var closed = false

        override fun record(term: SearchTerm) {
            terms.value = listOf(term) + terms.value.filterNot { it == term }
        }

        override fun remove(term: SearchTerm) {
            terms.value = terms.value.filterNot { it == term }
        }

        override fun clear() {
            terms.value = emptyList()
        }

        override fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }

        override fun retry() = Unit

        override fun close() {
            closed = true
        }
    }

    private val streamItem = media(9, "clip.mp4", PutioFileType.VIDEO)
}
