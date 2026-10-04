package io.putdotio.android.tv.player

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.MaterialTheme
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.putdotio.android.design.putioTvDarkColorScheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsReducer
import io.putdotio.android.settings.AccountSettingsRequestId
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import io.putdotio.android.playback.playbackState

internal const val SOURCE_URL = "https://api.put.io/v2/files/9/hls/media.m3u8?token=t"
internal const val SAVED_SECONDS = 210f
internal const val DURATION_SECONDS = 840f
internal const val CONTINUE_LABEL = "Continue playing from 03:30"
private const val SETTLE_MILLIS = 50L

/** A few frames: the key's state change, then the recomposition it causes. */
internal fun ComposeContentTestRule.settle() {
    mainClock.advanceTimeBy(SETTLE_MILLIS)
    waitForIdle()
    mainClock.advanceTimeBy(SETTLE_MILLIS)
}

internal fun AndroidComposeTestRule<*, ComponentActivity>.back() {
    runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
    mainClock.advanceTimeBy(SETTLE_MILLIS)
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun ComposeContentTestRule.showReady(
    player: FakePlayer,
    resumePositionMillis: Long? = null,
    onBack: () -> Unit = {},
    playerFactory: TvPlayerFactory = TvPlayerFactory { _, _ -> player },
    lifecycleOwner: LifecycleOwner? = null,
) {
    mainClock.autoAdvance = false
    setContent {
        CompositionLocalProvider(LocalLifecycleOwner provides (lifecycleOwner ?: LocalLifecycleOwner.current)) {
            MaterialTheme(colorScheme = putioTvDarkColorScheme()) {
                TvPlayerScreen(
                    state = readyState(resumePositionMillis = resumePositionMillis),
                    onBack = onBack,
                    onRetry = {},
                    onResume = {},
                    onRestart = {},
                    onPlayerFailure = { _, _ -> },
                    playerFactory = playerFactory,
                )
            }
        }
    }
    settle()
    onNodeWithTag(TV_PLAYER_TAG).assertIsFocused()
}

internal fun reporting(writes: MutableList<Pair<Long, Double>>) = TvPlaybackReporting(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    settings = MutableStateFlow(
        AccountSettingsReducer.reduce(
            AccountSettingsReducer.start().state,
            AccountSettingsEvent.LoadSucceeded(
                AccountSettingsRequestId(1),
                AccountSettingsPreferences(
                    historyEnabled = true,
                    trashEnabled = true,
                    showSubtitles = true,
                    autoSelectSubtitles = true,
                    resumePlayback = true,
                ),
            ),
        ).state,
    ),
    sessionCurrent = { true },
    write = { fileId, seconds ->
        writes += fileId to seconds
        PlaybackRepositoryResult.Success(Unit)
    },
    onSaved = { _, _ -> },
)

internal fun readyState(
    name: String = "Sintel.mp4",
    resumePositionMillis: Long? = null,
    useStartFrom: Boolean = false,
) = playbackState(
    target = PlaybackTarget(FilesItemId(9), name, PlaybackMediaType.VIDEO),
    content = PlaybackContent.Ready(source(), useStartFrom),
    nextRequestValue = 2L,
    resumePositionMillis = resumePositionMillis,
)

internal fun source(startFromSeconds: Double = 0.0, fileId: Long = 9L) = PlaybackSource(
    fileId = fileId,
    kind = PlaybackSourceKind.HLS,
    url = PutioCredentialUrl::class.java
        .getDeclaredConstructor(String::class.java)
        .newInstance(SOURCE_URL.replace("/9/", "/$fileId/")),
    startFromSeconds = startFromSeconds,
    subtitles = PlaybackSubtitles.None,
)

@UnstableApi
internal class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private var state = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .addAll(
                    COMMAND_PLAY_PAUSE,
                    COMMAND_PREPARE,
                    COMMAND_SET_MEDIA_ITEM,
                    COMMAND_GET_CURRENT_MEDIA_ITEM,
                    COMMAND_GET_TIMELINE,
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    COMMAND_RELEASE,
                ).build(),
        ).build()
    val mediaItems = mutableListOf<MediaItem>()
    var startPositionMillis: Long? = null
    var prepared = false
    var released = false

    override fun getState(): State = state

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        this.mediaItems += mediaItems
        startPositionMillis = startPositionMs
        state = state.buildUpon()
            .setPlaylist(
                mediaItems.map {
                    MediaItemData.Builder(it.mediaId)
                        .setMediaItem(it)
                        .setDurationUs(DURATION_US)
                        .setIsSeekable(true)
                        .build()
                },
            )
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(startPositionMs)
            .build()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        state = state.buildUpon().setPlaybackState(STATE_READY).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        state = state.buildUpon().setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        state = state.buildUpon().setContentPositionMs(positionMs).build()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        return Futures.immediateVoidFuture()
    }

    fun advanceTo(positionMillis: Long) {
        // Pinned: a playing position would otherwise drift with however many frames the test runs.
        state = state.buildUpon().setContentPositionMs(PositionSupplier.getConstant(positionMillis)).build()
        invalidateState()
    }

    fun end() {
        state = state.buildUpon()
            .setContentPositionMs(PositionSupplier.getConstant(DURATION_US / 1_000L))
            .setPlaybackState(STATE_ENDED)
            .build()
        invalidateState()
    }

    fun fail(positionMillis: Long) {
        state = state.buildUpon()
            .setContentPositionMs(positionMillis)
            .setPlayerError(PlaybackException("Playback failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            .setPlaybackState(STATE_IDLE)
            .build()
        invalidateState()
    }

    private companion object {
        const val DURATION_US = 14L * 60L * 1_000_000L
    }
}
