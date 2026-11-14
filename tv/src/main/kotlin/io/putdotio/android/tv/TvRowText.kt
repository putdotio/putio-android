package io.putdotio.android.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
        items.forEach { item ->
            currentCoroutineContext().ensureActive()
            prepared[key(item)] = known[key(item)]?.takeIf { it.item == item && it.refresh == refresh }
                ?: Entry(item, build(item), refresh)
        }
        currentCoroutineContext().ensureActive()
        entries = prepared
        if (refresh != applied) {
            applied = refresh
            refreshed.intValue++
        }
    }
}

@Composable
internal fun <T : Any, V> rememberTvRowText(
    items: List<T>,
    vararg inputs: Any?,
    key: (T) -> Any,
    refresh: Any? = null,
    build: (T) -> V,
): TvRowText<T, V> {
    val text = remember(*inputs) { TvRowText(key, build) }
    LaunchedEffect(text, items, refresh) { withContext(Dispatchers.Default) { text.prepare(items, refresh) } }
    return text
}
