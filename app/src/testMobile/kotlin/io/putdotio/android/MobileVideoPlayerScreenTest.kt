package io.putdotio.android

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackGroup
import androidx.media3.common.VideoSize
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.hasSelectableSubtitles
import io.putdotio.android.playback.preparePlayback
import io.putdotio.android.playback.toMediaItem
import io.putdotio.android.playback.toMediaRequestFailureOrNull
import io.putdotio.android.playback.toPlaybackFailure
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitle
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private fun ImageBitmap.hasVisiblePixel(): Boolean {
    val pixels = toPixelMap()
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (pixels[x, y].alpha > 0f) return true
        }
    }
    return false
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileVideoPlayerScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun conversionStateOffersRefreshAndBack() {
        var retries = 0
        var backs = 0
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = state(PlaybackContent.Conversion(PlaybackConversionState.Converting(42.0))),
                    onRetry = { retries += 1 },
                    onPlayerFailure = { _, _ -> },
                    onBack = { backs += 1 },
                )
            }
        }

        compose.onNodeWithText("Conversion is in progress. 42%").assertIsDisplayed()
        compose.onNodeWithText("Check again").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, retries)
        assertEquals(1, backs)
    }

    @Test
    fun unsupportedStateIsExplicitAndDoesNotOfferRetry() {
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = state(PlaybackContent.Unsupported(PutioFileType.TEXT)),
                    onRetry = { error("Retry must not be offered") },
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Can’t play this file").assertIsDisplayed()
        compose.onAllNodesWithText("Try again").assertCountEquals(0)
    }

    @Test
    fun expiredPlaybackAccessOffersCredentialRefresh() {
        var retries = 0
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state =
                        state(
                            PlaybackContent.Failed(
                                PlaybackFailure.MediaCredentialUnavailable(IllegalStateException("expired")),
                            ),
                        ),
                    onRetry = { retries += 1 },
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Playback access expired. Retry to refresh it.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun selectedSubtitleCueIsRendered() {
        val cue = Cue.Builder().setText("A rendered subtitle").build()
        compose.setContent {
            PutioTheme {
                MobileSubtitleCueOverlay(
                    cues = listOf(cue),
                    modifier = Modifier.requiredSize(320.dp, 180.dp),
                )
            }
        }

        compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(180.dp)
        assertTrue(compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG).captureToImage().hasVisiblePixel())
    }

    @Test
    fun bitmapSubtitleCueIsRendered() {
        val cue =
            Cue.Builder()
                .setBitmap(
                    Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.RED)
                    },
                )
                .setPosition(0.25f)
                .setLine(0.75f, Cue.LINE_TYPE_FRACTION)
                .setSize(0.5f)
                .setBitmapHeight(0.1f)
                .build()
        compose.setContent {
            PutioTheme {
                MobileSubtitleCueOverlay(
                    cues = listOf(cue),
                    modifier = Modifier.requiredSize(320.dp, 180.dp),
                )
            }
        }

        compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(320.dp)
            .assertHeightIsEqualTo(180.dp)
        assertTrue(compose.onNodeWithTag(MOBILE_SUBTITLE_CUES_TAG).captureToImage().hasVisiblePixel())
    }

    @Test
    fun subtitleCueLayerDoesNotBlockUnderlyingPlayerTouches() {
        var taps = 0
        var interactions = 0
        compose.setContent {
            PutioTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .observePlayerControlInteraction(
                            onInteractionChanged = {},
                            onActivity = { interactions += 1 },
                        ),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(0.5f)
                            .testTag("player-touch-target")
                            .pointerInput(Unit) {
                                detectTapGestures { taps += 1 }
                            },
                    )
                    MobileSubtitleCueOverlay(
                        cues = listOf(Cue.Builder().setText("Visible subtitle").build()),
                        modifier = Modifier.zIndex(1f),
                    )
                }
            }
        }

        compose.onNodeWithTag("player-touch-target").performTouchInput { click() }
        assertEquals(1, taps)
        assertEquals(1, interactions)
    }

    @Test
    fun subtitleToggleExposesAndUpdatesCheckedState() {
        compose.setContent {
            var enabled by remember { mutableStateOf(true) }
            PutioTheme {
                MobileSubtitleToggle(
                    enabled = enabled,
                    onToggle = { enabled = it },
                )
            }
        }

        compose.onNodeWithText("Subtitles on").assertIsOn().performClick()
        compose.onNodeWithText("Subtitles off").assertIsOff()
    }

    @Test
    fun retainedPreferencesSurviveReadyRemovalAndStateRestoration() {
        val restoration = StateRestorationTester(compose)
        lateinit var preferences: RetainedPlayerPreferences
        var showReady by mutableStateOf(true)
        restoration.setContent {
            preferences = rememberRetainedPlayerPreferences(42L)
            if (showReady) {
                Text(if (preferences.resumeAfterLifecyclePause) "resume" else "paused")
            }
        }
        compose.runOnIdle {
            preferences.retainPlayback(
                retainPlaybackOnPause(positionMillis = 54_321L, playWhenReady = false),
            )
            preferences.subtitleSelection = SubtitleSelection.Off
            showReady = false
        }
        compose.runOnIdle { showReady = true }
        compose.onNodeWithText("paused").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("paused").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(SubtitleSelection.Off, preferences.subtitleSelection)
            assertEquals(54_321L, preferences.positionMillis)
        }
    }

    @Test
    fun retainedPreferencesDefaultToResumeWhenOlderStateHasNoPlayIntent() {
        val preferences = Bundle().toRetainedPlayerPreferences()

        assertTrue(preferences.resumeAfterLifecyclePause)
    }

    @Test
    fun readyPlayerSubtreeCanBeRemovedAndRecreated() {
        val players = mutableListOf<RecordingPlayer>()
        val playerFactory = MobilePlayerFactory {
            RecordingPlayer().also(players::add)
        }
        var content by mutableStateOf<PlaybackContent>(
            PlaybackContent.Ready(
                PlaybackSource(
                    fileId = Target.fileId.value,
                    kind = PlaybackSourceKind.MP4,
                    url = credentialUrl("https://example.com/video.mp4"),
                    startFromSeconds = 12.345,
                    subtitles = PlaybackSubtitles.None,
                ),
            ),
        )
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = state(content),
                    onRetry = {},
                    onPlayerFailure = { failure, _ -> content = PlaybackContent.Failed(failure) },
                    onBack = {},
                    playerFactory = playerFactory,
                    subtitleStartupPolicy =
                        SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false),
                )
            }
        }
        compose.onNodeWithTag(MOBILE_VIDEO_PLAYER_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, players.size)
            assertEquals(1, players.single().mediaItemUpdates)
            assertEquals(1, players.single().prepareCalls)
            assertTrue(players.single().playWhenReady)
            assertFalse(players.single().trackSelectionParameters.selectTextByDefault)
            assertFalse(C.TRACK_TYPE_TEXT in players.single().trackSelectionParameters.disabledTrackTypes)
        }

        compose.runOnIdle {
            content =
                PlaybackContent.Ready(
                    PlaybackSource(
                        fileId = Target.fileId.value,
                        kind = PlaybackSourceKind.MP4,
                        url = credentialUrl("https://example.com/replaced-video.mp4"),
                        startFromSeconds = 0.0,
                        subtitles = PlaybackSubtitles.None,
                    ),
                )
        }
        compose.runOnIdle {
            assertEquals(1, players.size)
            assertEquals(2, players.single().mediaItemUpdates)
        }

        compose.runOnIdle {
            players.single().fail(
                PlaybackException(
                    "decoder failed",
                    null,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                ),
            )
        }
        compose.onNodeWithText("put.io is temporarily unavailable. Try again.").assertIsDisplayed()
        compose.runOnIdle { assertTrue(players.single().released) }

        compose.runOnIdle {
            content = PlaybackContent.Failed(PlaybackFailure.NetworkUnavailable(IOException("offline")))
        }
        compose.onNodeWithText("Check your connection and try again.").assertIsDisplayed()

        compose.runOnIdle {
            content =
                PlaybackContent.Ready(
                    PlaybackSource(
                        fileId = Target.fileId.value,
                        kind = PlaybackSourceKind.MP4,
                        url = credentialUrl("https://example.com/video.mp4"),
                        startFromSeconds = 12.345,
                        subtitles = PlaybackSubtitles.None,
                    ),
                )
        }
        compose.onNodeWithTag(MOBILE_VIDEO_PLAYER_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertFalse(players.last().released)
            assertEquals(1, players.last().mediaItemUpdates)
        }
    }

    @Test
    fun accountSubtitlePolicyAppliesWhenSettingsBecomeReady() {
        val player = RecordingPlayer()
        var policy by mutableStateOf<SubtitleStartupPolicy?>(null)
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = state(PlaybackContent.Ready(videoSource())),
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    playerFactory = MobilePlayerFactory { player },
                    subtitleStartupPolicy = policy,
                )
            }
        }
        compose.runOnIdle {
            assertFalse(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
            policy = SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = false)
        }
        compose.runOnIdle {
            assertTrue(C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes)
        }
    }

    @Test
    fun playerReleasesSynchronouslyOnStopAndRecreatesOnStart() {
        val lifecycleOwner = PlayerLifecycleOwner().apply { moveTo(Lifecycle.State.RESUMED) }
        val players = mutableListOf<RecordingPlayer>()
        val failures = mutableListOf<PlaybackFailure>()
        val releaseError =
            PlaybackException(
                "renderer release timed out",
                null,
                PlaybackException.ERROR_CODE_TIMEOUT,
            )
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                PutioTheme {
                    MobileVideoPlayerScreen(
                        state = state(PlaybackContent.Ready(videoSource())),
                        onRetry = {},
                        onPlayerFailure = { failure, _ -> failures += failure },
                        onBack = {},
                        playerFactory = MobilePlayerFactory {
                            RecordingPlayer(releaseError).also(players::add)
                        },
                    )
                }
            }
        }
        compose.runOnIdle { assertEquals(1, players.size) }

        compose.runOnIdle { players.single().pause() }
        compose.runOnIdle {
            players.single().play()
            assertTrue(players.single().playWhenReady)
            lifecycleOwner.moveTo(Lifecycle.State.STARTED)
            players.single().movePositionTo(54_321L)
            lifecycleOwner.moveTo(Lifecycle.State.CREATED)
            assertTrue(players.single().released)
            assertTrue(failures.isEmpty())
        }
        compose.runOnIdle { assertEquals(1, players.size) }
        compose.runOnIdle { lifecycleOwner.moveTo(Lifecycle.State.RESUMED) }
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertFalse(players.last().released)
            assertEquals(54_321L, players.last().currentPosition)
            assertTrue(players.last().playWhenReady)
        }

        compose.runOnIdle {
            lifecycleOwner.moveTo(Lifecycle.State.STARTED)
            lifecycleOwner.moveTo(Lifecycle.State.CREATED)
            assertTrue(players.last().released)
            assertEquals(2, players.size)
            lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
            assertEquals(0, players.last().playStateUpdatesAfterRelease)
        }
        compose.runOnIdle {
            assertEquals(3, players.size)
            assertFalse(players.last().released)
            assertEquals(54_321L, players.last().currentPosition)
        }
    }

    @Test
    fun playerRecreatesAfterCoalescedStopAndStartWithoutResume() {
        val lifecycleOwner = PlayerLifecycleOwner().apply { moveTo(Lifecycle.State.RESUMED) }
        val players = mutableListOf<RecordingPlayer>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                PutioTheme {
                    MobileVideoPlayerScreen(
                        state = state(PlaybackContent.Ready(videoSource())),
                        onRetry = {},
                        onPlayerFailure = { _, _ -> },
                        onBack = {},
                        playerFactory = MobilePlayerFactory { RecordingPlayer().also(players::add) },
                    )
                }
            }
        }
        compose.runOnIdle { assertEquals(1, players.size) }

        compose.runOnIdle {
            players.single().movePositionTo(54_321L)
            lifecycleOwner.moveTo(Lifecycle.State.STARTED)
            lifecycleOwner.moveTo(Lifecycle.State.CREATED)
            lifecycleOwner.moveTo(Lifecycle.State.STARTED)
            assertTrue(players.single().released)
            assertEquals(1, players.size)
        }
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertFalse(players.last().released)
            assertTrue(players.last().currentPosition in 54_321L..54_500L)
            assertFalse(players.last().playWhenReady)
        }
    }

    @Test
    fun selectedSubtitleTrackExposesCheckedState() {
        val group =
            TrackGroup(
                Format.Builder().setId("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        compose.setContent {
            PutioTheme {
                MobileSubtitleTrackOption(
                    track =
                        MobileSubtitleTrack(
                            group = group,
                            trackIndex = 0,
                            label = "English",
                            selected = true,
                        ),
                    onClick = {},
                )
            }
        }

        compose.onNodeWithText("English").assertIsOn()
    }

    @Test
    fun subtitleTrackOptionForwardsPointerAndKeyboardModality() {
        val group =
            TrackGroup(
                Format.Builder().setId("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        var pointerEvents = 0
        var keyEvents = 0
        lateinit var inputModeManager: InputModeManager
        compose.setContent {
            inputModeManager = LocalInputModeManager.current
            PutioTheme {
                MobileSubtitleTrackOption(
                    track = MobileSubtitleTrack(group, 0, label = "English", selected = false),
                    onClick = {},
                    modifier =
                        Modifier
                            .observePlayerControlInteraction(
                                onInteractionChanged = { if (it) pointerEvents += 1 },
                                onActivity = {},
                            ).observePlayerControlKeyActivity { keyEvents += 1 },
                )
            }
        }

        compose.onNodeWithText("English").performTouchInput { click() }
        compose.runOnIdle { assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithText("English")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }

        assertEquals(1, pointerEvents)
        assertEquals(1, keyEvents)
    }

    @Test
    @UnstableApi
    fun mediaItemPreservesHlsMetadataAndKnownSidecarSubtitles() {
        val source = PlaybackSource(
            fileId = Target.fileId.value,
            kind = PlaybackSourceKind.HLS,
            url = credentialUrl("https://api.put.io/v2/files/42/hls/media.m3u8?token=credential"),
            startFromSeconds = 12.5,
            subtitles =
                PlaybackSubtitles.Sidecar(
                    listOf(
                        subtitle("English", "en", "srt"),
                        subtitle("German", "de", "vtt"),
                        subtitle("Inferred", "tr", null, "subtitle.vtt"),
                        subtitle("Unknown", "und", "future-format"),
                        subtitle("Declared unknown", "es", "future-format", "subtitle.vtt"),
                        subtitle("Missing", "fr", null),
                    ),
                ),
        )

        val preparedPlayback = source.preparePlayback(Target.name)
        val item = preparedPlayback.mediaItem
        val local = requireNotNull(item.localConfiguration)

        assertEquals(12_500L, preparedPlayback.startPositionMillis)
        assertEquals(MimeTypes.APPLICATION_M3U8, local.mimeType)
        assertEquals("episode.mkv", item.mediaMetadata.title)
        assertEquals(3, local.subtitleConfigurations.size)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, local.subtitleConfigurations.first().mimeType)
        assertEquals("en", local.subtitleConfigurations.first().language)
        assertEquals(MimeTypes.TEXT_VTT, local.subtitleConfigurations[2].mimeType)
        assertEquals("tr", local.subtitleConfigurations[2].language)
        assertTrue(local.subtitleConfigurations.all { it.selectionFlags == 0 })
        assertEquals(54_321L, source.preparePlayback(Target.name, 54_321L).startPositionMillis)
        assertTrue(source.hasSelectableSubtitles())
        assertFalse(
            source
                .copy(
                    subtitles = PlaybackSubtitles.Sidecar(listOf(subtitle("Unknown", "und", "future-format"))),
                ).hasSelectableSubtitles(),
        )
    }

    @Test
    fun mp4MediaItemLetsMedia3InferTheContainer() {
        val source = PlaybackSource(
            fileId = Target.fileId.value,
            kind = PlaybackSourceKind.MP4,
            url = credentialUrl("https://api.put.io/v2/files/42/mp4/download?token=credential"),
            startFromSeconds = 0.0,
            subtitles = PlaybackSubtitles.None,
        )

        assertNull(source.toMediaItem(Target.name).localConfiguration?.mimeType)
    }

    private fun state(content: PlaybackContent): PlaybackState =
        PlaybackState(
            target = Target,
            content = content,
            nextRequestValue = 2L,
        )

    private fun subtitle(
        name: String,
        languageCode: String,
        format: String?,
        path: String = languageCode,
    ): PlaybackSubtitle =
        PlaybackSubtitle(
            format = format,
            key = languageCode,
            language = name,
            languageCode = languageCode,
            name = name,
            source = "put.io",
            url = credentialUrl("https://api.put.io/v2/subtitles/$path?token=credential"),
        )

    // Credential URLs can only be minted by the SDK resolver in production.
    private fun credentialUrl(value: String): PutioCredentialUrl =
        PutioCredentialUrl::class.java
            .getDeclaredConstructor(String::class.java)
            .newInstance(value)

    private fun videoSource(): PlaybackSource =
        PlaybackSource(
            fileId = Target.fileId.value,
            kind = PlaybackSourceKind.MP4,
            url = credentialUrl("https://example.com/video.mp4"),
            startFromSeconds = 12.345,
            subtitles = PlaybackSubtitles.None,
        )

    private companion object {
        val Target = PlaybackTarget(FilesItemId(42L), "episode.mkv")
    }
}

private class PlayerLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

@UnstableApi
private class RecordingPlayer(
    private val releaseError: PlaybackException? = null,
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state =
        State.Builder()
            .setAvailableCommands(Media3Player.Commands.Builder().addAllCommands().build())
            .setPlaybackState(Media3Player.STATE_IDLE)
            .build()

    var mediaItemUpdates = 0
        private set
    var prepareCalls = 0
        private set
    var released = false
        private set
    var playStateUpdatesAfterRelease = 0
        private set

    override fun getState(): State = state

    fun movePositionTo(positionMillis: Long) {
        state = state.buildUpon().setContentPositionMs(positionMillis).build()
        invalidateState()
    }

    fun fail(error: PlaybackException) {
        state =
            state.buildUpon()
                .setPlayerError(error)
                .setPlaybackState(Media3Player.STATE_IDLE)
                .build()
        invalidateState()
    }

    override fun handleSetMediaItems(
        mediaItems: List<androidx.media3.common.MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        mediaItemUpdates += 1
        val playlist =
            mediaItems.mapIndexed { index, mediaItem ->
                MediaItemData.Builder(index)
                    .setMediaItem(mediaItem)
                    .build()
            }
        state =
            state.buildUpon()
                .setPlaylist(playlist)
                .setCurrentMediaItemIndex(startIndex)
                .setContentPositionMs(startPositionMs)
                .build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepareCalls += 1
        state = state.buildUpon().setPlaybackState(Media3Player.STATE_READY).build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (released) playStateUpdatesAfterRelease += 1
        state =
            state.buildUpon()
                .setPlayWhenReady(playWhenReady, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(
        trackSelectionParameters: TrackSelectionParameters,
    ): ListenableFuture<*> {
        state = state.buildUpon().setTrackSelectionParameters(trackSelectionParameters).build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        releaseError?.let(::fail)
        released = true
        return Futures.immediateVoidFuture()
    }
}

private fun rendererCapabilities(trackType: Int): RendererCapabilities =
    object : RendererCapabilities {
        override fun getName(): String = "test-$trackType"

        override fun getTrackType(): Int = trackType

        override fun supportsFormat(format: Format): Int =
            RendererCapabilities.create(
                if (MimeTypes.getTrackType(format.sampleMimeType) == trackType) {
                    C.FORMAT_HANDLED
                } else {
                    C.FORMAT_UNSUPPORTED_TYPE
                },
            )

        override fun supportsMixedMimeTypeAdaptation(): Int = RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
    }

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
class MobileVideoPlayerCodecTest {
    @Test
    fun subtitleSelectionCanEnableDisableAndReenableUnflaggedText() {
        val defaults = TrackSelectionParameters.Builder().build()
        val enabled = defaults.withSubtitlesEnabled(true)
        assertTrue(enabled.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in enabled.disabledTrackTypes)

        val disabled = enabled.withSubtitlesEnabled(false)
        assertFalse(disabled.selectTextByDefault)
        assertTrue(C.TRACK_TYPE_TEXT in disabled.disabledTrackTypes)

        val reenabled = disabled.withSubtitlesEnabled(true)
        assertTrue(reenabled.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in reenabled.disabledTrackTypes)
    }

    @Test
    fun retainedSubtitleSelectionWinsWhenThePlayerIsRecreated() {
        val defaults = TrackSelectionParameters.Builder().build()
        val disabled = defaults.withSubtitlesEnabled(false)

        val restored =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = SubtitleSelection.Off,
                systemCaptionsEnabled = true,
            )

        assertFalse(restored.selectTextByDefault)
        assertTrue(C.TRACK_TYPE_TEXT in restored.disabledTrackTypes)

        val group =
            TrackGroup(
                Format.Builder().setId("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
                Format.Builder().setId("de").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        val track =
            MobileSubtitleTrack(
                group = group,
                trackIndex = 1,
                label = "German",
                selected = false,
            )
        val restoredSelection =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = SubtitleSelection.Track(track.identity),
                systemCaptionsEnabled = false,
            ).withSubtitleSelection(SubtitleSelection.Track(track.identity), listOf(track))

        assertEquals(listOf(1), restoredSelection.overrides.getValue(group).trackIndices)
    }

    @Test
    fun playerDefaultsPreserveTheSystemCaptionPreference() {
        val captionsDisabled =
            TrackSelectionParameters.Builder()
                .setSelectTextByDefault(false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()

        val restored =
            restoreSubtitleSelection(
                captionsDisabled,
                retained = null,
                systemCaptionsEnabled = false,
            )
        val captionsEnabled =
            restoreSubtitleSelection(
                captionsDisabled,
                retained = null,
                systemCaptionsEnabled = true,
            )

        assertFalse(C.TRACK_TYPE_TEXT in restored.disabledTrackTypes)
        assertFalse(restored.subtitlesEnabled(emptyList()))
        assertTrue(captionsEnabled.selectTextByDefault)

        val automatic = restored.withSubtitleSelection(SubtitleSelection.Automatic, emptyList())
        assertEquals(0, automatic.ignoredTextSelectionFlags)
        assertTrue(automatic.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in captionsEnabled.disabledTrackTypes)
    }

    @Test
    fun accountSubtitlePolicySeedsPlayerDefaults() {
        val defaults = TrackSelectionParameters.Builder().build()
        val hidden =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = true,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = true),
            )
        val forcedOnly =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = true,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false),
            )
        val automatic =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = false,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = true),
            )
        val retained =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = SubtitleSelection.Automatic,
                systemCaptionsEnabled = false,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = false, autoSelectSubtitles = false),
            )

        assertFalse(hidden.selectTextByDefault)
        assertTrue(C.TRACK_TYPE_TEXT in hidden.disabledTrackTypes)
        assertFalse(forcedOnly.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in forcedOnly.disabledTrackTypes)
        assertTrue(automatic.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in automatic.disabledTrackTypes)
        assertTrue(retained.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in retained.disabledTrackTypes)
    }

    @Test
    fun forcedOnlyPolicySelectsForcedTrackInsteadOfDefaultCaption() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val defaults =
            TrackSelectionParameters.Builder()
                .setPreferredTextLanguages("en")
                .setPreferredTextRoleFlags(C.ROLE_FLAG_CAPTION)
                .setPreferredTextLabels("English")
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED)
                .setSelectUndeterminedTextLanguage(true)
                .build()
        val audio =
            Format.Builder()
                .setId("audio")
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setLanguage("en")
                .build()
        val ordinary =
            Format.Builder()
                .setId("ordinary")
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .setLanguage("en")
                .setLabel("English")
                .setRoleFlags(C.ROLE_FLAG_CAPTION)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        val forced =
            Format.Builder()
                .setId("forced")
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .setLanguage("en")
                .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                .build()
        val trackGroups = TrackGroupArray(TrackGroup(audio), TrackGroup(ordinary, forced))
        fun selectedTextId(parameters: TrackSelectionParameters): String? {
            val selector = DefaultTrackSelector(context, parameters)
            selector.init({ _ -> }, DefaultBandwidthMeter.getSingletonInstance(context))
            val result =
                selector.selectTracks(
                    arrayOf(rendererCapabilities(C.TRACK_TYPE_AUDIO), rendererCapabilities(C.TRACK_TYPE_TEXT)),
                    trackGroups,
                    MediaSource.MediaPeriodId(Any()),
                    Timeline.EMPTY,
                )
            return result.selections[1]?.selectedFormat?.id.also { selector.release() }
        }
        val forcedOnly =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = true,
                startupPolicy = SubtitleStartupPolicy(showSubtitles = true, autoSelectSubtitles = false),
            )
        val noPolicyFallback =
            restoreSubtitleSelection(
                defaults = defaults,
                retained = null,
                systemCaptionsEnabled = false,
            )
        val automatic =
            forcedOnly
                .withSubtitleSelection(SubtitleSelection.Off, emptyList(), defaults)
                .withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), defaults)

        assertEquals("forced", selectedTextId(forcedOnly))
        assertEquals("forced", selectedTextId(noPolicyFallback))
        assertEquals("ordinary", selectedTextId(automatic))
        assertEquals(listOf("en"), automatic.preferredTextLanguages)
        assertEquals(C.ROLE_FLAG_CAPTION, automatic.preferredTextRoleFlags)
        assertEquals(listOf("English"), automatic.preferredTextLabels)
        assertEquals(C.SELECTION_FLAG_FORCED, automatic.ignoredTextSelectionFlags)
        assertTrue(automatic.selectUndeterminedTextLanguage)

        val captionManagerDefaults =
            TrackSelectionParameters.Builder()
                .setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings()
                .build()
        val captionManagerAutomatic =
            captionManagerDefaults
                .withSubtitleSelection(SubtitleSelection.Off, emptyList(), captionManagerDefaults)
                .withSubtitleSelection(SubtitleSelection.Automatic, emptyList(), captionManagerDefaults)
        assertTrue(captionManagerAutomatic.usePreferredTextLanguagesAndRoleFlagsFromCaptioningManager)
    }

    @Test
    fun mobileVideoRequestsMovieAudioFocus() {
        var capturedAttributes: AudioAttributes? = null
        var capturedHandleAudioFocus: Boolean? = null
        var capturedHandleAudioBecomingNoisy: Boolean? = null

        configureMobilePlayerAudio(
            setAudioAttributes = { attributes, handleAudioFocus ->
                capturedAttributes = attributes
                capturedHandleAudioFocus = handleAudioFocus
            },
            setHandleAudioBecomingNoisy = { capturedHandleAudioBecomingNoisy = it },
        )

        assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, capturedAttributes?.contentType)
        assertEquals(C.USAGE_MEDIA, capturedAttributes?.usage)
        assertEquals(true, capturedHandleAudioFocus)
        assertEquals(true, capturedHandleAudioBecomingNoisy)
    }

    @Test
    fun subtitleFrameMatchesTheFittedVideoSurface() {
        assertEquals(16f / 9f, VideoSize(1_920, 1_080).displayAspectRatioOrNull())
        assertEquals(FittedVideoSize(1_080, 608), fitInside(1_080, 2_160, 16f / 9f))
        assertEquals(FittedVideoSize(1_080, 1_920), fitInside(1_080, 2_160, 9f / 16f))
    }

    @Test
    fun autoplayRequiresAResumedLifecycle() {
        assertFalse(lifecycleAllowsAutoplay(Lifecycle.State.CREATED))
        assertFalse(lifecycleAllowsAutoplay(Lifecycle.State.STARTED))
        assertTrue(lifecycleAllowsAutoplay(Lifecycle.State.RESUMED))
        assertFalse(
            lifecycleAllowsAutoplay(
                state = Lifecycle.State.RESUMED,
                resumeAfterLifecyclePause = false,
            ),
        )
    }

    @Test
    fun activePlaybackKeepsTheScreenAwake() {
        assertTrue(playbackKeepsScreenOn(playWhenReady = true, Media3Player.STATE_BUFFERING))
        assertTrue(playbackKeepsScreenOn(playWhenReady = true, Media3Player.STATE_READY))
        assertFalse(playbackKeepsScreenOn(playWhenReady = false, Media3Player.STATE_READY))
        assertFalse(playbackKeepsScreenOn(playWhenReady = true, Media3Player.STATE_IDLE))
        assertFalse(playbackKeepsScreenOn(playWhenReady = true, Media3Player.STATE_ENDED))
        assertFalse(
            playbackKeepsScreenOn(
                playWhenReady = true,
                playbackState = Media3Player.STATE_READY,
                playbackSuppressionReason = Media3Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS,
            ),
        )
    }

    @Test
    fun lifecyclePauseRetainsPositionAndManualPauseIntent() {
        assertEquals(
            RetainedPlayback(positionMillis = 12_345L, resumeAfterLifecyclePause = true),
            retainPlaybackOnPause(positionMillis = 12_345L, playWhenReady = true),
        )
        assertEquals(
            RetainedPlayback(positionMillis = 54_321L, resumeAfterLifecyclePause = false),
            retainPlaybackOnPause(positionMillis = 54_321L, playWhenReady = false),
        )
    }

    @Test
    fun playerEventsPreserveBackgroundPauseIntent() {
        assertEquals(
            PlayerRetentionUpdate.Playback(
                RetainedPlayback(positionMillis = 54_321L, resumeAfterLifecyclePause = true),
            ),
            playerRetentionUpdate(
                event = PlayerRetentionEvent.PlayerError,
                lifecycleState = Lifecycle.State.RESUMED,
                positionMillis = 54_321L,
                playWhenReady = true,
            ),
        )
        assertEquals(
            PlayerRetentionUpdate.Position(54_321L),
            playerRetentionUpdate(
                event = PlayerRetentionEvent.PlayerError,
                lifecycleState = Lifecycle.State.CREATED,
                positionMillis = 54_321L,
                playWhenReady = false,
            ),
        )
        assertEquals(
            PlayerRetentionUpdate.Playback(
                RetainedPlayback(positionMillis = 54_321L, resumeAfterLifecyclePause = true),
            ),
            playerRetentionUpdate(
                event = PlayerRetentionEvent.LifecyclePause,
                lifecycleState = Lifecycle.State.CREATED,
                positionMillis = 54_321L,
                playWhenReady = true,
            ),
        )
    }

    @Test
    fun sourceReplacementUsesLivePositionOnlyForTheActiveFile() {
        assertEquals(
            54_321L,
            replacementPositionMillis(
                activeFileId = 42L,
                replacementFileId = 42L,
                livePositionMillis = 54_321L,
                preparedPositionMillis = 12_345L,
            ),
        )
        assertEquals(
            12_345L,
            replacementPositionMillis(
                activeFileId = 7L,
                replacementFileId = 42L,
                livePositionMillis = 54_321L,
                preparedPositionMillis = 12_345L,
            ),
        )
    }

    @Test
    fun errorPositionSurvivesTheFollowingPlayerDisposal() {
        assertEquals(
            54_321L,
            retainedPositionOnDispose(
                failurePositionMillis = 54_321L,
                livePositionMillis = 12_345L,
            ),
        )
        assertEquals(
            12_345L,
            retainedPositionOnDispose(
                failurePositionMillis = null,
                livePositionMillis = 12_345L,
            ),
        )
    }

    @Test
    fun retainedPositionWinsOverAnEarlierRequestedResumePosition() {
        assertEquals(
            54_321L,
            preferredPlaybackPosition(
                retainedPositionMillis = 54_321L,
                requestedPositionMillis = 12_345L,
            ),
        )
        assertEquals(
            12_345L,
            preferredPlaybackPosition(
                retainedPositionMillis = null,
                requestedPositionMillis = 12_345L,
            ),
        )
    }

    @Test
    fun selectingSubtitleTrackEnablesTextAndPinsTheRequestedTrack() {
        val group =
            TrackGroup(
                Format.Builder().setId("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
                Format.Builder().setId("de").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        val selected =
            TrackSelectionParameters.Builder().build().withSubtitleSelection(
                SubtitleSelection.Track(group.getFormat(1).toSubtitleTrackIdentity()),
                listOf(
                    MobileSubtitleTrack(
                        group = group,
                        trackIndex = 1,
                        label = "German",
                        selected = false,
                    ),
                ),
            )

        assertTrue(selected.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in selected.disabledTrackTypes)
        assertEquals(listOf(1), selected.overrides.getValue(group).trackIndices)
    }

    @Test
    fun retainedSubtitleIdentityResolvesAgainstReplacementTracks() {
        val oldGroup =
            TrackGroup(
                Format.Builder().setId("en").setLanguage("en").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
                Format.Builder().setId("de").setLanguage("de").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        val replacementGroup =
            TrackGroup(
                Format.Builder().setId("en").setLanguage("en").setLabel("English").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
                Format.Builder().setId("de").setLanguage("de").setLabel("Deutsch").setSampleMimeType(MimeTypes.TEXT_VTT).build(),
            )
        val selection = SubtitleSelection.Track(oldGroup.getFormat(1).toSubtitleTrackIdentity())
        val oldParameters =
            TrackSelectionParameters.Builder().build().withSubtitleSelection(
                selection,
                listOf(MobileSubtitleTrack(oldGroup, 1, label = "German", selected = false)),
            )

        val replacementParameters =
            oldParameters.withSubtitleSelection(
                selection,
                listOf(MobileSubtitleTrack(replacementGroup, 1, label = "German", selected = false)),
            )

        assertFalse(oldGroup in replacementParameters.overrides)
        assertEquals(listOf(1), replacementParameters.overrides.getValue(replacementGroup).trackIndices)
    }

    @Test
    fun unmatchedRetainedSubtitleStaysDisabledUntilItsTrackAppears() {
        val selectedFormat =
            Format.Builder()
                .setId("de")
                .setLanguage("de")
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .build()
        val selection = SubtitleSelection.Track(selectedFormat.toSubtitleTrackIdentity())

        val pending =
            TrackSelectionParameters.Builder().build().withSubtitleSelection(selection, emptyList())

        assertFalse(pending.selectTextByDefault)
        assertTrue(C.TRACK_TYPE_TEXT in pending.disabledTrackTypes)
        assertTrue(pending.overrides.isEmpty())

        val replacementGroup = TrackGroup(selectedFormat)
        val resolved =
            pending.withSubtitleSelection(
                selection,
                listOf(MobileSubtitleTrack(replacementGroup, 0, label = "German", selected = false)),
            )

        assertTrue(resolved.selectTextByDefault)
        assertFalse(C.TRACK_TYPE_TEXT in resolved.disabledTrackTypes)
        assertEquals(listOf(0), resolved.overrides.getValue(replacementGroup).trackIndices)
    }

    @Test
    fun idlessSubtitleIdentityDistinguishesFlagsAndAccessibilityChannel() {
        val forced =
            Format.Builder()
                .setLanguage("en")
                .setLabel("English")
                .setSampleMimeType(MimeTypes.APPLICATION_CEA608)
                .setSelectionFlags(C.SELECTION_FLAG_FORCED)
                .setAccessibilityChannel(1)
                .build()
        val full =
            forced.buildUpon()
                .setSelectionFlags(0)
                .setAccessibilityChannel(2)
                .build()
        val forcedIdentity = forced.toSubtitleTrackIdentity()

        assertTrue(forcedIdentity.exactlyMatches(forced))
        assertFalse(forcedIdentity.exactlyMatches(full))
        assertNull(
            listOf(MobileSubtitleTrack(TrackGroup(full), 0, label = "English", selected = false))
                .resolve(forcedIdentity),
        )
    }

    @Test
    fun duplicateSubtitleIdsRequireTheRemainingIdentityToMatch() {
        val selected =
            Format.Builder()
                .setId("subtitle")
                .setLanguage("en")
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .build()
        val duplicate =
            selected.buildUpon()
                .setLanguage("de")
                .build()
        val selectedGroup = TrackGroup(selected)
        val duplicateGroup = TrackGroup(duplicate)

        val resolved =
            listOf(
                MobileSubtitleTrack(duplicateGroup, 0, label = "German", selected = false),
                MobileSubtitleTrack(selectedGroup, 0, label = "English", selected = false),
            ).resolve(selected.toSubtitleTrackIdentity())

        assertEquals(selectedGroup, resolved?.group)
    }

    @Test
    fun activeNonTouchInteractionPreventsControlAutoHide() {
        assertTrue(isPlayerControlActivity(KeyEventType.KeyDown))
        assertFalse(isPlayerControlActivity(KeyEventType.KeyUp))
        assertTrue(
            controlsShouldAutoHide(
                controlsVisible = true,
                playerWantsToPlay = true,
                pointerInteracting = false,
                keyboardNavigationActive = false,
                menuOpen = false,
                touchExplorationEnabled = false,
            ),
        )
        assertFalse(
            controlsShouldAutoHide(
                controlsVisible = true,
                playerWantsToPlay = true,
                pointerInteracting = false,
                keyboardNavigationActive = true,
                menuOpen = false,
                touchExplorationEnabled = false,
            ),
        )
        assertFalse(
            controlsShouldAutoHide(
                controlsVisible = true,
                playerWantsToPlay = true,
                pointerInteracting = false,
                keyboardNavigationActive = false,
                menuOpen = false,
                touchExplorationEnabled = true,
            ),
        )
        assertFalse(
            controlsShouldAutoHide(
                controlsVisible = true,
                playerWantsToPlay = true,
                playbackState = Media3Player.STATE_ENDED,
                pointerInteracting = false,
                keyboardNavigationActive = false,
                menuOpen = false,
                touchExplorationEnabled = false,
            ),
        )
        assertTrue(controlsVisibleForTouchExploration(false, true))
        assertTrue(controlsVisibleForPlaybackState(false, Media3Player.STATE_ENDED))
    }

    @Test
    fun mandatoryControlsCannotBeHiddenByTaps() {
        assertFalse(controlsVisibleAfterTap(true, Media3Player.STATE_READY, false))
        assertTrue(controlsVisibleAfterTap(false, Media3Player.STATE_READY, false))
        assertTrue(controlsVisibleAfterTap(true, Media3Player.STATE_ENDED, false))
        assertTrue(controlsVisibleAfterTap(false, Media3Player.STATE_ENDED, false))
        assertTrue(controlsVisibleAfterTap(true, Media3Player.STATE_READY, true))
        assertTrue(controlsVisibleAfterTap(false, Media3Player.STATE_READY, true))
    }

    @Test
    fun emulatorCodecsDemoteGoldfishDecoders() {
        assertTrue(requiresEmulatorCodecWorkaround(37, "ranchu"))
        assertTrue(requiresEmulatorCodecWorkaround(37, "goldfish"))
        assertFalse(requiresEmulatorCodecWorkaround(36, "ranchu"))
        assertFalse(requiresEmulatorCodecWorkaround(37, "tensor"))
        assertEquals(1, emulatorCodecPriority("c2.goldfish.h264.decoder"))
        assertEquals(0, emulatorCodecPriority("c2.android.avc.decoder"))
        assertEquals(SURFACE_TYPE_TEXTURE_VIEW, playbackSurfaceType(37, "ranchu"))
        assertEquals(SURFACE_TYPE_SURFACE_VIEW, playbackSurfaceType(36, "ranchu"))
        assertEquals(SURFACE_TYPE_SURFACE_VIEW, playbackSurfaceType(37, "tensor"))
    }

    @Test
    fun mediaRequestUnauthorizedRefreshesThePlaybackCredential() {
        val dataSpec = DataSpec(Uri.parse("https://example.com/video.mp4"))
        val response =
            HttpDataSource.InvalidResponseCodeException(
                401,
                "Unauthorized",
                IOException("rejected"),
                emptyMap(),
                dataSpec,
                ByteArray(0),
            )

        assertTrue(
            IllegalStateException("player failed", response).toMediaRequestFailureOrNull() is
                PlaybackFailure.MediaCredentialUnavailable,
        )
    }

    @Test
    fun mediaRequestForbiddenRefreshesThePlaybackCredential() {
        val dataSpec = DataSpec(Uri.parse("https://example.com/video.mp4"))
        val response =
            HttpDataSource.InvalidResponseCodeException(
                403,
                "Forbidden",
                IOException("rejected"),
                emptyMap(),
                dataSpec,
                ByteArray(0),
            )

        assertTrue(
            IllegalStateException("player failed", response).toMediaRequestFailureOrNull() is
                PlaybackFailure.MediaCredentialUnavailable,
        )
    }

    @Test
    fun mediaRequestNotFoundUsesGenericRecovery() {
        val dataSpec = DataSpec(Uri.parse("https://example.com/video.mp4"))
        val response =
            HttpDataSource.InvalidResponseCodeException(
                404,
                "Not Found",
                IOException("missing"),
                emptyMap(),
                dataSpec,
                ByteArray(0),
            )

        assertNull(IllegalStateException("player failed", response).toMediaRequestFailureOrNull())
    }

    @Test
    fun mediaRequestTransportFailureReportsNetworkUnavailable() {
        val dataSpec = DataSpec(Uri.parse("https://example.com/video.mp4"))
        val transport =
            HttpDataSource.HttpDataSourceException(
                IOException("offline"),
                dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )

        assertTrue(
            IllegalStateException("player failed", transport).toMediaRequestFailureOrNull() is
                PlaybackFailure.NetworkUnavailable,
        )
    }

    @Test
    fun nonNetworkDataSourceFailureUsesGenericRecovery() {
        val dataSpec = DataSpec(Uri.parse("https://example.com/video.mp4"))
        val dataSourceFailure =
            HttpDataSource.HttpDataSourceException(
                IOException("cleartext rejected"),
                dataSpec,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        val error =
            PlaybackException(
                "player failed",
                dataSourceFailure,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            )

        assertTrue(error.toPlaybackFailure() is PlaybackFailure.Unexpected)
    }

    @Test
    fun nonTransportPlayerFailureUsesTheAppRecoveryState() {
        val error =
            PlaybackException(
                "decoder failed",
                IllegalStateException("codec"),
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            )

        assertTrue(error.toPlaybackFailure() is PlaybackFailure.Unexpected)
    }
}
