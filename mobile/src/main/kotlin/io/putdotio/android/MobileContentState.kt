package io.putdotio.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun MobileLoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(text = message)
    }
}

@Composable
internal fun MobileEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    MobileMessageState(
        title = title,
        message = message,
        modifier = modifier,
    )
}

@Composable
internal fun MobileErrorState(
    title: String,
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryEnabled: Boolean = true,
) {
    MobileAuthMessageScreen(
        title = title,
        message = message,
        actionLabel = retryLabel,
        onAction = onRetry,
        modifier = modifier,
        actionEnabled = retryEnabled,
    )
}

@Composable
internal fun MobileAuthMessageScreen(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
) {
    MobileMessageState(
        title = title,
        message = message,
        modifier = modifier,
    ) {
        Button(onClick = onAction, enabled = actionEnabled) {
            Text(text = actionLabel)
        }
    }
}

@Composable
private fun MobileMessageState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        propagateMinConstraints = true,
    ) {
        // In a list item, the parent already owns scrolling and supplies unbounded height.
        val scrolling = if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            modifier = Modifier.fillMaxSize().then(scrolling),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.invoke()
        }
    }
}
