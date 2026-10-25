package io.putdotio.android.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.putioMono

internal const val MOBILE_INACTIVE_ACCOUNT_NOTICE_TAG = "mobile-inactive-account-notice"

/**
 * The inactive-account notice card under the top bar. A red warning glyph leads; the card and its words
 * stay neutral, with the deletion countdown's days in mono. It only informs, so nothing in it is clickable.
 */
@Composable
internal fun MobileInactiveAccountNotice(
    notice: InactiveAccountNotice,
    modifier: Modifier = Modifier,
) {
    val days = (notice as? InactiveAccountNotice.Deactivated)?.daysUntilFilesDeleted()
    val text = when (notice) {
        is InactiveAccountNotice.Deactivated -> deactivatedAccountNoticeText(days)
        InactiveAccountNotice.FamilyPlanExpired -> familyPlanExpiredNoticeText()
    }
    val mono = MaterialTheme.typography.bodyMedium.putioMono().toSpanStyle()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(MOBILE_INACTIVE_ACCOUNT_NOTICE_TAG),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_ph_warning_circle_fill),
                contentDescription = null,
                tint = PutioDesignTokens.redTextSecondary,
                modifier = Modifier.padding(top = 1.dp).size(20.dp),
            )
            Column {
                Text(
                    text = text.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                text.message?.let { message ->
                    Text(
                        text = monoNumber(message, days?.toString(), mono),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** [message] with its first [number] in [mono]; the plural's words stay in the UI face. */
private fun monoNumber(
    message: String,
    number: String?,
    mono: SpanStyle,
): AnnotatedString {
    val start = number?.let { Regex("""(?<!\d)${Regex.escape(it)}(?!\d)""").find(message)?.range?.first } ?: -1
    if (number == null || start < 0) return AnnotatedString(message)
    return buildAnnotatedString {
        append(message.substring(0, start))
        withStyle(mono) { append(number) }
        append(message.substring(start + number.length))
    }
}
