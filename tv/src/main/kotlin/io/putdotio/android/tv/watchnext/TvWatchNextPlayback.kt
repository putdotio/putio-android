package io.putdotio.android.tv.watchnext

import io.putdotio.android.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class TvWatchNextPlayback(
    private val recorder: TvWatchNextRecorder,
    private val scope: CoroutineScope,
) {
    private val played = object : LinkedHashMap<Long, TvWatchNextMedia>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TvWatchNextMedia>?): Boolean =
            size > PLAYED_LIMIT
    }
    private var following: Job? = null

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

    fun saved(fileId: Long, seconds: Double) {
        played[fileId]?.let { recorder.positionSaved(it, seconds) }
    }

    fun hidden(fileId: Long) {
        played.remove(fileId)
        recorder.fileGone(fileId)
    }
}

private const val PLAYED_LIMIT = 8
