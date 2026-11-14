package io.putdotio.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Stable
internal class MobileRowText<T : Any, V : Any>(
    private val key: (T) -> Any,
    private val maxAgeMillis: Long,
    private val clock: () -> Long,
    private val build: (T, nowMillis: Long) -> V,
) {
    private class Entry<T, V>(val item: T, val value: V, val builtAt: Long)

    private val entries = ConcurrentHashMap<Any, Entry<T, V>>()

    operator fun get(item: T): V {
        val now = clock()
        val entry = entries[key(item)]
        if (entry != null && entry.item == item && now - entry.builtAt < maxAgeMillis) return entry.value
        return build(item, now).also { entries[key(item)] = Entry(item, it, now) }
    }

    fun prepare(items: List<T>) {
        val now = clock()
        items.forEach { item ->
            val entry = entries[key(item)]
            if (entry == null || entry.item != item || now - entry.builtAt >= maxAgeMillis / 2) {
                entries[key(item)] = Entry(item, build(item, now), now)
            }
        }
    }
}

@Composable
internal fun <T : Any, V : Any> rememberRowText(
    items: List<T>,
    vararg inputs: Any?,
    key: (T) -> Any,
    maxAgeMillis: Long = ROW_TEXT_MAX_AGE_MILLIS,
    clock: () -> Long = System::currentTimeMillis,
    build: (T, nowMillis: Long) -> V,
): MobileRowText<T, V> {
    val text = remember(*inputs) { MobileRowText(key, maxAgeMillis, clock, build) }
    LaunchedEffect(text, items) {
        withContext(Dispatchers.Default) {
            while (isActive) {
                text.prepare(items)
                delay(maxAgeMillis / 2)
            }
        }
    }
    return text
}

private const val ROW_TEXT_MAX_AGE_MILLIS = 60_000L
