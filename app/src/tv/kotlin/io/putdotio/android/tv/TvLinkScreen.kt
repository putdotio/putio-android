package io.putdotio.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.tv.auth.TvLinkFailure
import io.putdotio.android.tv.auth.TvLinkPhase
import io.putdotio.android.tv.auth.TvLinkStop

/**
 * Device-code sign-in, after the tv-native oracle (01-auth-code, 02-auth-logging-in):
 * code tiles, put.io/link, and a single "Get new code" action that is the only
 * focusable thing on screen. The only chrome is the copy.
 */
@Composable
internal fun TvLinkScreen(
    phase: TvLinkPhase,
    sessionExpired: Boolean,
    onRequestNewCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val newCodeFocus = remember { FocusRequester() }
    val canRequestNewCode = phase != TvLinkPhase.Validating && phase != TvLinkPhase.RequestingCode
    LaunchedEffect(canRequestNewCode) {
        if (canRequestNewCode) newCodeFocus.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 80.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.tv_link_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Column(
            modifier = Modifier.padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (phase) {
                TvLinkPhase.RequestingCode -> StatusLine(stringResource(R.string.tv_link_requesting))
                is TvLinkPhase.AwaitingLink -> AwaitingLinkBody(phase.code)
                TvLinkPhase.Validating -> StatusLine(stringResource(R.string.tv_link_validating))
                is TvLinkPhase.Stopped -> StoppedBody(phase.reason, sessionExpired)
            }
        }
        if (canRequestNewCode) {
            TvButton(
                onClick = onRequestNewCode,
                modifier = Modifier
                    .padding(top = 56.dp)
                    .focusRequester(newCodeFocus),
            ) {
                Text(stringResource(R.string.tv_link_new_code))
            }
        }
    }
}

@Composable
private fun AwaitingLinkBody(code: String) {
    Text(
        text = stringResource(R.string.tv_link_instruction),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    val description = stringResource(R.string.tv_link_code_description, code)
    Row(
        modifier = Modifier
            .semantics(mergeDescendants = true) { contentDescription = description }
            .focusable(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        code.forEach { character ->
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.onBackground, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = character.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.background,
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.tv_link_follow),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 40.dp),
    )
    Text(
        text = stringResource(R.string.tv_link_url),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = PutioDesignTokens.yellowSolid,
    )
}

@Composable
private fun StoppedBody(
    reason: TvLinkStop,
    sessionExpired: Boolean,
) {
    if (sessionExpired) {
        StatusLine(stringResource(R.string.tv_link_session_expired))
    }
    val message = when (reason) {
        TvLinkStop.CodeExpired -> R.string.tv_link_expired
        TvLinkStop.StorageUnavailable -> R.string.tv_link_failed_storage
        is TvLinkStop.Failed ->
            when (reason.failure) {
                TvLinkFailure.NETWORK -> R.string.tv_link_failed_network
                TvLinkFailure.MISCONFIGURED -> R.string.tv_link_failed_misconfigured
                TvLinkFailure.SERVER -> R.string.tv_link_failed_server
            }
    }
    StatusLine(stringResource(message))
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(720.dp),
    )
}
