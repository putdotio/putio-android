package io.putdotio.android.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.putdotio.android.R
import io.putdotio.android.parsePutioTimestamp
import io.putdotio.sdk.account.AccountInfo
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * The persistent notice an inactive account shows above every signed-in screen, with
 * putio-web's states (`AccountStatusNotification`): a family plan member is told the plan
 * expired, anyone else that the account is deactivated and when the files go.
 * Google Play's payments policy rules out web's billing and family links and its calls to
 * pay, renew or subscribe, so the notice states facts only and has no action.
 * Web's active-account payment warnings and `stranger` notice are not ported.
 */
sealed interface InactiveAccountNotice {
    /** [filesDeletedAt] is null when put.io sent no usable deletion date; the notice then has no message. */
    data class Deactivated(
        val filesDeletedAt: Instant?,
    ) : InactiveAccountNotice

    data object FamilyPlanExpired : InactiveAccountNotice
}

internal fun AccountInfo.inactiveAccountNotice(): InactiveAccountNotice? =
    when {
        accountStatus != INACTIVE_STATUS -> null
        isSubAccount -> InactiveAccountNotice.FamilyPlanExpired
        else -> InactiveAccountNotice.Deactivated(filesWillBeDeletedAt?.let(::parsePutioTimestamp))
    }

/**
 * Whole calendar days between today and the deletion day in [zone], as web's `daysDiffFromNow`
 * counts them: absolute, so a date already past still reads as a distance.
 */
fun InactiveAccountNotice.Deactivated.daysUntilFilesDeleted(now: Instant, zone: ZoneId): Long? =
    filesDeletedAt?.let { abs(ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), it.atZone(zone).toLocalDate())) }

/** The notice's words; [message] is null where web shows none. */
data class InactiveAccountNoticeText(
    val title: String,
    val message: String?,
)

@Composable
fun InactiveAccountNotice.text(
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): InactiveAccountNoticeText =
    when (this) {
        is InactiveAccountNotice.Deactivated ->
            InactiveAccountNoticeText(
                title = stringResource(R.string.account_inactive_title),
                message = daysUntilFilesDeleted(now, zone)?.toInt()?.let { days ->
                    pluralStringResource(R.plurals.account_inactive_files_deletion, days, days)
                },
            )
        InactiveAccountNotice.FamilyPlanExpired ->
            InactiveAccountNoticeText(
                title = stringResource(R.string.account_family_expired_title),
                message = null,
            )
    }

private const val INACTIVE_STATUS = "inactive"
