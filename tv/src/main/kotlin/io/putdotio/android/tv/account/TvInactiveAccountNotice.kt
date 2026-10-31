package io.putdotio.android.tv.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.account.InactiveAccountNotice
import io.putdotio.android.account.daysUntilFilesDeleted
import io.putdotio.android.account.deactivatedAccountNoticeText
import io.putdotio.android.account.familyPlanExpiredNoticeText
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.tv.withFullLineBox

/** The inactive-account card under the tabs; plain text, so nothing here takes D-pad focus. */
@Composable
internal fun TvInactiveAccountNotice(
    notice: InactiveAccountNotice,
    modifier: Modifier = Modifier,
) {
    val text = when (notice) {
        is InactiveAccountNotice.Deactivated -> deactivatedAccountNoticeText(notice.daysUntilFilesDeleted())
        InactiveAccountNotice.FamilyPlanExpired -> familyPlanExpiredNoticeText()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PutioDesignTokens.componentBgHover, RoundedCornerShape(PutioRadius.large))
            .padding(horizontal = NOTICE_PADDING_X, vertical = NOTICE_PADDING_Y),
        horizontalArrangement = Arrangement.spacedBy(NOTICE_GLYPH_GAP),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_warning_circle_fill),
            contentDescription = null,
            tint = PutioDesignTokens.redTextSecondary,
            modifier = Modifier
                .padding(top = NOTICE_GLYPH_TOP)
                .size(NOTICE_GLYPH),
        )
        Column {
            Text(
                text = text.title,
                style = NOTICE_TITLE,
                color = MaterialTheme.colorScheme.onSurface,
            )
            text.message?.let {
                Text(
                    text = it,
                    style = NOTICE_MESSAGE,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = NOTICE_LINE_GAP),
                )
            }
        }
    }
}

private val NOTICE_PADDING_X = 14.dp
private val NOTICE_PADDING_Y = 12.dp
private val NOTICE_GLYPH = 16.dp
private val NOTICE_GLYPH_TOP = 1.dp
private val NOTICE_GLYPH_GAP = 10.dp
private val NOTICE_LINE_GAP = 2.dp
private val NOTICE_TITLE = PutioTypeScale.tvBody.copy(fontWeight = FontWeight.Medium, lineHeight = 20.sp).withFullLineBox()
private val NOTICE_MESSAGE = PutioTypeScale.tvMeta.withFullLineBox()
