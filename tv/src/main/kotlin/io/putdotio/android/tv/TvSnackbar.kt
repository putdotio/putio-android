package io.putdotio.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import kotlinx.coroutines.delay

internal data class TvSnackbarMessage(
    val text: String,
    val actionLabel: String? = null,
    val onAction: () -> Unit = {},
)

internal class TvSnackbarShowing(val message: TvSnackbarMessage)

@Stable
internal class TvSnackbarState {
    var showing: TvSnackbarShowing? by mutableStateOf(null)
        private set

    fun show(message: TvSnackbarMessage) {
        showing = TvSnackbarShowing(message)
    }

    fun dismiss() {
        showing = null
    }
}

@Composable
internal fun TvSnackbarHost(
    state: TvSnackbarState,
    modifier: Modifier = Modifier,
) {
    val showing = state.showing ?: return
    val message = showing.message
    LaunchedEffect(showing) {
        delay(SNACKBAR_MILLIS)
        if (state.showing === showing) state.dismiss()
    }
    Row(
        modifier = modifier
            .height(40.dp)
            .background(PutioDesignTokens.componentBgActive, RoundedCornerShape(PutioRadius.small))
            .padding(start = 14.dp, end = if (message.actionLabel == null) 14.dp else 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (message.actionLabel != null) {
            TvButton(
                onClick = {
                    state.dismiss()
                    message.onAction()
                },
                style = TvButtonStyle.Text,
            ) { Text(message.actionLabel) }
        }
    }
}

internal const val SNACKBAR_MILLIS = 6_000L
