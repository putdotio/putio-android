package io.putdotio.android.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.tv.auth.TvAccount

/**
 * Signed-in shell per putio-design platforms/android/DESIGN.md §Android TV: the
 * M3 TV navigation drawer (80dp icon rail, labels while focus is inside) on the
 * left, one destination pane on the right. Focus starts in the pane; the
 * drawer's focusRestorer returns D-pad focus to the last chosen item.
 *
 * The pane inset is the tv token group's overscan (4% x 2% of a 1920x1080
 * canvas, halved for xhdpi: 38dp x 11dp) plus one `space.sm` step (16dp).
 */
@Composable
internal fun TvShell(
    account: TvAccount,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf(TvDestination.Files) }
    val paneFocus = remember { FocusRequester() }
    val selectedItemFocus = remember { FocusRequester() }

    NavigationDrawer(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        drawerContent = {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp, vertical = 24.dp)
                    // First entry lands on the selected destination; later entries return
                    // to whichever item was focused last.
                    .focusRestorer(selectedItemFocus)
                    .focusGroup(),
            ) {
                Text(
                    text = stringResource(R.string.tv_wordmark),
                    style = MaterialTheme.typography.titleMedium,
                    color = PutioDesignTokens.yellowSolid,
                    modifier = Modifier.padding(start = 16.dp, bottom = 24.dp),
                )
                TvDestination.entries.forEach { entry ->
                    NavigationDrawerItem(
                        selected = entry == destination,
                        onClick = { destination = entry },
                        modifier = if (entry == destination) Modifier.focusRequester(selectedItemFocus) else Modifier,
                        leadingContent = {
                            Icon(
                                painter = painterResource(entry.icon),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                    ) {
                        Text(stringResource(entry.label))
                    }
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = OVERSCAN_X + PANE_INSET, vertical = OVERSCAN_Y + PANE_INSET),
        ) {
            when (destination) {
                TvDestination.Account -> TvAccountPane(account, onSignOut, paneFocus)
                else -> TvPlaceholderPane(destination, paneFocus)
            }
        }
    }

    // Runs after the chosen pane has composed, so its requester is attached.
    LaunchedEffect(destination) { paneFocus.requestFocus() }
}

@Composable
private fun TvPlaceholderPane(
    destination: TvDestination,
    paneFocus: FocusRequester,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PaneTitle(stringResource(destination.label))
        val placeholder = when (destination) {
            TvDestination.Files -> R.string.tv_placeholder_files
            TvDestination.Search -> R.string.tv_placeholder_search
            TvDestination.History -> R.string.tv_placeholder_history
            TvDestination.Account -> R.string.tv_destination_account
        }
        // The single focus target keeps D-pad entry deterministic before the
        // pane has real content; the row is a Material list item so it scales
        // like every other full-width row (1.02) rather than a stock 1.1 button.
        ListItem(
            selected = false,
            onClick = {},
            headlineContent = { Text(stringResource(placeholder)) },
            scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
            modifier = Modifier
                .padding(top = 32.dp)
                .focusRequester(paneFocus),
        )
    }
}

@Composable
private fun TvAccountPane(
    account: TvAccount,
    onSignOut: () -> Unit,
    paneFocus: FocusRequester,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PaneTitle(stringResource(R.string.tv_destination_account))
        Text(
            text = stringResource(R.string.tv_account_signed_in_as, account.username),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        ListItem(
            selected = false,
            onClick = onSignOut,
            headlineContent = { Text(stringResource(R.string.tv_account_sign_out)) },
            scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
            modifier = Modifier
                .padding(top = 32.dp)
                .focusRequester(paneFocus),
        )
    }
}

@Composable
private fun PaneTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/** Full-width rows scale less than compact surfaces so they stay inside the safe area. */
private const val FULL_WIDTH_FOCUSED_SCALE = 1.02f
private val OVERSCAN_X = 38.dp
private val OVERSCAN_Y = 11.dp
private val PANE_INSET = 16.dp
