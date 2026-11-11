package io.putdotio.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioEmptyState
import io.putdotio.android.design.PutioRadius
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

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
    glyph: PutioEmptyGlyph? = null,
    action: @Composable (() -> Unit)? = null,
) {
    MobileMessageState(
        title = title,
        message = message,
        modifier = modifier,
        glyph = glyph,
        action = action,
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
    glyph: PutioEmptyGlyph? = null,
) {
    MobileMessageState(title = title, message = message, modifier = modifier, glyph = glyph) {
        MobileRetryButton(onClick = onRetry, enabled = retryEnabled, label = retryLabel)
    }
}

@Composable
internal fun MobileRetryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = stringResource(R.string.mobile_action_retry),
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(label) }
}

@Composable
internal fun MobileInlineError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryEnabled: Boolean = true,
    retryLabel: String = stringResource(R.string.mobile_action_retry),
    retryModifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(PutioRadius.large))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_warning_circle),
            contentDescription = null,
            tint = PutioDesignTokens.redTextSecondary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        TextButton(
            onClick = onRetry,
            enabled = retryEnabled,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = retryModifier,
        ) { Text(retryLabel) }
    }
}

/**
 * Calls [onRetry] once the default network is validated again, while this is on screen and the app
 * is started, and on a slow clock: the network may have returned before this was on screen, or
 * put.io itself was down. Keyed on [failure], so a retry that fails again before a frame draws
 * still restarts the clock; each return to the foreground restarts it too, and retries at once
 * when the network is validated by then.
 */
internal val FilesFailure?.isUnreachable: Boolean
    get() = this is PutioFailure.NetworkUnavailable || this is PutioFailure.Timeout

@Composable
internal fun RetryWhenOnline(failure: Any?, onRetry: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val retry by rememberUpdatedState(onRetry)
    LaunchedEffect(failure, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(OFFLINE_RETRY_MILLIS)
            retry()
        }
    }
    // Only a network that was missing or unvalidated, or a return from the background, and then a
    // validated network counts, so a put.io outage on a working network does not retry in a loop.
    val waiting = remember(context) {
        AtomicBoolean(!context.getSystemService(ConnectivityManager::class.java).defaultNetworkValidated())
    }
    LifecycleStartEffect(context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (!connectivity.defaultNetworkValidated()) {
            waiting.set(true)
        } else if (waiting.compareAndSet(true, false)) {
            retry()
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                waiting.set(true)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!validated) {
                    waiting.set(true)
                } else if (waiting.compareAndSet(true, false)) {
                    ContextCompat.getMainExecutor(context).execute { retry() }
                }
            }
        }
        val registered = connectivity?.runCatching { registerDefaultNetworkCallback(callback) }?.isSuccess == true
        onStopOrDispose {
            if (registered) connectivity?.unregisterNetworkCallback(callback)
            waiting.set(true)
        }
    }
}

private fun ConnectivityManager?.defaultNetworkValidated(): Boolean =
    this?.activeNetwork
        ?.let(::getNetworkCapabilities)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

private const val OFFLINE_RETRY_MILLIS = 30_000L

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
    glyph: PutioEmptyGlyph? = null,
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
            verticalArrangement = Arrangement.Center,
        ) {
            PutioEmptyState(glyph = glyph, headline = title, message = message, action = action)
        }
    }
}
