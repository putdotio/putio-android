package io.putdotio.android.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Stable
public class RowText<T : Any, V> internal constructor(
    private val key: (T) -> Any,
    private val placeholder: V,
    private val build: (T) -> V,
) {
    internal class Entry<T, V>(val item: T, val value: V, val refresh: Any?)

    private class Prepared<T, V>(
        val items: List<T>,
        val entries: Map<Any, Entry<T, V>>,
        val previous: Map<Any, Entry<T, V>>,
    )

    internal class Batch<T, V> internal constructor(
        internal val items: List<T>,
        internal val entries: Map<Any, Entry<T, V>>,
        internal val changed: Boolean,
    )

    private val prepared = mutableStateOf<Prepared<T, V>?>(null, referentialEqualityPolicy())

    private val waiting = ConcurrentHashMap<Any, T>()

    private val stale = mutableIntStateOf(0)

    public operator fun get(item: T): V {
        stale.intValue
        val key = key(item)
        val shown = Snapshot.withoutReadObservation { prepared.value }
        val entry = shown?.entries?.get(key)?.takeIf { it.item == item }
            ?: shown?.previous?.get(key)?.takeIf { it.item == item }
            ?: return wait(key, item, shown)
        return entry.value
    }

    private fun wait(key: Any, item: T, shown: Prepared<T, V>?): V {
        waiting[key] = item
        return shown?.entries?.get(key)?.value ?: placeholder
    }

    public fun isBuiltFor(items: List<T>): Boolean = prepared.value?.items === items

    internal suspend fun prepare(items: List<T>, refresh: Any?): Batch<T, V> {
        val known = Snapshot.withoutReadObservation { prepared.value }?.entries.orEmpty()
        val built = HashMap<Any, Entry<T, V>>(items.size)
        var changed = false
        items.forEach { item ->
            currentCoroutineContext().ensureActive()
            val old = known[key(item)]?.takeIf { it.item == item }
            built[key(item)] = old?.takeIf { it.refresh == refresh }
                ?: Entry(item, build(item), refresh).also { if (old != null && old.value != it.value) changed = true }
        }
        return Batch(items, built, changed)
    }

    internal fun show(batch: Batch<T, V>) {
        val previous = prepared.value?.entries.orEmpty()
        prepared.value = Prepared(batch.items, batch.entries, previous)
        val arrived = waiting.entries.removeIf { (key, item) -> batch.entries[key]?.item == item }
        if (arrived || batch.changed) stale.intValue++
    }
}

private class Shown<S>(var source: S?)

@Composable
public fun <T : Any, S : Any> RowText<T, *>.shown(source: S?, items: List<T>, vararg keys: Any?): S? {
    val last = remember(*keys) { Shown<S>(null) }
    val shown = when {
        source == null -> null
        isBuiltFor(items) -> source
        else -> last.source
    }
    SideEffect { last.source = shown }
    return shown
}

private class Identity(val value: Any) {
    override fun equals(other: Any?): Boolean = other is Identity && other.value === value

    override fun hashCode(): Int = System.identityHashCode(value)
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
    val dispatcher = LocalBuildDispatcher.current
    LaunchedEffect(text, Identity(items), refresh, everyMinute, dispatcher) {
        while (isActive) {
            val minute = currentMinute()
            val batch = withContext(dispatcher) { text.prepare(items, if (everyMinute) refresh to minute else refresh) }
            text.show(batch)
            if (!everyMinute) break
            if (currentMinute() == minute) delay(MINUTE_MILLIS - System.currentTimeMillis() % MINUTE_MILLIS)
        }
    }
    return text
}

private fun currentMinute(): Long = System.currentTimeMillis() / MINUTE_MILLIS

private const val MINUTE_MILLIS = 60_000L
