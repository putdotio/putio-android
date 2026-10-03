package io.putdotio.android

import io.putdotio.sdk.account.AccountInfo

/** The account's disk from `/account/info`, and which side of it the viewer asked to see. */
data class AccountStorage(
    val availableBytes: Long = 0L,
    val sizeBytes: Long = 0L,
    val usedBytes: Long = 0L,
    /** `show_optimistic_usage`: label what is free rather than what is used, as web and iOS do. */
    val showOptimisticUsage: Boolean = false,
) {
    /** The bar always fills with what is used; invalid disk values clamp to 0..1. */
    val usedFraction: Float
        get() = if (sizeBytes <= 0L) 0f else (usedBytes.toDouble() / sizeBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

fun AccountInfo.toAccountStorage(): AccountStorage =
    AccountStorage(
        availableBytes = disk.available,
        sizeBytes = disk.size,
        usedBytes = disk.used,
        showOptimisticUsage = settings.showOptimisticUsage,
    )
