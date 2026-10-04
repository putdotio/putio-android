package io.putdotio.android.tv.watchnext

import io.putdotio.android.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Pairs a session's saved playback positions with the video they belong to. A position write
 * can land after playback moved on (autoplay) or ended, so each played target is remembered
 * by id, as many as the position writer keeps leases for.
 */
internal class TvWatchNextPlayback(
    private val recorder: TvWatchNextRecorder,
    private val scope: CoroutineScope,
) {
    private val played = object : LinkedHashMap<Long, TvWatchNextMedia>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TvWatchNextMedia>?): Boolean =
            size > PLAYED_LIMIT
    }
    private var following: Job? = null

    /** Follows [controller]'s target: autoplay and a looked-up duration change it. */
    fun follow(controller: PlaybackController) {
        following?.cancel()
        following = scope.launch {
            controller.state.map { it.target }.distinctUntilChanged().collect { target ->
                played[target.fileId.value] = target.toWatchNextMedia()
            }
        }
    }

    fun stopFollowing() {
        following?.cancel()
        following = null
    }

    /** put.io saved [seconds] for [fileId]; a file this session did not play says nothing. */
    fun saved(fileId: Long, seconds: Double) {
        played[fileId]?.let { recorder.positionSaved(it, seconds) }
    }
}

private const val PLAYED_LIMIT = 8
