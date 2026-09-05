package io.putdotio.android

import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.Player as Media3Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
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
    fun nextVideoLookupFailureOffersRetry() {
        var retries = 0
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state =
                        state(
                            PlaybackContent.NextFailed(
                                PlaybackFailure.NetworkUnavailable(IllegalStateException("offline")),
                            ),
                        ),
                    onRetry = { retries += 1 },
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("Couldn’t find the next video").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
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
                videoSource(),
            ),
        )
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = state(content).copy(resumePositionMillis = 12_345L),
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
            assertEquals(12_345L, players.single().currentPosition)
            assertTrue(players.single().playWhenReady)
            assertFalse(players.single().trackSelectionParameters.selectTextByDefault)
            assertFalse(C.TRACK_TYPE_TEXT in players.single().trackSelectionParameters.disabledTrackTypes)
        }

        compose.runOnIdle {
            content =
                PlaybackContent.Ready(
                    videoSource().copy(
                        url = credentialUrl("https://example.com/replaced-video.mp4"),
                        startFromSeconds = 0.0,
                    ),
                )
        }
        compose.runOnIdle {
            assertEquals(1, players.size)
            assertEquals(2, players.single().mediaItemUpdates)
        }

        compose.runOnIdle {
            players.single().movePositionTo(54_321L)
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
                    videoSource(),
                )
        }
        compose.onNodeWithTag(MOBILE_VIDEO_PLAYER_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, players.size)
            assertFalse(players.last().released)
            assertEquals(1, players.last().mediaItemUpdates)
            assertEquals(54_321L, players.last().currentPosition)
        }
        val readyContent = content
        compose.runOnIdle {
            players.last().movePositionTo(0L)
            players.last().fail(PlaybackException("decoder failed", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
        }
        compose.onNodeWithText("put.io is temporarily unavailable. Try again.").assertIsDisplayed()
        compose.runOnIdle { content = readyContent }
        compose.runOnIdle {
            assertEquals(3, players.size)
            assertEquals(0L, players.last().currentPosition)
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
    fun endedPlayerRequestsAutoplayOnceOnlyWhenEnabled() {
        lateinit var player: RecordingPlayer
        var autoplayEnabled by mutableStateOf(true)
        var endedCalls = 0
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = state(
                        PlaybackContent.Ready(
                            PlaybackSource(
                                fileId = Target.fileId.value,
                                kind = PlaybackSourceKind.MP4,
                                url = credentialUrl("https://example.com/video.mp4"),
                                startFromSeconds = 0.0,
                                subtitles = PlaybackSubtitles.None,
                            ),
                        ),
                    ),
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    autoplayNextVideo = autoplayEnabled,
                    onPlaybackEnded = { endedCalls += 1 },
                    playerFactory = MobilePlayerFactory { RecordingPlayer().also { player = it } },
                )
            }
        }
        compose.onNodeWithTag(MOBILE_VIDEO_PLAYER_TAG).assertIsDisplayed()

        compose.runOnIdle {
            player.updatePlaybackState(Media3Player.STATE_ENDED)
            player.updatePlaybackState(Media3Player.STATE_ENDED)
        }
        compose.runOnIdle { assertEquals(1, endedCalls) }

        compose.runOnIdle {
            player.updatePlaybackState(Media3Player.STATE_READY)
            autoplayEnabled = false
        }
        compose.runOnIdle { player.updatePlaybackState(Media3Player.STATE_ENDED) }
        compose.runOnIdle { assertEquals(1, endedCalls) }
    }

    @Test
    fun visibleSeekControlsAccumulateAndClampAtTheDuration() {
        lateinit var player: RecordingPlayer
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = readyState(startFromSeconds = 12.345),
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    playerFactory =
                        MobilePlayerFactory {
                            RecordingPlayer(durationMillis = 30_000L).also { player = it }
                        },
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            player.updatePlaybackState(Media3Player.STATE_BUFFERING)
            player.updatePlaybackState(Media3Player.STATE_READY)
            assertTrue(
                "duration=${player.duration}, seekable=${player.isCurrentMediaItemSeekable}, " +
                    "live=${player.isCurrentMediaItemLive}, commands=${player.availableCommands}",
                player.currentSeekWindow().available,
            )
        }
        compose.onNodeWithContentDescription("Forward 10 seconds")
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithContentDescription("Forward 10 seconds").performClick()

        compose.runOnIdle { assertEquals(listOf(22_345L, 30_000L), player.seekPositions) }
    }

    @Test
    fun doubleTapSeeksBySideAndSingleTapStillTogglesControls() {
        lateinit var player: RecordingPlayer
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = readyState(startFromSeconds = 20.0),
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    playerFactory =
                        MobilePlayerFactory {
                            RecordingPlayer(durationMillis = 60_000L).also { player = it }
                        },
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            player.updatePlaybackState(Media3Player.STATE_BUFFERING)
            player.updatePlaybackState(Media3Player.STATE_READY)
        }

        compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput {
            click(percentOffset(0.5f, 0.25f))
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.onAllNodesWithTag(MOBILE_SEEK_FORWARD_TAG).assertCountEquals(0)

        compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput {
            doubleClick(percentOffset(0.75f, 0.25f))
            doubleClick(percentOffset(0.25f, 0.25f))
        }

        compose.runOnIdle { assertEquals(listOf(30_000L, 20_000L), player.seekPositions) }
    }

    @Test
    fun doubleTapStillAccumulatesWhenPlaybackBuffersBetweenTaps() {
        lateinit var player: RecordingPlayer
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = readyState(startFromSeconds = 20.0),
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    playerFactory =
                        MobilePlayerFactory {
                            RecordingPlayer(durationMillis = 60_000L).also { player = it }
                        },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            player.updatePlaybackState(Media3Player.STATE_BUFFERING)
            player.updatePlaybackState(Media3Player.STATE_READY)
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val seekWindow = player.currentSeekWindow()
        assertTrue(seekWindow.available)

        compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput {
            click(percentOffset(0.5f, 0.25f))
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.onAllNodesWithTag(MOBILE_SEEK_FORWARD_TAG).assertCountEquals(0)

        compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput {
            doubleClick(percentOffset(0.75f, 0.25f))
        }
        compose.runOnIdle { assertEquals(listOf(30_000L), player.seekPositions) }

        compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput {
            click(percentOffset(0.75f, 0.25f))
        }
        compose.runOnIdle { player.updatePlaybackState(Media3Player.STATE_BUFFERING) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(Media3Player.STATE_BUFFERING, player.playbackState)
            assertEquals(seekWindow, player.currentSeekWindow())
        }
        compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput {
            advanceEventTime(64L)
            click(percentOffset(0.75f, 0.25f))
        }

        compose.runOnIdle { assertEquals(listOf(30_000L, 40_000L), player.seekPositions) }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Forward 20 seconds").assertIsDisplayed()
    }

    @Test
    fun accumulatedSeekFeedbackIsVisible() {
        compose.setContent {
            PutioTheme {
                MobileSeekFeedback(
                    PendingSeek(
                        direction = SeekDirection.Forward,
                        targetPositionMillis = 30_000L,
                        accumulatedMillis = 20_000L,
                        requestId = 2L,
                    ),
                )
            }
        }

        compose
            .onNodeWithText("Forward 20 seconds")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun subSecondSeekFeedbackAnnouncesTheBoundaryMovement() {
        compose.setContent {
            PutioTheme {
                MobileSeekFeedback(
                    PendingSeek(
                        direction = SeekDirection.Forward,
                        targetPositionMillis = 30_000L,
                        accumulatedMillis = 500L,
                        requestId = 1L,
                    ),
                )
            }
        }

        compose
            .onNodeWithText("Forward less than 1 second")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun nonSeekableMediaDisablesControlsAndIgnoresDoubleTap() {
        lateinit var player: RecordingPlayer
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = readyState(startFromSeconds = 12.345),
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    playerFactory =
                        MobilePlayerFactory {
                            RecordingPlayer(seekable = false).also { player = it }
                        },
                )
            }
        }

        compose.waitForIdle()
        compose.runOnIdle {
            player.updatePlaybackState(Media3Player.STATE_BUFFERING)
            player.updatePlaybackState(Media3Player.STATE_READY)
            assertFalse(player.currentSeekWindow().available)
            assertEquals(60_000L, player.duration)
            assertFalse(player.isCurrentMediaItemSeekable)
        }
        compose.onNodeWithTag(MOBILE_SEEK_BACK_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_SEEK_FORWARD_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput {
            doubleClick(percentOffset(0.75f, 0.25f))
        }
        compose.runOnIdle { assertTrue(player.seekPositions.isEmpty()) }
    }

    @Test
    fun seekRefreshesPendingFeedbackBeforeBatchedWindowEvents() {
        val player = RecordingPlayer()
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = readyState(startFromSeconds = 20.0),
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    playerFactory = MobilePlayerFactory { player },
                )
            }
        }
        compose.runOnIdle {
            player.pause()
            player.movePositionTo(20_000L)
        }
        compose.onNodeWithTag(MOBILE_SEEK_FORWARD_TAG).performClick()
        compose.onNodeWithTag(MOBILE_SEEK_FEEDBACK_TAG).assertIsDisplayed()
        val seekForward = requireNotNull(
            compose.onNodeWithTag(MOBILE_SEEK_FORWARD_TAG)
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action,
        )
        var windowEventDelivered = false
        compose.runOnIdle {
            player.addListener(object : Media3Player.Listener {
                override fun onEvents(player: Media3Player, events: Media3Player.Events) {
                    if (events.contains(Media3Player.EVENT_TIMELINE_CHANGED)) windowEventDelivered = true
                }
            })
            player.updateSeekWindow(durationMillis = 45_000L, seekable = true)
            assertEquals(45_000L, player.duration)
            assertFalse(windowEventDelivered)
            seekForward()
            assertFalse(windowEventDelivered)
        }

        compose.runOnIdle {
            assertTrue(windowEventDelivered)
            assertEquals(listOf(30_000L, 40_000L), player.seekPositions)
        }
        compose.onNodeWithTag(MOBILE_SEEK_FEEDBACK_TAG)
            .assertTextEquals("Forward 10 seconds")
    }

    @Test
    fun playerWindowEventsClearFeedbackAndDisableUnavailableSeeking() {
        val player = RecordingPlayer()
        compose.setContent {
            PutioTheme {
                MobileVideoPlayerScreen(
                    state = readyState(startFromSeconds = 20.0),
                    onRetry = {},
                    onPlayerFailure = { _, _ -> },
                    onBack = {},
                    playerFactory = MobilePlayerFactory { player },
                )
            }
        }
        compose.runOnIdle {
            player.pause()
            player.movePositionTo(20_000L)
        }
        compose.onNodeWithTag(MOBILE_SEEK_FORWARD_TAG).performClick()
        compose.onNodeWithTag(MOBILE_SEEK_FEEDBACK_TAG).assertIsDisplayed()
        compose.runOnIdle { player.updateSeekWindow(durationMillis = 45_000L, seekable = true) }
        compose.onAllNodesWithTag(MOBILE_SEEK_FEEDBACK_TAG).assertCountEquals(0)
        compose.onNodeWithTag(MOBILE_SEEK_FORWARD_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(MOBILE_SEEK_FEEDBACK_TAG).assertIsDisplayed()

        compose.runOnIdle { player.updateSeekWindow(durationMillis = 45_000L, seekable = false) }
        compose.onAllNodesWithTag(MOBILE_SEEK_FEEDBACK_TAG).assertCountEquals(0)
        compose.onNodeWithTag(MOBILE_SEEK_BACK_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_SEEK_FORWARD_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput {
            doubleClick(percentOffset(0.75f, 0.25f))
        }
        compose.runOnIdle { assertEquals(listOf(30_000L, 40_000L), player.seekPositions) }
    }

    @Test
    fun rtlSeekFeedbackStaysOnThePhysicalTappedSide() {
        val player = RecordingPlayer()
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                PutioTheme {
                    MobileVideoPlayerScreen(
                        state = readyState(startFromSeconds = 20.0),
                        onRetry = {},
                        onPlayerFailure = { _, _ -> },
                        onBack = {},
                        playerFactory = MobilePlayerFactory { player },
                    )
                }
            }
        }
        compose.runOnIdle {
            player.pause()
            player.movePositionTo(20_000L)
        }
        compose.waitForIdle()
        val centerX = compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG)
            .fetchSemanticsNode().boundsInRoot.center.x
        compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput {
            doubleClick(percentOffset(0.25f, 0.25f))
        }
        compose.runOnIdle { assertEquals(listOf(10_000L), player.seekPositions) }
        assertTrue(
            compose.onNodeWithTag(MOBILE_SEEK_FEEDBACK_TAG)
                .fetchSemanticsNode().boundsInRoot.center.x < centerX,
        )

        compose.onNodeWithTag(MOBILE_PLAYER_GESTURE_TAG).performTouchInput {
            doubleClick(percentOffset(0.75f, 0.25f))
        }
        compose.runOnIdle { assertEquals(listOf(10_000L, 20_000L), player.seekPositions) }
        assertTrue(
            compose.onNodeWithTag(MOBILE_SEEK_FEEDBACK_TAG)
                .fetchSemanticsNode().boundsInRoot.center.x > centerX,
        )
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

    private fun readyState(startFromSeconds: Double): PlaybackState =
        state(
            PlaybackContent.Ready(
                PlaybackSource(
                    fileId = Target.fileId.value,
                    kind = PlaybackSourceKind.MP4,
                    url = credentialUrl("https://example.com/video.mp4"),
                    startFromSeconds = startFromSeconds,
                    subtitles = PlaybackSubtitles.None,
                ),
            ),
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
internal class RecordingPlayer(
    private val releaseError: PlaybackException? = null,
    private val durationMillis: Long = 60_000L,
    private val seekable: Boolean = true,
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
    val seekPositions = mutableListOf<Long>()

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

    fun updatePlaybackState(playbackState: Int) {
        state = state.buildUpon().setPlaybackState(playbackState).build()
        invalidateState()
    }

    fun updateSeekWindow(durationMillis: Long, seekable: Boolean) {
        state = state.buildUpon()
            .setPlaylist(state.playlist.map { item ->
                item.buildUpon()
                    .setDurationUs(durationMillis * 1_000L)
                    .setIsSeekable(seekable)
                    .build()
            })
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
                    .setDurationUs(durationMillis * 1_000L)
                    .setIsSeekable(seekable)
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

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        seekPositions += positionMs
        state = state.buildUpon().setContentPositionMs(positionMs).build()
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@UnstableApi
class MobileVideoPlayerCodecTest {
    @Test
    fun pendingSeekAccumulatesRequestedStepsFromThePendingTargetAndClamps() {
        val first =
            requireNotNull(
                nextPendingSeek(
                    previous = null,
                    currentPositionMillis = 95_000L,
                    durationMillis = 100_000L,
                    direction = SeekDirection.Forward,
                    requestId = 1L,
                ),
        )
        assertEquals(100_000L, first.targetPositionMillis)
        assertEquals(5_000L, first.accumulatedMillis)

        assertNull(
            nextPendingSeek(
                previous = first,
                currentPositionMillis = 95_000L,
                durationMillis = 100_000L,
                direction = SeekDirection.Forward,
                requestId = 2L,
            ),
        )

        val reversed =
            requireNotNull(
                nextPendingSeek(
                    previous = first,
                    currentPositionMillis = 95_000L,
                    durationMillis = 100_000L,
                    direction = SeekDirection.Backward,
                    requestId = 3L,
                ),
            )
        assertEquals(90_000L, reversed.targetPositionMillis)
        assertEquals(10_000L, reversed.accumulatedMillis)
        val clampedBackward =
            requireNotNull(
                nextPendingSeek(
                    previous = null,
                    currentPositionMillis = 5_000L,
                    durationMillis = 100_000L,
                    direction = SeekDirection.Backward,
                    requestId = 4L,
                ),
        )
        assertEquals(0L, clampedBackward.targetPositionMillis)
        assertEquals(5_000L, clampedBackward.accumulatedMillis)
        assertNull(
            nextPendingSeek(
                previous = null,
                currentPositionMillis = 1L,
                durationMillis = 0L,
                direction = SeekDirection.Backward,
                requestId = 5L,
            ),
        )
    }

    @Test
    fun seekWindowRejectsUnreadableUnknownLiveNonSeekableAndUnavailableMedia() {
        assertTrue(
            playerSeekWindow(
                canReadCurrentItem = true,
                durationMillis = 60_000L,
                seekable = true,
                live = false,
                canSeek = true,
            ).available,
        )
        listOf(
            playerSeekWindow(false, 60_000L, seekable = true, live = false, canSeek = true),
            playerSeekWindow(true, C.TIME_UNSET, seekable = true, live = false, canSeek = true),
            playerSeekWindow(true, 60_000L, seekable = true, live = true, canSeek = true),
            playerSeekWindow(true, 60_000L, seekable = false, live = false, canSeek = true),
            playerSeekWindow(true, 60_000L, seekable = true, live = false, canSeek = false),
        ).forEach { window ->
            assertFalse(window.available)
        }
    }

    @Test
    fun pendingSeekClearsWhenTheSeekWindowDurationChangesOrBecomesUnavailable() {
        val pending =
            PendingSeek(
                direction = SeekDirection.Forward,
                targetPositionMillis = 100_000L,
                accumulatedMillis = 5_000L,
                requestId = 1L,
            )
        val initialWindow = PlayerSeekWindow(available = true, durationMillis = 100_000L)

        assertEquals(
            pending,
            pendingSeekAfterWindowUpdate(
                pending = pending,
                previousWindow = initialWindow,
                updatedWindow = initialWindow,
            ),
        )
        assertNull(
            pendingSeekAfterWindowUpdate(
                pending = pending,
                previousWindow = initialWindow,
                updatedWindow = PlayerSeekWindow(available = true, durationMillis = 80_000L),
            ),
        )
        assertNull(
            pendingSeekAfterWindowUpdate(
                pending = pending,
                previousWindow = initialWindow,
                updatedWindow = PlayerSeekWindow(available = false, durationMillis = 0L),
            ),
        )
    }

    @Test
    fun mobileVideoFactoryAppliesMovieAudioAttributes() {
        val player = DefaultMobilePlayerFactory.create(ApplicationProvider.getApplicationContext())
        try {
            assertEquals(C.AUDIO_CONTENT_TYPE_MOVIE, player.audioAttributes.contentType)
            assertEquals(C.USAGE_MEDIA, player.audioAttributes.usage)
        } finally {
            player.release()
        }
    }

    @Test
    fun subtitleFrameMatchesTheFittedVideoSurface() {
        assertEquals(16f / 9f, VideoSize(1_920, 1_080).displayAspectRatioOrNull())
        assertEquals(FittedVideoSize(1_080, 608), fitInside(1_080, 2_160, 16f / 9f))
        assertEquals(FittedVideoSize(1_080, 1_920), fitInside(1_080, 2_160, 9f / 16f))
    }

    @Test
    fun activeNonTouchInteractionPreventsControlAutoHide() {
        val player = RecordingPlayer()
        try {
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri("https://example.com/video.mp4"))
            player.prepare()
            player.playWhenReady = true
            player.updatePlaybackState(Media3Player.STATE_READY)
            assertTrue(
                player.controlsShouldAutoHide(
                    controlsVisible = true,
                    pointerInteracting = false,
                    keyboardNavigationActive = false,
                    menuOpen = false,
                    touchExplorationEnabled = false,
                ),
            )
            assertFalse(
                player.controlsShouldAutoHide(
                    controlsVisible = true,
                    pointerInteracting = false,
                    keyboardNavigationActive = true,
                    menuOpen = false,
                    touchExplorationEnabled = false,
                ),
            )
            assertFalse(
                player.controlsShouldAutoHide(
                    controlsVisible = true,
                    pointerInteracting = false,
                    keyboardNavigationActive = false,
                    menuOpen = false,
                    touchExplorationEnabled = true,
                ),
            )
            player.updatePlaybackState(Media3Player.STATE_ENDED)
            assertFalse(
                player.controlsShouldAutoHide(
                    controlsVisible = true,
                    pointerInteracting = false,
                    keyboardNavigationActive = false,
                    menuOpen = false,
                    touchExplorationEnabled = false,
                ),
            )
            assertTrue(controlsVisibleForTouchExploration(false, true))
            assertTrue(controlsVisibleForPlaybackState(false, Media3Player.STATE_ENDED))
        } finally {
            player.release()
        }
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
