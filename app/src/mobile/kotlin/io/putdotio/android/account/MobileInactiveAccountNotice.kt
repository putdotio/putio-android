package io.putdotio.android.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

internal const val MOBILE_INACTIVE_ACCOUNT_NOTICE_TAG = "mobile-inactive-account-notice"

/** The inactive-account banner under the top bar; its action opens app.put.io in the browser. */
@Composable
internal fun MobileInactiveAccountNotice(
    notice: InactiveAccountNotice,
    modifier: Modifier = Modifier,
    openUrl: (Context, String) -> Boolean = ::openInBrowser,
) {
    val text = notice.text()
    val context = LocalContext.current
    // Without a browser the address is the only way forward, so it replaces the dead action.
    var browserMissing by remember(notice) { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(MOBILE_INACTIVE_ACCOUNT_NOTICE_TAG),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Text(text = text.title, style = MaterialTheme.typography.titleSmall)
            text.message?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (browserMissing) {
                Text(
                    text = notice.renewUrlLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                TextButton(onClick = { browserMissing = !openUrl(context, notice.renewUrl) }) {
                    Text(text.action)
                }
            }
        }
    }
}

private fun openInBrowser(context: Context, url: String): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
