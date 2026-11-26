package io.putdotio.android

import io.putdotio.sdk.account.AccountInfo

public data class AccountStorage(
    val availableBytes: Long = 0L,
    val sizeBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val showOptimisticUsage: Boolean = false,
) {
    val usedFraction: Float
        get() = if (sizeBytes <= 0L) 0f else (usedBytes.toDouble() / sizeBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()

    val isFull: Boolean
        get() = sizeBytes > 0L && (availableBytes <= 0L || usedBytes >= sizeBytes)
}

public fun AccountInfo.toAccountStorage(): AccountStorage =
    AccountStorage(
        availableBytes = disk.available,
        sizeBytes = disk.size,
        usedBytes = disk.used,
        showOptimisticUsage = settings.showOptimisticUsage,
    )
