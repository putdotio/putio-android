package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Stable
internal class TvRowText<T : Any, V>(
    private val key: (T) -> Any,
    private val build: (T) -> V,
) {
    private class Entry<T, V>(val item: T, val value: V, val refresh: Any?)

    @Volatile
    private var entries = ConcurrentHashMap<Any, Entry<T, V>>()

    @Volatile
    private var applied: Any? = null

    private val refreshed = mutableIntStateOf(0)

    operator fun get(item: T): V {
        refreshed.intValue
        val entry = entries[key(item)]
        if (entry != null && entry.item == item) return entry.value
        return build(item).also { entries[key(item)] = Entry(item, it, applied) }
    }

    suspend fun prepare(items: List<T>, refresh: Any?) {
        val known = entries
        val prepared = ConcurrentHashMap<Any, Entry<T, V>>(items.size)
        var changed = false
        items.forEach { item ->
            currentCoroutineContext().ensureActive()
            val old = known[key(item)]?.takeIf { it.item == item }
            prepared[key(item)] = old?.takeIf { it.refresh == refresh }
                ?: Entry(item, build(item), refresh).also { if (old != null && old.value != it.value) changed = true }
        }
        currentCoroutineContext().ensureActive()
        entries = prepared
        applied = refresh
        if (changed) refreshed.intValue++
    }
}

@Composable
internal fun <T : Any, V> rememberTvRowText(
    items: List<T>,
    vararg inputs: Any?,
    key: (T) -> Any,
    refresh: Any? = null,
    everyMinute: Boolean = false,
    build: (T) -> V,
): TvRowText<T, V> {
    val text = remember(*inputs) { TvRowText(key, build) }
    LaunchedEffect(text, items, refresh, everyMinute) {
        withContext(Dispatchers.Default) {
            text.prepare(items, refresh to currentMinute())
            while (everyMinute) {
                delay(MINUTE_MILLIS - System.currentTimeMillis() % MINUTE_MILLIS)
                text.prepare(items, refresh to currentMinute())
            }
        }
    }
    return text
}

private fun currentMinute(): Long = System.currentTimeMillis() / MINUTE_MILLIS

private const val MINUTE_MILLIS = 60_000L
