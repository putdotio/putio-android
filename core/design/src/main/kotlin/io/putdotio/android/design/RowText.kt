package io.putdotio.android.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Stable
public class RowText<T : Any, V> internal constructor(
    private val key: (T) -> Any,
    private val placeholder: V,
    private val build: (T) -> V,
) {
    private class Entry<T, V>(val item: T, val value: V, val refresh: Any?)

    @Volatile
    private var entries = ConcurrentHashMap<Any, Entry<T, V>>()

    private val waiting = ConcurrentHashMap<Any, T>()

    private val prepared = mutableIntStateOf(0)

    private val generation = AtomicLong()

    private val stale = AtomicBoolean()

    public operator fun get(item: T): V {
        prepared.intValue
        val key = key(item)
        val entry = entries[key]?.takeIf { it.item == item } ?: await(key, item)
        return entry?.value ?: placeholder
    }

    private fun await(key: Any, item: T): Entry<T, V>? {
        waiting[key] = item
        val entry = entries[key]
        if (entry?.item == item) waiting.remove(key, item)
        return entry
    }

    internal suspend fun prepare(items: List<T>, refresh: Any?) {
        val run = generation.incrementAndGet()
        val known = entries
        val built = ConcurrentHashMap<Any, Entry<T, V>>(items.size)
        var changed = false
        items.forEach { item ->
            currentCoroutineContext().ensureActive()
            val old = known[key(item)]?.takeIf { it.item == item }
            built[key(item)] = old?.takeIf { it.refresh == refresh }
                ?: Entry(item, build(item), refresh).also { if (old != null && old.value != it.value) changed = true }
        }
        val context = currentCoroutineContext()
        synchronized(generation) {
            if (run != generation.get() || !context.isActive) return
            entries = built
            if (waiting.entries.removeIf { (key, item) -> built[key]?.item == item } || changed) stale.set(true)
        }
    }

    internal fun recomposeStaleRows() {
        if (stale.getAndSet(false)) prepared.intValue++
    }
}

@Composable
public fun <T : Any, V> rememberRowText(
    items: List<T>,
    vararg inputs: Any?,
    key: (T) -> Any,
    placeholder: V,
    refresh: Any? = null,
    everyMinute: Boolean = false,
    build: (T) -> V,
): RowText<T, V> {
    val text = remember(*inputs) { RowText(key, placeholder, build) }
    LaunchedEffect(text, items, refresh, everyMinute) {
        text.recomposeStaleRows()
        while (isActive) {
            val minute = currentMinute()
            withContext(Dispatchers.Default) { text.prepare(items, if (everyMinute) refresh to minute else refresh) }
            withFrameNanos {}
            text.recomposeStaleRows()
            if (!everyMinute) break
            if (currentMinute() == minute) delay(MINUTE_MILLIS - System.currentTimeMillis() % MINUTE_MILLIS)
        }
    }
    return text
}

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
        val reused = lock.withLock {
            val finished = done
            when {
                job != null && building == next -> {
                    covers = next
                    null
                }
                finished != null && finished.source == next -> {
                    job?.cancel()
                    job = null
                    building = null
                    Built(finished.source, finished.value, next).also { done = it }
                }
                else -> {
                    job?.cancel()
                    building = next
                    covers = next
                    job = launch {
                        val value = build(next, finished?.value)
                        lock.withLock {
                            if (building !== next) return@launch
                            val built = Built(next, value, covers)
                            done = built
                            building = null
                            job = null
                            send(built)
                        }
                    }
                    null
                }
            }
        }
        if (reused != null) send(reused)
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

private fun currentMinute(): Long = System.currentTimeMillis() / MINUTE_MILLIS

private const val MINUTE_MILLIS = 60_000L
