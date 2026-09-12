package io.putdotio.android.tv

import androidx.activity.compose.BackHandler
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
 * left, one destination pane on the right. Focus starts in the pane; on
 * re-entry the drawer restores focus to the item that last held it, and on
 * first entry to the selected destination.
 *
 * The pane inset is the tv token group's overscan (4% x 2% of a 1920x1080
 * canvas, halved for xhdpi: 38dp x 11dp) plus one `space.sm` step (16dp).
 */
@Composable
internal fun TvShell(
    account: TvAccount,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    filesPane: @Composable (paneFocus: FocusRequester) -> Unit = { TvPlaceholderPane(TvDestination.Files, it) },
    searchPane: @Composable (paneFocus: FocusRequester) -> Unit = { TvPlaceholderPane(TvDestination.Search, it) },
    historyPane: @Composable (paneFocus: FocusRequester) -> Unit = { TvPlaceholderPane(TvDestination.History, it) },
    /** Shown in Account's place after Manage your trash; null hides the row. Back returns to Account. */
    trashPane: (@Composable (paneFocus: FocusRequester) -> Unit)? = null,
    /** Set by a pane that wants another destination shown, such as Search opening a result in Files. */
    requestedDestination: TvDestination? = null,
    onDestinationRequestHandled: () -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(TvDestination.Files) }
    LaunchedEffect(requestedDestination) {
        if (requestedDestination != null) {
            destination = requestedDestination
            onDestinationRequestHandled()
        }
    }
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
                TvDestination.Files -> filesPane(paneFocus)
                TvDestination.Search -> searchPane(paneFocus)
                TvDestination.History -> historyPane(paneFocus)
                TvDestination.Account -> TvAccountPane(account, onSignOut, paneFocus, trashPane)
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
    trashPane: (@Composable (paneFocus: FocusRequester) -> Unit)?,
) {
    // Saved across recreation like the destination, forgotten with the pane: leaving for
    // another destination and coming back lands on Account itself, and the row that opened
    // Trash takes focus again on Back.
    var showingTrash by rememberSaveable { mutableStateOf(false) }
    if (trashPane != null && showingTrash) {
        BackHandler { showingTrash = false }
        trashPane(paneFocus)
        return
    }
    val trashFocus = remember { FocusRequester() }
    Column(modifier = Modifier.fillMaxSize()) {
        PaneTitle(stringResource(R.string.tv_destination_account))
        Text(
            text = stringResource(R.string.tv_account_signed_in_as, account.username),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (trashPane != null) {
            ListItem(
                selected = false,
                onClick = { showingTrash = true },
                headlineContent = { Text(stringResource(R.string.tv_account_manage_trash)) },
                leadingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_ph_trash),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                trailingContent = {
                    Icon(
                        painter = painterResource(R.drawable.ic_ph_caret_right),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
                modifier = Modifier
                    .padding(top = 32.dp)
                    .focusRequester(paneFocus)
                    .focusRequester(trashFocus),
            )
        }
        // Sign out is the last row, per the contract.
        ListItem(
            selected = false,
            onClick = onSignOut,
            headlineContent = { Text(stringResource(R.string.tv_account_sign_out)) },
            scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
            modifier = Modifier
                .padding(top = if (trashPane != null) 8.dp else 32.dp)
                .then(if (trashPane == null) Modifier.focusRequester(paneFocus) else Modifier),
        )
    }
    // Back from Trash: the pane is recomposed with the row that opened it, which takes focus.
    LaunchedEffect(showingTrash) { if (!showingTrash && trashPane != null) trashFocus.requestFocus() }
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
