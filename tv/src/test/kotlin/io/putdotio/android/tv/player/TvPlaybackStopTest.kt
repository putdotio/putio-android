package io.putdotio.android.tv.player

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.tv.TvSessionKey
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.auth.TvAuthSessionId
import io.putdotio.android.tv.auth.TvAuthState
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PutioFileType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-television")
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TvPlaybackStopTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() = TvPlaybackService.resetForTests()

    @Test
    fun commandStopUsesUiExitWhileAttachedAndHostTeardownWhenDetached() {
        val players = mutableListOf<FakePlayer>()
        val factory = hostedFactory(players)
        TvPlaybackService.audioHost.playerFactory = factory
        val playbackController = startHostedTrack()
        val serviceController = Robolectric.buildService(TvPlaybackService::class.java).create()
        var mounted by mutableStateOf(true)
        var uiExits = 0
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        compose.setTvContent {
            if (mounted) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    TvPlaybackRoute(
                        controller = playbackController,
                        onExit = { uiExits += 1 },
                        onSessionRejected = {},
                        playerFactory = factory,
                        background = true,
                        onBackground = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val hosted = checkNotNull(TvBackgroundSessions.hosted)
        val pending = MediaController.Builder(compose.activity, hosted.token).buildAsync()
        shadowOf(Looper.getMainLooper()).idle()
        val mediaController = pending.get()
        try {
            assertTrue(mediaController.isCommandAvailable(Player.COMMAND_STOP))
            mediaController.stop()
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            assertEquals(1, uiExits)
            assertNotNull(TvPlaybackService.audioHost.active.value)

            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            mediaController.stop()
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()

            assertNull(TvPlaybackService.audioHost.active.value)
            assertNull(TvBackgroundSessions.hosted)
            assertTrue(players.single().released)
            assertTrue(shadowOf(serviceController.get()).isStoppedBySelf)
        } finally {
            mediaController.release()
            serviceController.destroy()
        }
    }

    private fun hostedFactory(players: MutableList<FakePlayer>) = object : TvPlayerFactory {
        override fun create(context: Context, mediaType: PlaybackMediaType): Player =
            FakePlayer().also { players += it }

        override fun publishQueue(context: Context, player: Player): TvQueueSession =
            DefaultTvPlayerFactory.publishQueue(context, player)
    }

    private fun startHostedTrack(): PlaybackController {
        val track = PlaybackTarget(FilesItemId(11), "02 Middle.mp3", PlaybackMediaType.AUDIO)
        return TvPlaybackService.audioHost.start(
            TvAudioStartRequest(
                identity = TvAudioSessionIdentity(
                    sessionKey = TvSessionKey(42L, TvAuthSessionId(1L)),
                    authState = MutableStateFlow(
                        TvAuthState.SignedIn(
                            TvAccount(userId = 42L, username = "u", email = "u@example.com", historyEnabled = true),
                            TvAuthSessionId(1L),
                        ),
                    ),
                    application = compose.activity.application,
                ),
                media = TvAudioMedia(
                    target = track,
                    startItem = FilesItem(
                        id = track.fileId,
                        parentId = FilesItemId(0L),
                        name = track.name,
                        type = PutioFileType.AUDIO,
                        sizeBytes = 1L,
                        createdAt = "2026-04-20T10:00:00Z",
                    ),
                    fromHome = false,
                    startAnswer = PlaybackEvent.Resume,
                ),
                callbacks = TvAudioSessionCallbacks(
                    playbackRepository = object : PlaybackRepository {
                        override suspend fun resolve(target: PlaybackTarget, convertedOnly: Boolean) =
                            PlaybackRepositoryResult.Success(
                                PlaybackResolution.Ready(source(fileId = target.fileId.value), false),
                            )

                        override suspend fun findNextVideo(target: PlaybackTarget) = PlaybackNextResult.Ended

                        override suspend fun findNeighbours(target: PlaybackTarget) =
                            PlaybackRepositoryResult.Success(PlaybackNeighbours())
                    },
                    settings = MutableStateFlow(AccountSettingsReducer.start().state),
                    writePlaybackPosition = { _, _ -> PlaybackRepositoryResult.Success(Unit) },
                    onSaved = { _, _ -> },
                    onAuthenticationRejected = {},
                ),
            ),
        )
    }
}
