package io.putdotio.android

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host-driven real TalkBack proof; observes callbacks and player state without an accessibility connection. */
@RunWith(AndroidJUnit4::class)
class MobileTalkBackProofTest {
    @get:Rule val optIn = accessibilityProofOptIn()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var directory: File
    private var deadline = 0L

    @Test
    fun talkBackActivatesAuthAndControlsPrivateVideoAndAudio() {
        val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
        require(manager.isTouchExplorationEnabled) { "Enable TalkBack before this selector" }
        require(manager.getEnabledAccessibilityServiceList(-1).any {
            it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback"
        }) { "Actual TalkBack is required" }
        directory = accessibilityProofDirectory()
        val videoReviewed = File(directory, "talkback-video-reviewed")
        val audioReviewed = File(directory, "talkback-audio-reviewed")
        require(!videoReviewed.exists() && !audioReviewed.exists()) { "Use a fresh proof run ID" }
        deadline = SystemClock.uptimeMillis() + 360_000
        val retries = AtomicInteger()
        val factory = TalkBackPlayerFactory()
        val video = localPlaybackState(PlaybackMediaType.VIDEO)
        val audio = localPlaybackState(PlaybackMediaType.AUDIO)
        var surface by mutableStateOf(0)
        ActivityScenario.launch(MobileFullscreenProofActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.setContent {
                    PutioTheme {
                        Surface(Modifier.fillMaxSize()) {
                            if (surface == 0) {
                                MobileAuthMessageScreen(
                                    title = "Sign in could not finish",
                                    message = "The browser closed before authentication completed. " +
                                        "Your account is unchanged.",
                                    actionLabel = "Try signing in again",
                                    onAction = { retries.incrementAndGet() },
                                )
                            } else {
                                MobilePlayerScreen(
                                    state = if (surface == 1) video else audio,
                                    onRetry = { error("Unexpected local playback retry") },
                                    onPlayerFailure = { failure, _ -> error("Local playback failed: $failure") },
                                    onBack = {}, playerFactory = factory,
                                )
                            }
                        }
                    }
                }
            }
            awaitPhase("auth-action") { retries.get() == 1 }
            scenario.onActivity { surface = 1 }
            awaitPlayer("video-ready", scenario, factory) {
                it.playbackState == Player.STATE_READY && it.playWhenReady
            }
            awaitPlayer("video-pause", scenario, factory) {
                it.playbackState == Player.STATE_READY && !it.playWhenReady
            }
            awaitPlayer("video-speed", scenario, factory) { it.playbackParameters.speed == 1.5f && !it.playWhenReady }
            awaitPhase("video-controls") { videoReviewed.isFile }
            scenario.onActivity { surface = 2 }
            awaitPlayer("audio-ready", scenario, factory) {
                it.playbackState == Player.STATE_READY && it.playWhenReady
            }
            awaitPlayer("audio-pause", scenario, factory) {
                it.playbackState == Player.STATE_READY && !it.playWhenReady
            }
            awaitPhase("audio-controls") { audioReviewed.isFile }
        }
        File(directory, "talkback-stage.txt").writeText("finished")
    }

    private fun awaitPlayer(
        phase: String,
        scenario: ActivityScenario<MobileFullscreenProofActivity>,
        factory: TalkBackPlayerFactory,
        predicate: (Player) -> Boolean,
    ) = awaitPhase(phase) {
        var accepted = false
        var snapshot = "preparing"
        scenario.onActivity {
            factory.player?.let { player ->
                check(player.playerError == null) { "Local playback failed: ${player.playerError?.errorCodeName}" }
                snapshot = "playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} " +
                    "state=${player.playbackState} speed=${player.playbackParameters.speed} " +
                    "position=${player.currentPosition}"
                accepted = predicate(player)
            }
        }
        File(directory, "talkback-state.txt").writeText(snapshot)
        accepted
    }

    private fun awaitPhase(phase: String, predicate: () -> Boolean) {
        File(directory, "talkback-stage.txt").writeText(phase)
        val phaseDeadline = minOf(deadline, SystemClock.uptimeMillis() + 120_000)
        while (!predicate() && SystemClock.uptimeMillis() < phaseDeadline) SystemClock.sleep(250)
        assertTrue("Host completed $phase through actual TalkBack controls", predicate())
    }

    private fun localPlaybackState(mediaType: PlaybackMediaType): PlaybackState {
        val kind = if (mediaType == PlaybackMediaType.VIDEO) "video" else "audio"
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("putio.accessibility.$kind"))
        val file = File(path).canonicalFile
        val root = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)).canonicalFile
        require(file.isFile && file.canRead() && file.toPath().startsWith(root.toPath()))
        // The SDK owns production URLs; this proof reads only the caller's local fixture.
        val url = PutioCredentialUrl::class.java.getDeclaredConstructor(String::class.java)
            .newInstance(Uri.fromFile(file).toString())
        val fileId = if (mediaType == PlaybackMediaType.VIDEO) 9_147_001L else 9_147_002L
        return PlaybackState(
            PlaybackTarget(FilesItemId(fileId), "Accessibility local $kind", mediaType),
            PlaybackContent.Ready(PlaybackSource(fileId, PlaybackSourceKind.ORIGINAL, url, 20.0,
                if (mediaType == PlaybackMediaType.VIDEO) PlaybackSubtitles.Embedded else PlaybackSubtitles.None)),
            nextRequestValue = 1,
        )
    }
}

private class TalkBackPlayerFactory : MobilePlayerFactory {
    var player: Player? = null
    override fun create(context: android.content.Context, mediaType: PlaybackMediaType): Player =
        DefaultMobilePlayerFactory.create(context, mediaType).also { player = it }
}
