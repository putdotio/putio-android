package io.putdotio.android

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.playback.PlaybackMediaType
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Host-driven TalkBack gestures; private audio, with no accessibility connection or session service. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MobileTalkBackSessionProofTest {
    @get:Rule val optIn = accessibilityProofOptIn()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var directory: File
    private var deadline = 0L

    @Test
    fun talkBackControlsNowPlayingAndSeeksPrivateAudio() {
        requireTalkBack()
        directory = accessibilityProofDirectory()
        require(directory.listFiles().orEmpty().isEmpty()) { "Use a fresh proof run ID" }
        val fixture = audioFixture()
        deadline = SystemClock.uptimeMillis() + 600_000
        var ownedPlayer: ExoPlayer? = null
        try {
            ActivityScenario.launch(MobileFullscreenProofActivity::class.java).use { scenario ->
                lateinit var factory: TalkBackSessionPlayerFactory
                val observations = TalkBackSessionObservations()
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    val player = ExoPlayer.Builder(activity).build()
                    ownedPlayer = player
                    player.volume = 0f
                    player.addListener(observations)
                    player.setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(PRIVATE_MEDIA_ID)
                            .setUri(Uri.fromFile(fixture))
                            .setMediaMetadata(MediaMetadata.Builder().setTitle("TalkBack rehearsal audio").build())
                            .build(),
                        30_000L,
                    )
                    player.prepare()
                    player.play()
                    factory = TalkBackSessionPlayerFactory(player)
                    activity.setContent {
                        PutioTheme {
                            Surface(Modifier.fillMaxSize()) {
                                MobileShell(
                                    filesState = accessibilityFiles(),
                                    accountSettingsState = accessibilitySettings(),
                                    appConfigState = accessibilityAppConfig(),
                                    transfersSessionId = MobileAuthSessionId(147),
                                    account = MobileAccount(147, "TalkBack proof", "proof@example.invalid"),
                                    playbackRepository = NoShellProofPlayback,
                                    playbackPlayerFactory = factory,
                                    sessionId = MobileAuthSessionId(147),
                                    onFilesEvent = { true },
                                    onAccountSettingsEvent = { error("Unexpected settings mutation") },
                                    onPlaybackAuthenticationRequired = { error("Unexpected authentication request") },
                                    onSignOut = { error("Unexpected sign out") },
                                )
                            }
                        }
                    }
                }
                awaitPlayer("preparing", scenario, factory, observations, timeoutMillis = 15_000) {
                    it.playbackState == Player.STATE_READY && it.isPlaying && factory.connections > 0
                }
                scenario.onActivity {
                    require(factory.player.duration >= 180_000L) { "Supply audio lasting at least 180 seconds" }
                }
                awaitPlayer("bar-pause", scenario, factory, observations) {
                    it.playbackState == Player.STATE_READY && !it.playWhenReady && observations.pauseRequests > 0
                }
                var previousPlayRequests = 0
                scenario.onActivity { previousPlayRequests = observations.playRequests }
                awaitPlayer("bar-play", scenario, factory, observations) {
                    it.isPlaying && observations.playRequests > previousPlayRequests
                }
                awaitPlayer("open-player-paused", scenario, factory, observations) {
                    factory.connections >= 2 && it.playbackState == Player.STATE_READY && !it.playWhenReady
                }
                var previousSeeks = 0
                var pausedPosition = 0L
                scenario.onActivity {
                    previousSeeks = observations.seeks
                    pausedPosition = factory.player.currentPosition
                }
                awaitPlayer("seek", scenario, factory, observations) {
                    !it.playWhenReady && observations.seeks > previousSeeks &&
                        abs(observations.seekFrom - pausedPosition) <= 500L &&
                        abs(observations.seekTo - observations.seekFrom) >= 1_000L &&
                        abs(it.currentPosition - observations.seekTo) <= 500L
                }
                awaitPlayer("return-and-stop", scenario, factory, observations) {
                    factory.stops == 1 && it.mediaItemCount == 0 && it.playbackState == Player.STATE_IDLE
                }
            }
            File(directory, STAGE_FILE).writeText("finished")
        } finally {
            // The Activity closes its bar/player connections before the sole owner releases this player.
            instrumentation.runOnMainSync { ownedPlayer?.release() }
        }
    }

    private fun awaitPlayer(
        phase: String,
        scenario: ActivityScenario<MobileFullscreenProofActivity>,
        factory: TalkBackSessionPlayerFactory,
        observations: TalkBackSessionObservations,
        timeoutMillis: Long = 120_000,
        predicate: (Player) -> Boolean,
    ) {
        File(directory, STAGE_FILE).writeText(phase)
        val phaseDeadline = minOf(deadline, SystemClock.uptimeMillis() + timeoutMillis)
        var accepted = false
        while (!accepted && SystemClock.uptimeMillis() < phaseDeadline) {
            var snapshot = ""
            scenario.onActivity {
                val player = factory.player
                check(player.playerError == null) { "Private audio failed: ${player.playerError?.errorCodeName}" }
                check(player.mediaItemCount == 0 || player.currentMediaItem?.mediaId == PRIVATE_MEDIA_ID) {
                    "The private player must retain its owned item"
                }
                snapshot = "phase=$phase playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} " +
                    "state=${player.playbackState} position=${player.currentPosition} " +
                    "connections=${factory.connections} stops=${factory.stops} " +
                    "pauseRequests=${observations.pauseRequests} playRequests=${observations.playRequests} " +
                    "seeks=${observations.seeks} seekFrom=${observations.seekFrom} seekTo=${observations.seekTo}"
                accepted = predicate(player)
            }
            File(directory, STATE_FILE).writeText(snapshot)
            if (!accepted) SystemClock.sleep(250)
        }
        assertTrue("Host completed $phase through actual TalkBack controls", accepted)
    }

    private fun requireTalkBack() {
        val manager = instrumentation.targetContext.getSystemService(AccessibilityManager::class.java)
        require(manager.isTouchExplorationEnabled) { "Enable TalkBack before this selector" }
        require(manager.getEnabledAccessibilityServiceList(-1).any {
            it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback"
        }) { "Actual TalkBack is required" }
    }

    private fun audioFixture(): File {
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("putio.accessibility.audio"))
        val file = File(path).canonicalFile
        val root = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)).canonicalFile
        require(file.isFile && file.canRead() && file.toPath().startsWith(root.toPath())) {
            "Audio must be a caller-owned readable fixture beneath the app's external files directory"
        }
        return file
    }

    private companion object {
        const val PRIVATE_MEDIA_ID = "9147003"
        const val STAGE_FILE = "talkback-session-stage.txt"
        const val STATE_FILE = "talkback-session-state.txt"
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class TalkBackSessionPlayerFactory(val player: Player) : MobilePlayerFactory {
    var connections = 0
    var stops = 0

    override fun create(context: Context, mediaType: PlaybackMediaType): Player =
        error("The proof must attach its existing private audio player")

    override fun connectAudio(context: Context, onResult: (Result<Player>) -> Unit): Closeable {
        connections += 1
        onResult(Result.success(player))
        return Closeable {}
    }

    override fun stopAudio(context: Context) {
        // The now-playing handle already stopped/cleared this private player; no service is involved.
        stops += 1
    }
}

private class TalkBackSessionObservations : Player.Listener {
    var pauseRequests = 0
    var playRequests = 0
    var seeks = 0
    var seekFrom = 0L
    var seekTo = 0L

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            if (playWhenReady) playRequests += 1 else pauseRequests += 1
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            seeks += 1
            seekFrom = oldPosition.positionMs
            seekTo = newPosition.positionMs
        }
    }
}
