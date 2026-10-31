package io.putdotio.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioEmptyGlyph

@Composable
internal fun TvStartupScreen(
    message: String,
    modifier: Modifier = Modifier,
) {
    StartupFrame(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = SPINNER_LIFT * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                trackColor = PutioDesignTokens.componentBgActive,
                strokeWidth = SPINNER_STROKE,
                modifier = Modifier.size(SPINNER_SIZE),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun TvOfflineScreen(
    onTryNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StartupFrame(modifier) {
        TvEmptyState(
            glyph = PutioEmptyGlyph.Offline,
            headline = stringResource(R.string.tv_session_unavailable_title),
            message = stringResource(R.string.tv_session_unavailable_message),
            action = TvEmptyStateAction(
                label = stringResource(R.string.tv_session_retry),
                onClick = onTryNow,
                icon = R.drawable.ic_ph_arrows_clockwise,
            ),
        )
    }
}

@Composable
private fun StartupFrame(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TvWordmark(Modifier.padding(top = TV_VERTICAL_MARGIN))
        Box(Modifier.fillMaxSize().padding(top = CONTENT_TOP)) { content() }
    }
}

private val CONTENT_TOP = 64.dp
private val SPINNER_LIFT = 14.dp
private val SPINNER_SIZE = 28.dp
private val SPINNER_STROKE = 3.dp
