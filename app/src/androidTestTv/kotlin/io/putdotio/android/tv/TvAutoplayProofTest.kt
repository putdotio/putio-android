package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.TvSessionShell
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesItemResolver
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesRepositoryResult
import io.putdotio.android.files.FilesStreamUrls
import io.putdotio.android.files.FilesWatchedRepository
import io.putdotio.android.history.HistoryEventId
import io.putdotio.android.history.HistoryPage
import io.putdotio.android.history.HistoryRepository
import io.putdotio.android.history.HistoryRepositoryResult
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.confirmedAutoplayNextVideo
import io.putdotio.android.search.SearchPage
import io.putdotio.android.search.SearchRepository
import io.putdotio.android.search.SearchTerm
import io.putdotio.android.files.FilesCursor
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsRepository
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigRepository
import io.putdotio.android.settings.AndroidAppConfigRepositoryResult
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.confirmedResumePlayback
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.player.TV_PLAYER_TAG
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Controlled-state proof of Autoplay next video on the real signed-in TV shell: fake
 * repositories stand in for an account with the setting and resume on, the TV session, shell
 * and ExoPlayer are the production ones, and each video plays a caller-owned local fixture to
 * its end. No API calls.
 */
@RunWith(AndroidJUnit4::class)
class TvAutoplayProofTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(
                    "TV autoplay proof requires opt-in",
                    arguments.getString("putio.tv.autoplay.enabled") == "true",
                )
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    private val lookups: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val writes: MutableList<Pair<Long, Double>> = Collections.synchronizedList(mutableListOf())
    private val saved = Collections.synchronizedMap(mutableMapOf(SECOND_ID to SECOND_SAVED_SECONDS))

    @Test
    fun aFinishedVideoPlaysTheNextInItsFolderAndLeavingFocusesItsRow() {
        val session = mount()
        compose.waitUntil(5_000) { isFocused("Play $FIRST") }
        screenshot("01-first-row-focused")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(TV_PLAYER_TAG).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(PLAY_MILLIS)
        screenshot("02-first-playing")

        // The first video plays to its end; the next in the folder has a saved position, so it asks.
        compose.waitUntil(FIXTURE_MILLIS * 3) {
            compose.onAllNodesWithText(SECOND_CONTINUE_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull("Autoplay stays in playback", compose.runOnIdle { session.playback.value })
        compose.onNodeWithText(SECOND).assertExists()
        compose.onNodeWithText(SECOND_CONTINUE_LABEL).assertIsFocused()
        assertEquals(listOf(FIRST_ID), lookups.toList())
        val firstEnd = writes.toList().last { it.first == FIRST_ID }.second
        assertTrue("The finished video's end is written: $firstEnd s", firstEnd >= FIXTURE_SECONDS - 1.0)
        screenshot("03-next-resume-prompt")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(TV_PLAYER_TAG).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(PLAY_MILLIS)
        screenshot("04-next-playing")

        // Back hides the controls, then leaves; Files focuses the video that played last.
        press(KeyEvent.KEYCODE_BACK)
        if (compose.runOnIdle { session.playback.value != null }) press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.runOnIdle { session.playback.value == null } }
        compose.waitUntil(10_000) { isFocused("Play $SECOND") }
        screenshot("05-back-on-the-autoplayed-row")

        // The folder's last video leaves playback when it ends, back on its row.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(RESUME_PREFIX, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(TV_PLAYER_TAG).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(PLAY_MILLIS)
        screenshot("06-last-playing")
        compose.waitUntil(FIXTURE_MILLIS * 3) { compose.runOnIdle { session.playback.value == null } }
        assertEquals(listOf(FIRST_ID, SECOND_ID), lookups.toList())
        compose.waitUntil(10_000) { isFocused("Play $SECOND") }
        screenshot("07-folder-end-back-on-its-row")
    }

    @Test
    fun withTheSettingOffAFinishedVideoLeavesPlayback() {
        val session = mount(autoplay = false)
        compose.waitUntil(5_000) { isFocused("Play $FIRST") }

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(TV_PLAYER_TAG).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(PLAY_MILLIS)
        screenshot("off-01-first-playing")

        compose.waitUntil(FIXTURE_MILLIS * 3) { compose.runOnIdle { session.playback.value == null } }
        assertEquals("No next video is looked up", emptyList<Long>(), lookups.toList())
        compose.waitUntil(10_000) { isFocused("Play $FIRST") }
        screenshot("off-02-back-on-its-row")
    }

    private fun mount(autoplay: Boolean = true): TvSession {
        val account = TvAccount(userId = 1, username = "proof", email = "proof@example.invalid", historyEnabled = true)
        val auth = MutableStateFlow<TvAuthState>(TvAuthState.SignedIn(account, TvAuthSessionId(1)))
        lateinit var session: TvSession
        compose.runOnUiThread {
            session = checkNotNull(
                TvSessionViewModel(auth).sessionFor(account, TvAuthSessionId(1), dependencies(autoplay)),
            )
        }
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvSessionShell(
                    session = session,
                    account = account,
                    sessionKey = 1L,
                    onSignOut = {},
                    onSessionRejected = { error("Unexpected rejection") },
                    loadTunnelRoutes = { AccountSettingsRepositoryResult.Success(emptyList<TunnelRouteOption>()) },
                )
            }
        }
        compose.waitUntil(5_000) {
            compose.runOnIdle {
                session.appConfig.state.value.confirmedPreferences != null &&
                    session.appConfig.state.value.confirmedAutoplayNextVideo() == autoplay &&
                    session.settings.state.value.confirmedResumePlayback() == true
            }
        }
        return session
    }

    private fun dependencies(autoplay: Boolean): TvSessionDependencies {
        val first = row(FIRST_ID, FIRST)
        val second = row(SECOND_ID, SECOND)
        val listings = mapOf(
            FilesFolder.Root.id to FilesPage(listOf(first, second, row(3, "notes.txt", PutioFileType.TEXT)), null),
        )
        val source = localSource()
        return TvSessionDependencies(
            filesRepository = ProofFilesRepository(listings),
            searchRepository = object : SearchRepository {
                override suspend fun search(term: SearchTerm) = error("No search")

                override suspend fun loadNextPage(cursor: FilesCursor) = error("No search")
            },
            historyRepository = object : HistoryRepository {
                override suspend fun load(before: HistoryEventId?) =
                    HistoryRepositoryResult.Success(HistoryPage(emptyList(), hasMore = false))

                override suspend fun clear() = HistoryRepositoryResult.Success(Unit)
            },
            trashRepository = ProofTrashRepository,
            settingsRepository = object : AccountSettingsRepository {
                override suspend fun load() = AccountSettingsRepositoryResult.Success(
                    AccountSettingsPreferences(
                        historyEnabled = true,
                        trashEnabled = true,
                        showSubtitles = true,
                        autoSelectSubtitles = true,
                        resumePlayback = true,
                    ),
                )

                override suspend fun save(change: AccountSettingsChange) = AccountSettingsRepositoryResult.Success(Unit)

                override suspend fun loadTunnelRoutes() =
                    AccountSettingsRepositoryResult.Success(emptyList<TunnelRouteOption>())
            },
            appConfigRepository = object : AndroidAppConfigRepository {
                override suspend fun load() =
                    AndroidAppConfigRepositoryResult.Success(AndroidAppConfigPreferences(autoplayNextVideo = autoplay))

                override suspend fun save(change: AndroidAppConfigChange) = AndroidAppConfigRepositoryResult.Success(Unit)
            },
            watchedRepository = object : FilesWatchedRepository {
                override suspend fun setPosition(itemId: FilesItemId, seconds: Double) =
                    FilesRepositoryResult.Success(Unit)

                override suspend fun clearPosition(itemId: FilesItemId) = FilesRepositoryResult.Success(Unit)
            },
            streamUrls = FilesStreamUrls { null },
            filesItemResolver = object : FilesItemResolver {
                override suspend fun resolveItem(itemId: FilesItemId) = error("No history rows")
            },
            recentSearchStore = { ProofRecentSearchStore() },
            playbackRepository = {
                object : PlaybackRepository {
                    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
                        val id = target.fileId.value
                        return PlaybackRepositoryResult.Success(
                            PlaybackResolution.Ready(
                                source.copy(fileId = id, startFromSeconds = saved[id] ?: 0.0),
                                useStartFrom = true,
                            ),
                        )
                    }

                    // The folder read in name order, with the listing's duration.
                    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult {
                        lookups += target.fileId.value
                        return if (target.fileId.value == FIRST_ID) {
                            PlaybackNextResult.Found(PlaybackTarget(second.id, SECOND, durationSeconds = FIXTURE_SECONDS))
                        } else {
                            PlaybackNextResult.Ended
                        }
                    }
                }
            },
            writePlaybackPosition = { fileId, seconds ->
                writes += fileId to seconds
                saved[fileId] = seconds
                PlaybackRepositoryResult.Success(Unit)
            },
        )
    }

    private fun isFocused(label: String) = compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes()
        .any { it.config.getOrNull(SemanticsProperties.Focused) == true }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
        Thread.sleep(STEP_MILLIS)
    }

    /**
     * Copies the fixture adb pushed into the app's own files directory: a directory adb creates
     * under `Android/data` is not readable to the app, and the app cannot read `/data/local/tmp`.
     */
    private fun localSource(): PlaybackSource {
        val pushed = requireNotNull(arguments.getString("putio.tv.autoplay.fixture"))
        val file = File(requireNotNull(context.getExternalFilesDir(null)), "tv-autoplay-fixture.mp4")
        val copy = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp $pushed ${file.absolutePath}")
        ParcelFileDescriptor.AutoCloseInputStream(copy).use { it.readBytes() }
        require(file.isFile && file.canRead()) { "Fixture is not readable: $file (pushed as $pushed)" }
        // The SDK owns production URLs. This proof reads only its caller-owned local fixture.
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        return PlaybackSource(
            fileId = FIRST_ID,
            kind = PlaybackSourceKind.ORIGINAL,
            url = url,
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-autoplay-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.autoplay.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun row(id: Long, name: String, type: PutioFileType = PutioFileType.VIDEO) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesFolder.Root.id,
        name = name,
        type = type,
        sizeBytes = 1_048_576L,
        createdAt = "2026-09-30T10:00:00Z",
        playback = if (type == PutioFileType.VIDEO) FilesPlaybackProgress(saved[id] ?: 0.0, FIXTURE_SECONDS) else null,
    )

    private companion object {
        const val FIRST_ID = 9_360_001L
        const val SECOND_ID = 9_360_002L
        const val FIRST = "Harbor film 1.mp4"
        const val SECOND = "Harbor film 2.mp4"
        const val FIXTURE_SECONDS = 30.0
        const val FIXTURE_MILLIS = 30_000L
        const val SECOND_SAVED_SECONDS = 5.0
        const val SECOND_CONTINUE_LABEL = "Continue playing from 00:05"
        const val RESUME_PREFIX = "Continue playing from"
        const val STEP_MILLIS = 400L
        const val PLAY_MILLIS = 2_000L
    }
}
