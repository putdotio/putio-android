package io.putdotio.android.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal const val MOBILE_INACTIVE_ACCOUNT_NOTICE_TAG = "mobile-inactive-account-notice"

/** The inactive-account banner under the top bar; it only informs, so nothing in it is clickable. */
@Composable
internal fun MobileInactiveAccountNotice(
    notice: InactiveAccountNotice,
    modifier: Modifier = Modifier,
) {
    val text = notice.text()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(MOBILE_INACTIVE_ACCOUNT_NOTICE_TAG),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = text.title, style = MaterialTheme.typography.titleSmall)
            text.message?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
