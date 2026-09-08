package io.putdotio.android

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesFolderState
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigState
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Real service playback with a controlled shell request; no notification tap, auth runtime, or API calls. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MobileLiveAudioAttachmentProofTest {
    // Real Media3 listeners require the main looper rather than v2's queued test dispatcher.
    private val compose = createComposeRule()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Live audio attachment proof requires opt-in",
                    arguments.getString("putio.audio.attach.enabled") == "true")
                require(Build.VERSION.SDK_INT == 37) { "Live audio attachment proof requires API 37" }
                runId()
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Test
    fun shellRequestReopensLiveAudioWithoutResolvingItsSource() {
        val fixture = localFixture()
        val fileId = (runId().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1).toString()
        val repository = UnavailableAttachmentRepository()
        val pending = MutableStateFlow(false)
        val requests = NowPlayingRequests(pending) { pending.value = false }
        mountShell(repository, requests)
        var connection: Closeable? = null
        var player: Player? = null
        var seeded = false
        var itemChanges = 0
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { itemChanges += 1 }
        }
        try {
            compose.runOnUiThread {
                connection = DefaultMobilePlayerFactory.connectAudio(context) { player = it.getOrThrow() }
            }
            compose.waitUntil(15_000) { compose.runOnIdle { player != null } }
            val live = checkNotNull(player)
            compose.runOnIdle {
                check(live.mediaItemCount == 0) { "Preserving a preexisting audio session; stop it before this proof" }
                live.setMediaItem(MediaItem.Builder()
                    .setMediaId(fileId).setUri(Uri.fromFile(fixture))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("ambient-session-03.m4a").build())
                    .build(), 30_000)
                seeded = true
                live.prepare()
                live.play()
            }
            awaitPlayer(live) {
                it.playbackState == Player.STATE_READY && it.currentTracks.mobileAudioTracks().size == 2
            }
            val selected = compose.runOnIdle {
                val track = live.currentTracks.mobileAudioTracks()[1]
                live.setPlaybackSpeed(1.5f)
                live.trackSelectionParameters = live.trackSelectionParameters.withAudioSelection(
                    AudioSelection.Track(track.identity), live.currentTracks.mobileAudioTracks(),
                )
                track.identity
            }
            awaitPreservedOptions(live, selected, fileId)
            compose.runOnIdle { live.addListener(listener) }
            compose.onNodeWithTag(MOBILE_NOW_PLAYING_TAG).assertIsDisplayed()
            requestAndCheck(live, pending, repository, selected, fileId)
            screenshot("attached-without-resolve")
            compose.onNodeWithContentDescription("Pause").performTouchInput { click() }
            awaitPlayer(live) { !it.playWhenReady }
            compose.onNodeWithContentDescription("Play").performTouchInput { click() }
            awaitPlayer(live) { it.isPlaying }
            compose.onNodeWithContentDescription("Back").performTouchInput { click() }
            compose.onNodeWithTag(MOBILE_NOW_PLAYING_TAG).assertIsDisplayed()
            requestAndCheck(live, pending, repository, selected, fileId)
            screenshot("reopened-without-resolve")
            compose.runOnIdle {
                assertEquals(0, itemChanges)
                assertEquals(0, repository.resolveCalls.get())
                assertEquals(0, repository.nextCalls.get())
            }
        } finally {
            compose.runOnUiThread {
                player?.let { live ->
                    live.removeListener(listener)
                    if (seeded && live.currentMediaItem?.mediaId == fileId) {
                        DefaultMobilePlayerFactory.stopAudio(context)
                    }
                }
                connection?.close()
            }
        }
    }

    private fun requestAndCheck(
        live: Player,
        pending: MutableStateFlow<Boolean>,
        repository: UnavailableAttachmentRepository,
        selected: AudioTrackIdentity,
        fileId: String,
    ) {
        val position = compose.runOnIdle {
            val before = live.currentPosition
            pending.value = true
            before
        }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag(MOBILE_AUDIO_COVER_TAG)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(MOBILE_AUDIO_COVER_TAG).assertIsDisplayed()
        compose.onNodeWithText("ambient-session-03.m4a").assertIsDisplayed()
        awaitPreservedOptions(live, selected, fileId)
        awaitPlayer(live) { it.isPlaying && it.currentPosition > position + 500 }
        compose.runOnIdle {
            assertTrue(!pending.value)
            assertEquals(0, repository.resolveCalls.get())
        }
    }

    private fun awaitPreservedOptions(live: Player, selected: AudioTrackIdentity, fileId: String) {
        awaitPlayer(live) { player ->
            player.currentMediaItem?.mediaId == fileId && player.playbackParameters.speed == 1.5f &&
                player.currentPosition >= 30_000 &&
                player.currentTracks.mobileAudioTracks().any { track ->
                    track.identity == selected && track.selected &&
                        player.trackSelectionParameters.overrides[track.group]
                            ?.trackIndices?.contains(track.trackIndex) == true
                }
        }
    }

    private fun awaitPlayer(live: Player, predicate: (Player) -> Boolean) {
        compose.waitUntil(15_000) {
            compose.runOnIdle {
                check(live.playerError == null) { "Local service playback failed: ${live.playerError?.errorCodeName}" }
                predicate(live)
            }
        }
    }

    private fun mountShell(repository: PlaybackRepository, requests: NowPlayingRequests) {
        compose.setContent {
            PutioTheme {
                Surface(Modifier.fillMaxSize()) {
                    MobileShell(
                        filesState = FilesBrowserState(listOf(FilesFolderState(
                            FilesFolder.Root, FilesContent.Empty(FilesPaging.Complete),
                        )), 1),
                        accountSettingsState = AccountSettingsState(
                            AccountSettingsContent.Ready(AccountSettingsPreferences(false, true, false, false)),
                            AccountSettingsMutation.Idle, 1,
                        ),
                        appConfigState = AndroidAppConfigState(
                            AndroidAppConfigContent.Ready(AndroidAppConfigPreferences()),
                            AndroidAppConfigMutation.Idle, 1,
                        ),
                        account = MobileAccount(42L, "Local audio proof", "proof@example.invalid"),
                        sessionId = MobileAuthSessionId(42L),
                        playbackRepository = repository,
                        nowPlayingRequests = requests,
                        onFilesEvent = { true },
                        onAccountSettingsEvent = { error("Unexpected settings change") },
                        onPlaybackAuthenticationRequired = { error("Unexpected authentication request") },
                        onSignOut = { error("Unexpected sign out") },
                    )
                }
            }
        }
    }

    private fun localFixture(): File {
        val path = requireNotNull(arguments.getString("putio.audio.attach.fixture"))
        val file = File(path).canonicalFile
        require(file.isFile && file.canRead()) { "Local two-track audio fixture must be readable" }
        require(file.toPath().startsWith(requireNotNull(context.getExternalFilesDir(null)).canonicalFile.toPath()))
        return file
    }

    private fun screenshot(label: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
        val directory = File(
            requireNotNull(context.getExternalFilesDir(null)), "live-audio-attachment-proof-${runId()}",
        )
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.audio.attach.runId")))
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
}

private class UnavailableAttachmentRepository : PlaybackRepository {
    val resolveCalls = AtomicInteger()
    val nextCalls = AtomicInteger()
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
        resolveCalls.incrementAndGet()
        return PlaybackRepositoryResult.Failure(
            PlaybackFailure.NetworkUnavailable(IOException("Controlled offline source")),
        )
    }
    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult {
        nextCalls.incrementAndGet()
        return PlaybackNextResult.Ended
    }
}
