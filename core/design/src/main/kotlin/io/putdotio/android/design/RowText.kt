package io.putdotio.android.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
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
            val stamp = if (everyMinute) refresh to System.currentTimeMillis() / MINUTE_MILLIS else refresh
            withContext(Dispatchers.Default) { text.prepare(items, stamp) }
            withFrameNanos {}
            text.recomposeStaleRows()
            if (!everyMinute) break
            delay(MINUTE_MILLIS - System.currentTimeMillis() % MINUTE_MILLIS)
        }
    }
    return text
}

private const val MINUTE_MILLIS = 60_000L
