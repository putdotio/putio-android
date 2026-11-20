package io.putdotio.android.tv.player

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackContent
import io.putdotio.android.playback.PlaybackController
import io.putdotio.android.playback.PlaybackEvent
import io.putdotio.android.playback.PlaybackFindNeighbours
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackState
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.playback.awaitsFolderNeighbours
import io.putdotio.android.playback.neighboursOf
import io.putdotio.android.playback.preparePlayback
import io.putdotio.android.playback.previousAt
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@MainThread
internal class TvPlaybackQueue(
    private val context: Context,
    private val factory: TvPlayerFactory,
    private val reporter: TvPlaybackReporter,
    private val lifecycle: Lifecycle,
    private val controller: PlaybackController,
    private val folder: TvFolderLookup,
) : RememberObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var current by mutableStateOf<Queued?>(null)
    private var session: TvQueueSession? = null
    private var screen: Screen? = null
    private var advancedFrom: Player? = null
    private val stopped = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) loadWhileStopped(controller.state.value)
    }
    private val endListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) advanceIfEnded()
        }
    }

    val neighbours: StateFlow<PlaybackNeighbours?> = folder.neighbours

    fun playing(source: PlaybackSource): Player? =
        current?.takeIf { it.source == source && it.player.playerError == null }?.player

    fun take(source: PlaybackSource, target: PlaybackTarget, player: Player) {
        val previous = current
        player.addListener(endListener)
        current = Queued(source, target.fileId, player, reporter.observe(player))
        val published = tvSessionPlayer(player, onSkipRequested = ::skip, onPlayingRequested = ::setPlaying)
        session?.swap(published) ?: run { session = factory.publishQueue(context, published) }
        previous?.release()
    }

    fun attach(player: Player, skip: (Boolean) -> Unit, setPlaying: (Boolean) -> Unit): Closeable {
        val attached = Screen(player, skip, setPlaying)
        screen = attached
        return Closeable { if (screen === attached) screen = null }
    }

    override fun onRemembered() {
        lifecycle.addObserver(stopped)
        scope.launch { controller.state.map { it.target }.distinctUntilChanged().collectLatest(folder::lookUp) }
        scope.launch {
            controller.state.collect { playing ->
                follow(playing)
                loadWhileStopped(playing)
            }
        }
        scope.launch { folder.neighbours.collect { advanceIfEnded() } }
    }

    override fun onAbandoned() = onForgotten()

    override fun onForgotten() {
        scope.cancel()
        lifecycle.removeObserver(stopped)
        screen = null
        drop()
    }

    private fun drop() {
        session?.close()
        session = null
        current?.release()
        current = null
    }

    private fun follow(playing: PlaybackState) {
        val held = current ?: return
        when (playing.content) {
            is PlaybackContent.Failed -> drop()
            is PlaybackContent.Ready -> if (playing.target.mediaType != PlaybackMediaType.AUDIO) drop()
            else -> if (playing.target.fileId != held.fileId) held.player.pause()
        }
    }

    private fun loadWhileStopped(playing: PlaybackState) {
        val ready = playing.content as? PlaybackContent.Ready ?: return
        val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (started || playing.target.mediaType != PlaybackMediaType.AUDIO || current?.source == ready.source) return
        val target = playing.target
        val prepared = ready.source.preparePlayback(target.name, target.mediaType, playing.resumePositionMillis)
        val player = factory.create(context, target.mediaType)
        player.start(
            prepared.mediaItem.leasedBy(reporter, target.fileId.value, ready.useStartFrom),
            prepared.startPositionMillis,
            playWhenReady = true,
        )
        take(ready.source, target, player)
    }

    private fun advanceIfEnded() {
        val playing = current ?: return
        val player = playing.player
        val next = folder.of(playing.fileId)?.next
        val ended = player.playbackState == Player.STATE_ENDED && player.playWhenReady
        if (next != null && ended && advancedFrom !== player) {
            advancedFrom = player
            controller.dispatch(PlaybackEvent.NextChosen(next))
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

    private fun setPlaying(play: Boolean) {
        val playing = current ?: return
        val attached = screen?.takeIf { it.player === playing.player }
        when {
            attached != null -> attached.setPlaying(play)
            play -> playing.player.play()
            else -> playing.player.pause()
        }
    }

    private inner class Queued(
        val source: PlaybackSource,
        val fileId: FilesItemId,
        val player: Player,
        private val positions: Closeable,
    ) {
        fun release() {
            positions.close()
            player.removeListener(endListener)
            player.release()
        }
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

private fun Player.start(item: MediaItem, startPositionMillis: Long, playWhenReady: Boolean) {
    setMediaItem(item, startPositionMillis)
    this.playWhenReady = playWhenReady
    prepare()
}
