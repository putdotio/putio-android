package io.putdotio.android.account

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.putdotio.android.design.R

public data class InactiveAccountNoticeText(
    val title: String,
    val message: String?,
)

@Composable
public fun deactivatedAccountNoticeText(daysUntilFilesDeleted: Long?): InactiveAccountNoticeText =
    InactiveAccountNoticeText(
        title = stringResource(R.string.account_inactive_title),
        message = daysUntilFilesDeleted?.toInt()?.let { days ->
            pluralStringResource(R.plurals.account_inactive_files_deletion, days, days)
        },
    )

@Composable
public fun familyPlanExpiredNoticeText(): InactiveAccountNoticeText =
    InactiveAccountNoticeText(
        title = stringResource(R.string.account_family_expired_title),
        message = null,
    )
