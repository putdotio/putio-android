package io.putdotio.android.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.MaterialTheme
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.TvFilesScreen
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Controlled-state TV playback proof: a fixed Files listing, the real TV player screen and
 * ExoPlayer streaming a caller-owned local HLS or MP4 fixture, driven with D-pad key events.
 * No API calls; the listing and the resolved source stand in for a signed-in session.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlayerProofTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(
                    "TV player proof requires opt-in",
                    arguments.getString("putio.tv.player.enabled") == "true",
                )
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun selectPlaysTheFixtureAndBackReturnsToItsRow() {
        val source = localSource()
        val factory = ProofPlayerFactory()
        val video = row(FIXTURE_FILE_ID, FIXTURE_TITLE, PutioFileType.VIDEO)
        val files = FilesBrowserState(
            stack = listOf(
                FilesFolderState(
                    FilesFolder.Root,
                    FilesContent.Ready(
                        listOf(row(1, "Documents", PutioFileType.FOLDER), video, row(3, "notes.txt", PutioFileType.TEXT)),
                        FilesPaging.Complete,
                    ),
                ),
            ),
            nextRequestValue = 1L,
        )
        val focusMemory = mutableMapOf<Long, Long>()
        var playing by mutableStateOf<FilesItem?>(null)
        compose.setContent {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlaybackLayer(
                    playing = playing != null,
                    player = {
                        val item = checkNotNull(playing)
                        TvPlayerScreen(
                            state = PlaybackState(
                                target = PlaybackTarget(item.id, item.name, PlaybackMediaType.VIDEO),
                                content = PlaybackContent.Ready(source),
                                nextRequestValue = 2L,
                            ),
                            onBack = { playing = null },
                            onRetry = { error("Unexpected retry") },
                            onResume = { error("Unexpected resume") },
                            onPlayerFailure = { failure, _ -> error("Local playback failed: $failure") },
                            playerFactory = factory,
                        )
                    },
                ) {
                    TvShell(
                        account = TvAccount(userId = 1, username = "proof", email = "proof@example.invalid"),
                        onSignOut = {},
                        filesPane = { paneFocus ->
                            TvFilesScreen(
                                state = files,
                                onEvent = { true },
                                onPlayMedia = { playing = it },
                                modifier = Modifier.focusRequester(paneFocus),
                                focusMemory = focusMemory,
                            )
                        },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Open Documents").assertIsFocused()
        pause()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithContentDescription("Play $FIXTURE_TITLE").assertIsFocused()
        screenshot("01-files-row-focused")
        pause()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) {
            it.playbackState == Player.STATE_READY && it.isPlaying && factory.renderedFrame &&
                it.currentPosition > 1_000L && it.videoSize.width > 0
        }
        compose.onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
        screenshot("02-playing-controls")
        // Controls hide three seconds into playback.
        elapse(TV_PLAYER_CONTROLS_HIDE_DELAY_MILLIS + 1_500L)
        compose.onNodeWithTag(TV_PLAYER_CONTROLS_TAG).assertDoesNotExist()
        screenshot("03-playing-clean")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { !it.playWhenReady }
        compose.onNodeWithContentDescription("Paused").assertExists()
        screenshot("04-paused")
        pause()

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlayer(factory) { it.isPlaying }
        pause()

        val player = factory.current()
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.runOnIdle { playing == null } }
        compose.onNodeWithContentDescription("Play $FIXTURE_TITLE").assertIsFocused()
        compose.runOnIdle {
            assertNull(playing)
            assertTrue("Leaving playback releases the player", factory.released(player))
        }
        screenshot("05-back-on-files-row")
        pause()
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
    }

    /** Lets a concurrent screen recording show each step. */
    private fun pause() = elapse(STEP_PAUSE_MILLIS)

    /**
     * Waits in real time while keeping the rule's virtual clock in step, so the player's
     * own timers (auto-hide, position polling) run as they would outside a test.
     */
    private fun elapse(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            val step = minOf(ELAPSE_STEP_MILLIS, remaining)
            Thread.sleep(step)
            compose.mainClock.advanceTimeBy(step)
            remaining -= step
        }
    }

    private fun awaitPlayer(factory: ProofPlayerFactory, predicate: (Player) -> Boolean) {
        compose.waitUntil(30_000) {
            compose.runOnIdle {
                factory.player?.let {
                    check(it.playerError == null) { "Playback failed: ${it.playerError?.errorCodeName}" }
                    predicate(it)
                } == true
            }
        }
    }

    private fun localSource(): PlaybackSource {
        val file = File(requireNotNull(arguments.getString("putio.tv.player.fixture"))).canonicalFile
        require(file.isFile && file.canRead()) { "Fixture is not readable: $file" }
        require(file.toPath().startsWith(requireNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        // The SDK owns production URLs. This proof reads only its caller-owned local fixture.
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        return PlaybackSource(
            fileId = FIXTURE_FILE_ID,
            kind = if (file.extension == "m3u8") PlaybackSourceKind.HLS else PlaybackSourceKind.ORIGINAL,
            url = url,
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )
    }

    private fun row(id: Long, name: String, type: PutioFileType) = FilesItem(
        id = FilesItemId(id),
        parentId = FilesFolder.Root.id,
        name = name,
        type = type,
        sizeBytes = 1L,
        createdAt = "2026-09-30T10:00:00Z",
    )

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-player-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.player.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val FIXTURE_FILE_ID = 9_340_001L
        const val FIXTURE_TITLE = "TV player proof.mp4"
        const val STEP_PAUSE_MILLIS = 1_500L
        const val ELAPSE_STEP_MILLIS = 100L
    }
}

/** The production TV player, observed for its first rendered frame and its release. */
@UnstableApi
private class ProofPlayerFactory : TvPlayerFactory {
    var player: Player? = null
        private set
    var renderedFrame = false
        private set
    fun current(): Player = checkNotNull(player)

    fun released(player: Player): Boolean = (player as ExoPlayer).isReleased

    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        DefaultTvPlayerFactory.create(context, mediaType).also { created ->
            player = created
            renderedFrame = false
            created.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    renderedFrame = true
                }
            })
        }
}
