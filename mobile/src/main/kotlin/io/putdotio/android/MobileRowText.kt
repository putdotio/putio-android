package io.putdotio.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Stable
internal class MobileRowText<T : Any, V>(
    private val key: (T) -> Any,
    private val placeholder: V,
    private val build: (T) -> V,
) {
    private class Entry<T, V>(val item: T, val value: V, val refresh: Any?)

    @Volatile
    private var entries = ConcurrentHashMap<Any, Entry<T, V>>()

    @Volatile
    private var refresh: Any? = null

    private val waiting = ConcurrentHashMap<Any, T>()

    private val prepared = mutableIntStateOf(0)

    private val generation = AtomicLong()

    private val stale = AtomicBoolean()

    operator fun get(item: T): V {
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

    suspend fun prepare(items: List<T>, refresh: Any?) {
        val run = generation.incrementAndGet()
        val known = entries
        val built = ConcurrentHashMap<Any, Entry<T, V>>(items.size)
        items.forEach { item ->
            currentCoroutineContext().ensureActive()
            built[key(item)] = known[key(item)]?.takeIf { it.item == item && it.refresh == refresh }
                ?: Entry(item, build(item), refresh)
        }
        val context = currentCoroutineContext()
        synchronized(generation) {
            if (run != generation.get() || !context.isActive) return
            entries = built
            val newRefresh = refresh != this.refresh
            this.refresh = refresh
            if (waiting.entries.removeIf { (key, item) -> built[key]?.item == item } || newRefresh) stale.set(true)
        }
    }

    fun recomposeStaleRows() {
        if (stale.getAndSet(false)) prepared.intValue++
    }
}

@Composable
internal fun <T : Any, V> rememberRowText(
    items: List<T>,
    vararg inputs: Any?,
    key: (T) -> Any,
    placeholder: V,
    refresh: Any? = null,
    build: (T) -> V,
): MobileRowText<T, V> {
    val text = remember(*inputs) { MobileRowText(key, placeholder, build) }
    LaunchedEffect(text, items, refresh) {
        text.recomposeStaleRows()
        withContext(Dispatchers.Default) { text.prepare(items, refresh) }
        withFrameNanos {}
        text.recomposeStaleRows()
    }
    return text
}

@Composable
internal fun rememberMinute(): Long {
    val minute = remember { mutableLongStateOf(System.currentTimeMillis() / MINUTE_MILLIS) }
    LaunchedEffect(minute) {
        withContext(Dispatchers.Default) {
            while (isActive) {
                delay(MINUTE_MILLIS - System.currentTimeMillis() % MINUTE_MILLIS)
                minute.longValue = System.currentTimeMillis() / MINUTE_MILLIS
            }
        }
    }
    return minute.longValue
}

private const val MINUTE_MILLIS = 60_000L
