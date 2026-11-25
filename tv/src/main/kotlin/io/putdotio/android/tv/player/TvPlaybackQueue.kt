package io.putdotio.android.tv.player

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackAnalyticsReporter
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFailure
import io.putdotio.android.playback.PlaybackFindNeighbours
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.awaitsFolderNeighbours
import io.putdotio.android.playback.neighboursOf
import io.putdotio.android.playback.preparePlayback
import io.putdotio.android.playback.previousAt
import io.putdotio.android.playback.toPlaybackFailure
import io.putdotio.sdk.files.PlaybackSource
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@MainThread
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Suppress("LongParameterList")
internal class TvPlaybackQueue(
    private val context: Context,
    private val factory: TvPlayerFactory,
    val reporter: TvPlaybackReporter,
    private val controller: PlaybackController,
    private val folder: TvFolderLookup,
    private val defaultAnalytics: PlaybackAnalyticsReporter,
    private val onFailure: (PlaybackFailure, Long) -> Unit,
    private val onQueueFinished: (() -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var current by mutableStateOf<Queued?>(null)
    private var loading: LoadingAudioPlayer? = null
    private var session: TvQueueSession? = null
    private var screen: Screen? = null
    private var ui: UiBinding? = null
    private var advancedFrom: Player? = null
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START, Lifecycle.Event.ON_STOP -> syncSession(controller.state.value)
            else -> Unit
        }
    }
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            (ui?.analytics ?: defaultAnalytics).reachedEnd()
            advanceIfEnded()
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = current?.player?.takeIf { it !== screen?.player } ?: return
            val handler = ui?.onFailure ?: onFailure
            handler(error.toPlaybackFailure(), player.currentPosition.coerceAtLeast(0L))
        }
    }

    val loadingPlayWhenReady: Boolean get() = loading?.playWhenReady ?: true

    val neighbours: StateFlow<PlaybackNeighbours?> = folder.neighbours

    private val isForeground: Boolean
        get() = ui?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true

    fun start() {
        if (started) return
        started = true
        scope.launch {
            controller.state.map { it.target }.distinctUntilChangedBy { it.fileId }.collectLatest(folder::lookUp)
        }
        scope.launch {
            controller.state.collect { playing ->
                val held = current
                val ready = playing.content is PlaybackContent.Ready
                when {
                    playing.content.isTerminalAudioState() -> {
                        drop()
                        if (ui == null) onQueueFinished?.invoke()
                    }
                    ready && playing.target.mediaType != PlaybackMediaType.AUDIO -> drop()
                    held != null && !ready && playing.target.fileId != held.fileId -> held.player.pause()
                }
                syncSession(playing)
            }
        }
        scope.launch { folder.neighbours.collect { advanceIfEnded() } }
        if (ui != null) syncSession(controller.state.value)
    }

    fun bindUi(
        lifecycle: Lifecycle,
        analytics: PlaybackAnalyticsReporter,
        onFailure: (PlaybackFailure, Long) -> Unit,
    ): Closeable {
        ui?.detach()
        val binding = UiBinding(lifecycle, analytics, onFailure)
        ui = binding
        lifecycle.addObserver(lifecycleObserver)
        if (started) syncSession(controller.state.value)
        return Closeable {
            binding.detach()
            if (ui === binding) ui = null
        }
    }

    fun playing(source: PlaybackSource): Player? =
        current?.takeIf { it.source == source && it.player.playerError == null }?.player

    fun take(source: PlaybackSource, target: PlaybackTarget, player: Player) {
        val previous = current
        val previousLoading = loading
        loading = null
        player.addListener(listener)
        val queued = Queued(source, target.fileId, player, reporter.observe(player))
        current = queued
        val published = tvSessionPlayer(player, onSkipRequested = ::skip, onPlayingRequested = queued::setPlaying)
        val activeSession = session
        when {
            activeSession != null -> activeSession.swap(published)
            isForeground -> session = factory.publishQueue(context, published)
        }
        previous?.release()
        previousLoading?.release()
    }

    fun attach(player: Player, skip: (Boolean) -> Unit, setPlaying: (Boolean) -> Unit): Closeable {
        val attached = Screen(player, skip, setPlaying)
        screen = attached
        return Closeable { if (screen === attached) screen = null }
    }

    fun close() {
        scope.cancel()
        ui?.detach()
        ui = null
        screen = null
        drop()
    }

    private fun drop() {
        session?.close()
        session = null
        loading?.release()
        loading = null
        current?.release()
        current = null
    }

    private fun syncSession(playing: PlaybackState) {
        if (playing.target.mediaType != PlaybackMediaType.AUDIO) return
        when {
            isForeground && session == null -> {
                val held = current
                if (held != null) {
                    val published = tvSessionPlayer(
                        held.player,
                        onSkipRequested = ::skip,
                        onPlayingRequested = held::setPlaying,
                    )
                    session = factory.publishQueue(context, published)
                } else {
                    val awaitingLoad = playing.content is PlaybackContent.Loading ||
                        playing.content is PlaybackContent.AwaitingResume
                    if (awaitingLoad && loading == null) {
                        val placeholder = LoadingAudioPlayer(playing.target)
                        loading = placeholder
                        val published = tvSessionPlayer(
                            placeholder,
                            onSkipRequested = null,
                            onPlayingRequested = { placeholder.playWhenReady = it },
                        )
                        session = factory.publishQueue(context, published)
                    }
                }
            }
            !isForeground && session != null -> {
                val ready = playing.content as? PlaybackContent.Ready
                if (ready != null && current?.source != ready.source) {
                    val target = playing.target
                    val prepared = ready.source.preparePlayback(
                        target.name,
                        target.mediaType,
                        playing.resumePositionMillis,
                    )
                    val player = factory.create(context, target.mediaType)
                    player.start(
                        prepared.mediaItem.leasedBy(reporter, target.fileId.value, ready.useStartFrom),
                        prepared.startPositionMillis,
                        playWhenReady = loading?.playWhenReady ?: true,
                    )
                    take(ready.source, target, player)
                }
            }
        }
    }

    private fun advanceIfEnded() {
        val playing = current ?: return
        val player = playing.player
        val neighbours = folder.of(playing.fileId) ?: return
        val ended = player.playbackState == Player.STATE_ENDED && player.playWhenReady
        if (ended) {
            val next = neighbours.next
            if (next != null) {
                if (advancedFrom !== player) {
                    advancedFrom = player
                    controller.dispatch(PlaybackEvent.NextChosen(next))
                }
            } else if (!isForeground) {
                onQueueFinished?.invoke()
            }
        }
    }

    private fun skip(forward: Boolean) {
        val playing = current ?: return
        val attached = screen?.takeIf { it.player === playing.player }
        if (attached != null) return attached.skip(forward)
        val neighbours = folder.of(playing.fileId)
        val position = playing.player.currentPosition.coerceAtLeast(0L)
        val target = if (forward) neighbours?.next else neighbours?.previousAt(position)
        val skipped = target != null && controller.dispatch(PlaybackEvent.NextChosen(target))
        if (!skipped && !forward) playing.player.seekTo(0L)
    }

    private inner class Queued(
        val source: PlaybackSource,
        val fileId: FilesItemId,
        val player: Player,
        private val positions: Closeable,
    ) {
        fun setPlaying(play: Boolean) {
            val attached = screen?.takeIf { it.player === player }
            when {
                attached != null -> attached.setPlaying(play)
                play -> player.play()
                else -> player.pause()
            }
        }

        fun release() {
            positions.close()
            player.removeListener(listener)
            player.release()
        }
    }

    private inner class UiBinding(
        val lifecycle: Lifecycle,
        val analytics: PlaybackAnalyticsReporter,
        val onFailure: (PlaybackFailure, Long) -> Unit,
    ) {
        fun detach() = lifecycle.removeObserver(lifecycleObserver)
    }

    private class Screen(val player: Player, val skip: (Boolean) -> Unit, val setPlaying: (Boolean) -> Unit)
}

internal class TvFolderLookup(
    private val state: StateFlow<PlaybackState>,
    private val find: PlaybackFindNeighbours?,
    private val onAuthenticationRequired: suspend () -> Unit,
) {
    private val known = MutableStateFlow<PlaybackNeighbours?>(null)
    private var knownFor: FilesItemId? = null

    val neighbours: StateFlow<PlaybackNeighbours?> = known.asStateFlow()

    fun of(fileId: FilesItemId): PlaybackNeighbours? = known.value?.takeIf { knownFor == fileId }

    suspend fun lookUp(target: PlaybackTarget) {
        known.value = null
        knownFor = target.fileId
        val lookup = find
        if (lookup == null) {
            known.value = PlaybackNeighbours()
            return
        }
        state.first { it.awaitsFolderNeighbours }
        known.value = lookup.neighboursOf(target, onAuthenticationRequired)
    }
}

internal class TvHeldPlayer(val player: Player, val queue: TvPlaybackQueue?, private val adopted: Boolean) {
    fun playsAtStart(startsPlaying: Boolean): Boolean = if (adopted) player.playWhenReady else startsPlaying

    fun configure(apply: () -> Unit) {
        if (!adopted) apply()
    }

    fun start(item: MediaItem, startPositionMillis: Long, playWhenReady: Boolean) {
        if (!adopted) player.start(item, startPositionMillis, playWhenReady)
    }

    fun publish(
        source: PlaybackSource,
        target: PlaybackTarget,
        skip: (Boolean) -> Unit,
        setPlaying: (Boolean) -> Unit,
        own: () -> Closeable,
    ): Closeable {
        val queue = queue ?: return own()
        if (!adopted) queue.take(source, target, player)
        return queue.attach(player, skip, setPlaying)
    }
}

@Composable
internal fun rememberHeldPlayer(
    queue: TvPlaybackQueue?,
    source: PlaybackSource,
    factory: TvPlayerFactory,
    mediaType: PlaybackMediaType,
): TvHeldPlayer {
    val context = LocalContext.current
    val queued = queue?.playing(source)
    val held = remember(source, factory, queue) {
        TvHeldPlayer(queued ?: factory.create(context, mediaType), queue, adopted = queued != null)
    }
    return remember(held, queued) {
        if (queued == null || queued === held.player) held else TvHeldPlayer(queued, queue, adopted = true)
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun TvPlaybackQueue?.startsPlaying(stoppedAtMillis: Long?): Boolean =
    stoppedAtMillis == null && (this == null || loadingPlayWhenReady)

private fun Player.start(item: MediaItem, startPositionMillis: Long, playWhenReady: Boolean) {
    setMediaItem(item, startPositionMillis)
    this.playWhenReady = playWhenReady
    prepare()
}

private fun PlaybackContent.isTerminalAudioState(): Boolean =
    when (this) {
        is PlaybackContent.Failed,
        is PlaybackContent.Unsupported,
        is PlaybackContent.Conversion,
        PlaybackContent.Ended,
        -> true
        else -> false
    }
