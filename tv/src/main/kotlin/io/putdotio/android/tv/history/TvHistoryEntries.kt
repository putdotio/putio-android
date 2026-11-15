package io.putdotio.android.tv.history

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import io.putdotio.android.R
import io.putdotio.android.design.Built
import io.putdotio.android.design.buildReusing
import io.putdotio.android.design.rememberBuiltOffMain
import io.putdotio.android.history.HistoryItem
import io.putdotio.android.history.navigableFileId
import io.putdotio.android.tv.TvRowMeta
import io.putdotio.android.tv.TvRowMetaPart
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal sealed interface TvHistoryEntry {
    val key: Any

    data class Section(val label: String) : TvHistoryEntry {
        override val key: Any get() = SECTION_KEY
    }

    data class Header(val label: String, val day: TvHistoryDay) : TvHistoryEntry {
        override val key: Any get() = "header-$day"
    }

    data class Event(val item: HistoryItem, val title: String, val text: TvHistoryRowText) : TvHistoryEntry {
        override val key: Any get() = item.id.value
    }
}

internal class TvHistoryStamp(val day: String, val time: String?)

internal class TvHistoryEntries(
    val list: List<TvHistoryEntry>,
    val titles: List<String>,
    private val stamps: Map<Long, TvHistoryStamp>,
    val rows: Map<HistoryItem, TvHistoryRowParts>,
) {
    fun stampOf(item: HistoryItem): TvHistoryStamp? = stamps[item.id.value]
}

internal class TvHistoryRowParts(
    val item: HistoryItem,
    val day: TvHistoryDay,
    val time: String,
    val title: String,
    val text: TvHistoryRowText,
)

@Composable
internal fun rememberHistoryEntries(
    items: List<HistoryItem>,
    clock: Clock,
): Built<List<HistoryItem>, TvHistoryEntries>? {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now(clock)
    val zone = clock.zone
    return rememberBuiltOffMain(items, today, zone, resources, locale) { next, previous ->
        next.historyEntries(today, zone, resources, locale, previous?.rows.orEmpty())
    }
}

private suspend fun List<HistoryItem>.historyEntries(
    today: LocalDate,
    zone: ZoneId,
    resources: Resources,
    locale: Locale,
    built: Map<HistoryItem, TvHistoryRowParts>,
): TvHistoryEntries {
    val untitled = resources.getString(R.string.tv_history_no_title)
    val rows = buildReusing(built) { it.rowParts(zone, resources, untitled) }
    val stamps = HashMap<Long, TvHistoryStamp>(size)
    val titles = ArrayList<String>(size)
    val days = rows.rows.groupConsecutiveBy { it.day }
    val list = buildList {
        days.forEachIndexed { index, (day, events) ->
            val label = day.label(today, resources, locale)
            add(if (index == 0) TvHistoryEntry.Section(label) else TvHistoryEntry.Header(label, day))
            events.forEach { row ->
                stamps[row.item.id.value] = TvHistoryStamp(label, row.time.takeIf { day.date != null })
                titles += row.title
                add(TvHistoryEntry.Event(row.item, row.title, row.text))
            }
        }
    }
    return TvHistoryEntries(list, titles, stamps, rows.byItem)
}

private fun HistoryItem.rowParts(zone: ZoneId, resources: Resources, untitled: String): TvHistoryRowParts {
    val time = timeLabel(zone)
    val title = named() ?: untitled
    return TvHistoryRowParts(this, day(zone), time, title, rowText(time, title, resources))
}

private fun <T, K> List<T>.groupConsecutiveBy(keyOf: (T) -> K): List<Pair<K, List<T>>> =
    fold(mutableListOf<Pair<K, MutableList<T>>>()) { groups, item ->
        val key = keyOf(item)
        val last = groups.lastOrNull()
        if (last?.first == key) last.second += item else groups += key to mutableListOf(item)
        groups
    }

internal data class TvHistoryRowText(val meta: TvRowMeta, val label: String)

private fun HistoryItem.rowText(time: String, title: String, resources: Resources): TvHistoryRowText {
    val meta = TvRowMeta(
        listOfNotNull(
            kind.kindLabel()?.let { TvRowMetaPart(resources.getString(it), shrinks = true) },
            TvRowMetaPart(time, mono = true),
        ),
    )
    return TvHistoryRowText(meta, rowLabel(title, resources))
}

private fun HistoryItem.rowLabel(title: String, resources: Resources): String =
    if (kind.navigableFileId() == null) title else resources.getString(R.string.tv_history_open_file, title)

private const val SECTION_KEY = "tv-history-section"
