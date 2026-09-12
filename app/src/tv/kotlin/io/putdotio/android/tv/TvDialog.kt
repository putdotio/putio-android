package io.putdotio.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * A centred 28dp dialog per the TV contract: a title, an optional message, and stacked
 * full-width actions. The action given the requester takes focus once the window is up;
 * Back is the caller's dismissal.
 */
@Composable
internal fun TvDialog(
    title: String,
    message: String?,
    onDismiss: () -> Unit,
    actions: (@Composable (focus: FocusRequester) -> Unit)? = null,
) {
    val actionFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        if (actions != null) {
            // Requested from inside the dialog window, one frame after its content attaches.
            LaunchedEffect(Unit) {
                withFrameNanos {}
                actionFocus.requestFocus()
            }
        }
        Column(
            modifier = Modifier
                .width(480.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
                .padding(24.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            actions?.invoke(actionFocus)
        }
    }
}
