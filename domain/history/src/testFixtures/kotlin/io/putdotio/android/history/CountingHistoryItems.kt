package io.putdotio.android.history

public class CountingHistoryItems(
    private val items: List<HistoryItem>,
    private val comparisons: Comparisons,
) : List<HistoryItem> by items {
    public class Comparisons {
        public var count: Int = 0
    }

    override fun equals(other: Any?): Boolean {
        comparisons.count++
        return items == ((other as? CountingHistoryItems)?.items ?: other)
    }

    override fun hashCode(): Int = items.hashCode()
}
