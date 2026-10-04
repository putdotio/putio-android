package io.putdotio.android.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.putdotio.android.design.R

/** The inactive-account notice's words on both surfaces; [message] is null where web shows none. */
data class InactiveAccountNoticeText(
    val title: String,
    val message: String?,
)

/** A deactivated account's words; without [daysUntilFilesDeleted] there is no message. */
@Composable
fun deactivatedAccountNoticeText(daysUntilFilesDeleted: Long?): InactiveAccountNoticeText =
    InactiveAccountNoticeText(
        title = stringResource(R.string.account_inactive_title),
        message = daysUntilFilesDeleted?.toInt()?.let { days ->
            pluralStringResource(R.plurals.account_inactive_files_deletion, days, days)
        },
    )

@Composable
fun familyPlanExpiredNoticeText(): InactiveAccountNoticeText =
    InactiveAccountNoticeText(
        title = stringResource(R.string.account_family_expired_title),
        message = null,
    )
