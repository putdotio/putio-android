package io.putdotio.android.transfers

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import io.putdotio.android.R
import io.putdotio.android.parsePutioTimestamp
import java.text.NumberFormat
import kotlin.math.roundToLong

/** Where a transfer card sits on Transfers. */
internal enum class MobileTransferSection(@StringRes val label: Int) {
    Active(R.string.mobile_transfers_section_active),
    NeedsAttention(R.string.mobile_transfers_section_attention),
    Completed(R.string.mobile_transfers_section_completed),
}

internal val TransferItem.section: MobileTransferSection
    get() = when (status) {
        AppTransferStatus.Failed -> MobileTransferSection.NeedsAttention
        AppTransferStatus.Seeding,
        AppTransferStatus.PreparingSeed,
        AppTransferStatus.Completed,
        -> MobileTransferSection.Completed
        else -> MobileTransferSection.Active
    }

/** The state dot's colour; its word always sits next to it. */
internal enum class MobileTransferDot { Downloading, Waiting, Failed, Done }

internal val TransferItem.dot: MobileTransferDot
    get() = when (section) {
        MobileTransferSection.NeedsAttention -> MobileTransferDot.Failed
        MobileTransferSection.Completed -> MobileTransferDot.Done
        MobileTransferSection.Active ->
            if (status == AppTransferStatus.Downloading || status == AppTransferStatus.Completing) {
                MobileTransferDot.Downloading
            } else {
                MobileTransferDot.Waiting
            }
    }

/** How much of the card fills: a running transfer's progress, a seeding one in full, nothing otherwise. */
internal val TransferItem.fillFraction: Float
    get() = when {
        status == AppTransferStatus.Seeding -> 1f
        section != MobileTransferSection.Active -> 0f
        else -> ((percentDone ?: 0.0) / PERCENT).toFloat().coerceIn(0f, 1f)
    }

/** One piece of a card's or sheet's meta; [mono] marks a number. */
internal data class MobileTransferPart(val text: String, val mono: Boolean = false)

/** A size, or "847 MB of 3.42 GB" while a transfer is still getting its bytes. */
internal fun TransferItem.sizeParts(context: Context): List<MobileTransferPart> {
    val total = sizeBytes?.takeIf { it > 0.0 }?.let { size(context, it) }
    val done = downloadedBytes?.takeIf { section == MobileTransferSection.Active && it >= 0.0 }
    return when {
        total == null -> emptyList()
        done == null -> listOf(MobileTransferPart(total, mono = true))
        else -> {
            val template = context.getString(R.string.mobile_transfer_of, SLOT_ONE, SLOT_TWO)
            listOf(
                MobileTransferPart(template.substringBefore(SLOT_ONE)),
                MobileTransferPart(size(context, done), mono = true),
                MobileTransferPart(template.substringAfter(SLOT_ONE).substringBefore(SLOT_TWO)),
                MobileTransferPart(total, mono = true),
                MobileTransferPart(template.substringAfter(SLOT_TWO)),
            ).filter { it.text.isNotEmpty() }
        }
    }
}

internal fun TransferItem.speedText(context: Context): String? =
    (downloadSpeedBytesPerSecond?.takeIf { it > 0.0 } ?: uploadSpeedBytesPerSecond?.takeIf { it > 0.0 })
        ?.let { context.getString(R.string.mobile_transfer_speed, size(context, it)) }

internal fun TransferItem.timeLeftText(context: Context): String? =
    estimatedSecondsRemaining?.takeIf { it > 0.0 && it.isFinite() }?.let { timeLeft(context, it.roundToLong()) }

internal fun TransferItem.percentText(): String? =
    percentDone?.takeIf { it in 0.0..PERCENT }?.let { NumberFormat.getPercentInstance().format(it / PERCENT) }

internal fun TransferItem.ratioText(): String? =
    currentRatio?.takeIf { it >= 0.0 && it.isFinite() }?.let { ratio ->
        NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = RATIO_DIGITS
            maximumFractionDigits = RATIO_DIGITS
        }.format(ratio)
    }

/** "3 hr. ago" from put.io's finish time, in words, as dates read. */
internal fun TransferItem.finishedAgo(nowMillis: Long): String? =
    finishedAt?.let(::parsePutioTimestamp)?.toEpochMilli()?.let { finished ->
        DateUtils.getRelativeTimeSpanString(
            finished.coerceAtMost(nowMillis),
            nowMillis,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        ).toString()
    }

/** "2m 48s", "1h 05m", "3d 04h": the two largest units. */
internal fun timeLeft(context: Context, seconds: Long): String {
    val minutes = seconds / SECONDS_PER_MINUTE
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        days > 0 -> context.getString(R.string.mobile_transfer_eta_days, days, hours % HOURS_PER_DAY)
        hours > 0 -> context.getString(R.string.mobile_transfer_eta_hours, hours, minutes % MINUTES_PER_HOUR)
        else -> context.getString(R.string.mobile_transfer_eta_minutes, minutes, seconds % SECONDS_PER_MINUTE)
    }
}

/**
 * A transfer name on one line, cut in the middle when it runs past [budget] characters: its start,
 * an ellipsis, and its last characters, which keep the extension ("The.Wire.S03E04.…-DEMAND.mkv").
 */
internal fun middleCut(name: String, budget: Int = TRANSFER_NAME_BUDGET): String {
    if (name.codePointCount(0, name.length) <= budget) return name
    val tail = minOf(TRANSFER_NAME_TAIL, budget / 2)
    val head = budget - 1 - tail
    return name.take(name.offsetByCodePoints(0, head)) + "…" +
        name.substring(name.offsetByCodePoints(name.length, -tail))
}

internal const val TRANSFER_NAME_BUDGET = 28
private const val TRANSFER_NAME_TAIL = 11

private fun size(context: Context, bytes: Double): String = Formatter.formatShortFileSize(context, bytes.toLong())

private const val PERCENT = 100.0
private const val RATIO_DIGITS = 2
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val SLOT_ONE = "\u0001"
private const val SLOT_TWO = "\u0002"
