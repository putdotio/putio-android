package io.putdotio.android.tv.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.account.InactiveAccountNotice
import io.putdotio.android.account.renewUrlLabel
import io.putdotio.android.account.text
import io.putdotio.android.design.PutioDesignTokens

/**
 * The inactive-account banner above every pane. A TV cannot renew in place, so the web
 * action becomes its address to visit on another device; nothing here takes D-pad focus.
 */
@Composable
internal fun TvInactiveAccountNotice(
    notice: InactiveAccountNotice,
    modifier: Modifier = Modifier,
) {
    val text = notice.text()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = text.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        text.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text.action,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = notice.renewUrlLabel,
                style = MaterialTheme.typography.titleSmall,
                color = PutioDesignTokens.yellowSolid,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
