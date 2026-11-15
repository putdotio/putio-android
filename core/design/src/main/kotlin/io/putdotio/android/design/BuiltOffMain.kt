package io.putdotio.android.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public val LocalBuildDispatcher: ProvidableCompositionLocal<CoroutineDispatcher> =
    staticCompositionLocalOf { Dispatchers.Default }

@Stable
public class Built<S : Any, V : Any> internal constructor(
    public val source: S,
    public val value: V,
    private val covers: S?,
) {
    public fun isBuiltFrom(source: S): Boolean = covers === source

    internal fun superseded(): Built<S, V> = Built(source, value, covers = null)
}

internal fun <S : Any, V : Any> Flow<S>.buildLatest(build: suspend (S, V?) -> V): Flow<Built<S, V>> = channelFlow {
    val lock = Mutex()
    var done: Built<S, V>? = null
    var building: S? = null
    var covers: S? = null
    var job: Job? = null
    collect { next ->
        lock.withLock {
            val finished = done
            when {
                job != null && building == next -> covers = next
                finished != null && finished.source == next -> {
                    job?.cancel()
                    job = null
                    building = null
                    val reused = Built(finished.source, finished.value, next)
                    done = reused
                    send(reused)
                }
                else -> {
                    job?.cancel()
                    building = next
                    covers = next
                    job = launch {
                        val value = build(next, finished?.value)
                        lock.withLock {
                            if (job !== coroutineContext.job) return@launch
                            val built = Built(next, value, covers)
                            done = built
                            building = null
                            job = null
                            send(built)
                        }
                    }
                }
            }
        }
    }
}

public class Reused<T : Any, R : Any> internal constructor(public val rows: List<R>, public val byItem: Map<T, R>)

public suspend fun <T : Any, R : Any> List<T>.buildReusing(built: Map<T, R>, build: (T) -> R): Reused<T, R> {
    val rows = ArrayList<R>(size)
    val byItem = HashMap<T, R>(size)
    forEach { item ->
        currentCoroutineContext().ensureActive()
        val row = built[item] ?: build(item)
        rows += row
        byItem[item] = row
    }
    return Reused(rows, byItem)
}

private class Submitted<S>(val source: S)

@Composable
public fun <S : Any, V : Any> rememberBuiltOffMain(
    source: S,
    vararg inputs: Any?,
    build: suspend (source: S, previous: V?) -> V,
): Built<S, V>? {
    val latest = remember { mutableStateOf(source, referentialEqualityPolicy()) }
    SideEffect { latest.value = source }
    val built = remember { mutableStateOf<Built<S, V>?>(null, referentialEqualityPolicy()) }
    val dispatcher = LocalBuildDispatcher.current
    val currentBuild = rememberUpdatedState(build)
    LaunchedEffect(dispatcher, *inputs) {
        built.value = built.value?.superseded()
        snapshotFlow { Submitted(latest.value) }
            .map { it.source }
            .buildLatest<S, V> { next, previous -> currentBuild.value(next, previous) }
            .flowOn(dispatcher)
            .collect { built.value = it }
    }
    return built.value
}
