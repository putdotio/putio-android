package io.putdotio.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Stable
internal class MobileRowText<T : Any, V : Any>(
    private val key: (T) -> Any,
    private val build: (T) -> V,
) {
    private class Entry<T, V>(val item: T, val value: V)

    @Volatile
    private var entries = ConcurrentHashMap<Any, Entry<T, V>>()

    operator fun get(item: T): V {
        val entry = entries[key(item)]
        if (entry != null && entry.item == item) return entry.value
        return build(item).also { entries[key(item)] = Entry(item, it) }
    }

    fun prepare(items: List<T>) {
        val known = entries
        val prepared = ConcurrentHashMap<Any, Entry<T, V>>(items.size)
        items.forEach { item ->
            prepared[key(item)] = known[key(item)]?.takeIf { it.item == item } ?: Entry(item, build(item))
        }
        entries = prepared
    }
}

@Composable
internal fun <T : Any, V : Any> rememberRowText(
    items: List<T>,
    vararg inputs: Any?,
    key: (T) -> Any,
    build: (T) -> V,
): MobileRowText<T, V> {
    val text = remember(*inputs) { MobileRowText(key, build) }
    LaunchedEffect(text, items) { withContext(Dispatchers.Default) { text.prepare(items) } }
    return text
}
