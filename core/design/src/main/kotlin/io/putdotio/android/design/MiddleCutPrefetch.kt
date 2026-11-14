package io.putdotio.android.design

import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Stable
public class PutioMiddleCuts internal constructor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val jobs = HashMap<MiddleCutRoom, Job>()
    private var names: List<String> = emptyList()

    internal fun seen(room: MiddleCutRoom) {
        if (room.maxLines == 1 && room !in jobs) jobs[room] = prefetch(room, names)
    }

    internal fun names(names: List<String>) {
        if (names === this.names) return
        val added = names - this.names.toSet()
        this.names = names
        if (added.isEmpty()) return
        jobs.keys.forEach { room -> jobs[room] = prefetch(room, added, after = jobs[room]) }
    }

    private fun prefetch(room: MiddleCutRoom, names: List<String>, after: Job? = null): Job =
        scope.launch(dispatcher) {
            after?.join()
            val measurer = TextMeasurer(room.fontFamilyResolver, room.scale, room.layoutDirection, cacheSize = 0)
            names.forEach { name ->
                ensureActive()
                room.fitMiddleCut(name.oneLine(), room.maxWidth, measurer, room.style, room.maxLines)
            }
        }
}

@Composable
public fun rememberPutioMiddleCuts(names: List<String>): PutioMiddleCuts {
    val scope = rememberCoroutineScope()
    val cuts = remember(scope) { PutioMiddleCuts(scope, MiddleCutDispatcher) }
    SideEffect { cuts.names(names) }
    return cuts
}

public val LocalPutioMiddleCuts: ProvidableCompositionLocal<PutioMiddleCuts?> = staticCompositionLocalOf { null }

internal data class MiddleCutRoom(
    val maxWidth: Int,
    val style: TextStyle,
    val maxLines: Int,
    val scale: Density,
    override val layoutDirection: LayoutDirection,
    val fontFamilyResolver: FontFamily.Resolver,
) : IntrinsicMeasureScope, Density by scale

private val MiddleCutDispatcher: CoroutineDispatcher by lazy {
    Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "putio-middle-cuts").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
