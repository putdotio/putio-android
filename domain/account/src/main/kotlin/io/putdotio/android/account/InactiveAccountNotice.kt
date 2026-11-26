package io.putdotio.android.account

import io.putdotio.android.parsePutioTimestamp
import io.putdotio.sdk.account.AccountInfo
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

public sealed interface InactiveAccountNotice {
    public data class Deactivated(
        val filesDeletedAt: Instant?,
    ) : InactiveAccountNotice

    public data object FamilyPlanExpired : InactiveAccountNotice
}

public fun AccountInfo.inactiveAccountNotice(): InactiveAccountNotice? =
    when {
        accountStatus != INACTIVE_STATUS -> null
        isSubAccount -> InactiveAccountNotice.FamilyPlanExpired
        else -> InactiveAccountNotice.Deactivated(filesWillBeDeletedAt?.let(::parsePutioTimestamp))
    }

public fun InactiveAccountNotice.Deactivated.daysUntilFilesDeleted(
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): Long? =
    filesDeletedAt?.let { abs(ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), it.atZone(zone).toLocalDate())) }

private const val INACTIVE_STATUS = "inactive"
