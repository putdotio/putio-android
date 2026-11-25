package io.putdotio.android.tv.player

import android.content.Intent
import android.media.session.MediaController as PlatformMediaController
import android.media.session.PlaybackState as PlatformPlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.tv.TvProofActivity
import io.putdotio.android.tv.TvSessionKey
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvBackgroundAudioProofTest {
    private val emptyCompose = createEmptyComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(
                    "TV background audio proof requires opt-in",
                    InstrumentationRegistry.getArguments().getString(ENABLED) == "true",
                )
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(emptyCompose)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        runOnMain { TvPlaybackService.resetForTests() }
    }

    @Test
    fun audioSessionPublishesPlayingPositionAndPausedStateToPlatformMediaSession() {
        val wav = File(context.cacheDir, "proof-879-track.wav").also { writeToneWav(it, 10, 440) }
        var player: Player? = null
        var session: MediaSession? = null
        try {
            runOnMain {
                val created = DefaultTvPlayerFactory.create(context, PlaybackMediaType.AUDIO).apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(wav)))
                    playWhenReady = true
                    prepare()
                }
                player = created
                session = tvMediaSession(
                    context,
                    tvSessionPlayer(
                        created,
                        onSkipRequested = {},
                        onPlayingRequested = { created.playWhenReady = it },
                    ),
                ).build()
            }
            val activeSession = requireNotNull(session)
            val platform = PlatformMediaController(context, activeSession.platformToken)
            waitUntil(10_000L) {
                platform.playbackState?.state == PlatformPlaybackState.STATE_PLAYING
            }
            runOnMain { requireNotNull(player).seekTo(2_000L) }
            waitUntil(5_000L) {
                val state = platform.playbackState
                state != null && state.state == PlatformPlaybackState.STATE_PLAYING && state.position >= 2_000L
            }
            val playingDump = sessionBlock(shell("dumpsys media_session"), activeSession.id)
            Log.i("TvBgAudioProof", "879_PLAYING:\n$playingDump")
            assertTrue(
                "Expected PLAYING(3) in:\n$playingDump",
                playingDump.hasDumpsysPlaybackState("PLAYING", PlatformPlaybackState.STATE_PLAYING),
            )
            val playingPos = Regex("""position=(\d+)""").find(playingDump)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            assertTrue("Expected position >= 2000 in:\n$playingDump", playingPos >= 2_000L)

            runOnMain { requireNotNull(player).pause() }
            waitUntil(5_000L) {
                val state = platform.playbackState
                state != null &&
                    state.state == PlatformPlaybackState.STATE_PAUSED &&
                    state.playbackSpeed == 0f &&
                    state.position >= 2_000L
            }
            val pausedDump = sessionBlock(shell("dumpsys media_session"), activeSession.id)
            Log.i("TvBgAudioProof", "879_PAUSED:\n$pausedDump")
            assertTrue(
                "Expected PAUSED(2) in:\n$pausedDump",
                pausedDump.hasDumpsysPlaybackState("PAUSED", PlatformPlaybackState.STATE_PAUSED),
            )
        } finally {
            runOnMain {
                session?.release()
                player?.release()
            }
        }
    }

    @Test
    fun backgroundAudioSurvivesActivityDestructionAdvancesToNextTrackAndReattaches() {
        val firstFile = File(context.cacheDir, "proof-880-track-1.wav").also { writeToneWav(it, 2, 440) }
        val secondFile = File(context.cacheDir, "proof-880-track-2.wav").also { writeToneWav(it, 12, 660) }
        val firstTarget = PlaybackTarget(FilesItemId(101L), "01 First.wav", PlaybackMediaType.AUDIO)
        val secondTarget = PlaybackTarget(FilesItemId(102L), "02 Second.wav", PlaybackMediaType.AUDIO)
        val repo = localQueueRepository(firstTarget to firstFile, secondTarget to secondFile)
        val firstScenario = ActivityScenario.launch(TvProofActivity::class.java)
        var secondScenario: ActivityScenario<TvProofActivity>? = null
        try {
            val controller = runOnMainSyncResult {
                TvPlaybackService.audioHost.start(
                    TvAudioStartRequest(
                        identity = TvAudioSessionIdentity(
                            sessionKey = TvSessionKey(42L, TvAuthSessionId(1L)),
                            authState = MutableStateFlow(
                                TvAuthState.SignedIn(
                                    TvAccount(
                                        userId = 42L,
                                        username = "devs",
                                        email = "devs@put.io",
                                        historyEnabled = true,
                                    ),
                                    TvAuthSessionId(1L),
                                ),
                            ),
                            application = context.applicationContext,
                        ),
                        media = TvAudioMedia(
                            target = firstTarget,
                            startItem = FilesItem(
                                id = firstTarget.fileId,
                                parentId = FilesItemId(0L),
                                name = firstTarget.name,
                                type = PutioFileType.AUDIO,
                                sizeBytes = firstFile.length(),
                                createdAt = "2026-04-20T10:00:00Z",
                            ),
                            fromHome = false,
                            startAnswer = PlaybackEvent.Resume,
                        ),
                        callbacks = TvAudioSessionCallbacks(
                            playbackRepository = repo,
                            settings = MutableStateFlow(AccountSettingsReducer.start().state),
                            writePlaybackPosition = { _, _ -> PlaybackRepositoryResult.Success(Unit) },
                            onSaved = { _, _ -> },
                            onAuthenticationRejected = {},
                        ),
                    ),
                )
            }
            firstScenario.onActivity { activity ->
                activity.setContent {
                    PutioTvTheme {
                        TvPlaybackRoute(
                            controller = controller,
                            onExit = {},
                            onSessionRejected = {},
                            findUpNext = repo::findNeighbours,
                            onBackground = {},
                        )
                    }
                }
            }
            waitUntil(10_000L) {
                runOnMainSyncResult {
                    val player = TvBackgroundSessions.hosted?.player
                    player != null &&
                        player.currentMediaItem?.mediaId == "101" &&
                        player.playbackState == Player.STATE_READY &&
                        player.playWhenReady
                }
            }
            val initialSession = requireNotNull(TvBackgroundSessions.hosted)

            firstScenario.close()

            waitUntil(12_000L) {
                runOnMainSyncResult {
                    val session = TvBackgroundSessions.hosted
                    session === initialSession &&
                        session.player.currentMediaItem?.mediaId == "102" &&
                        session.player.playbackState == Player.STATE_READY &&
                        session.player.playWhenReady
                }
            }
            val dump = sessionBlock(shell("dumpsys media_session"), initialSession.id)
            val afterDestroyLine = dump.lines().firstOrNull {
                it.hasDumpsysPlaybackState("PLAYING", PlatformPlaybackState.STATE_PLAYING)
            }
            assertNotNull("Expected PLAYING(3) after activity destruction:\n$dump", afterDestroyLine)
            val serviceComponent = "${context.packageName}/${TvPlaybackService::class.java.name}"
            waitUntil(5_000L) {
                shell("dumpsys activity services $serviceComponent").contains("isForeground=true")
            }
            Log.i("TvBgAudioProof", "880_AFTER_DESTROY: $afterDestroyLine")

            val relaunched = ActivityScenario.launch(TvProofActivity::class.java)
            secondScenario = relaunched
            relaunched.onActivity { activity ->
                val activeController = requireNotNull(TvPlaybackService.audioHost.active.value?.controller)
                activity.setContent {
                    PutioTvTheme {
                        TvPlaybackRoute(
                            controller = activeController,
                            onExit = {},
                            onSessionRejected = {},
                            findUpNext = repo::findNeighbours,
                            onBackground = {},
                        )
                    }
                }
            }
            emptyCompose.waitUntil(5_000L) {
                emptyCompose.onAllNodesWithText(secondTarget.name).fetchSemanticsNodes().isNotEmpty()
            }
            val finalHosted = TvBackgroundSessions.hosted
            assertSame(initialSession, finalHosted)
            assertEquals("102", runOnMainSyncResult { finalHosted?.player?.currentMediaItem?.mediaId })
        } finally {
            firstScenario.close()
            secondScenario?.close()
        }
    }

    @Test
    fun coldMediaButtonStartWithoutHostedSessionDoesNotCrashService() {
        val serviceComponent = "${context.packageName}/${TvPlaybackService::class.java.name}"
        runOnMain { TvPlaybackService.resetForTests() }
        waitUntil(5_000L) {
            !shell("dumpsys activity services $serviceComponent").contains("isForeground=true")
        }

        val intent = Intent(context, TvPlaybackService::class.java)
            .setAction(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        ContextCompat.startForegroundService(context, intent)

        SystemClock.sleep(6_000L)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { latch.countDown() }
        assertTrue("Main thread stalled after cold ACTION_MEDIA_BUTTON start", latch.await(5, TimeUnit.SECONDS))
        val services = shell("dumpsys activity services $serviceComponent")
        assertFalse("Service remained foregrounded unexpectedly:\n$services", services.contains("isForeground=true"))
    }

    private fun localQueueRepository(
        first: Pair<PlaybackTarget, File>,
        second: Pair<PlaybackTarget, File>,
    ): PlaybackRepository = object : PlaybackRepository {
        override suspend fun resolve(
            target: PlaybackTarget,
            convertedOnly: Boolean,
        ): PlaybackRepositoryResult<PlaybackResolution> {
            val file = if (target.fileId == first.first.fileId) first.second else second.second
            val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
                .newInstance(Uri.fromFile(file).toString())
            val source = PlaybackSource(
                fileId = target.fileId.value,
                kind = PlaybackSourceKind.ORIGINAL,
                url = url,
                startFromSeconds = 0.0,
                subtitles = PlaybackSubtitles.None,
            )
            return PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source, useStartFrom = false))
        }

        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended

        override suspend fun findNeighbours(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackNeighbours> {
            val next = if (target.fileId == first.first.fileId) second.first else null
            val prev = if (target.fileId == second.first.fileId) first.first else null
            return PlaybackRepositoryResult.Success(PlaybackNeighbours(prev, next, "Proof Album"))
        }
    }

    private fun writeToneWav(file: File, durationSeconds: Int, frequencyHz: Int) {
        val sampleRate = 44_100
        val totalSamples = sampleRate * durationSeconds
        val dataSize = totalSamples * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun writeIntLE(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        fun writeShortLE(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        writeIntLE(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(1)
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * 2)
        writeShortLE(2)
        writeShortLE(16)
        out.write("data".toByteArray())
        writeIntLE(dataSize)
        val step = 2.0 * PI * frequencyHz / sampleRate
        for (i in 0 until totalSamples) {
            val sample = (sin(i * step) * 8_000.0).roundToInt()
            writeShortLE(sample)
        }
        file.writeBytes(out.toByteArray())
    }

    private fun sessionBlock(dump: String, tag: String): String {
        val lines = dump.lines()
        val start = lines.indexOfLast { it.contains(tag) }
        if (start < 0) return ""
        val headerIndent = lines[start].takeWhile { it == ' ' }.length
        val tail = lines.drop(start + 1).takeWhile { line ->
            line.isNotBlank() && line.takeWhile { it == ' ' }.length > headerIndent
        }
        return (listOf(lines[start]) + tail).joinToString("\n")
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun runOnMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun <T> runOnMainSyncResult(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100L)
        }
        assertTrue("Timed out after ${timeoutMillis}ms", predicate())
    }

    private companion object {
        const val ENABLED = "putio.tv.backgroundAudio.enabled"
    }
}

private fun String.hasDumpsysPlaybackState(name: String, code: Int): Boolean =
    contains("state=$name($code)") || contains("state=$code,")
