package io.putdotio.android.tv

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemScale
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.tv.account.TvInactiveAccountNotice
import io.putdotio.android.tv.auth.TvAccount

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvShell(
    account: TvAccount,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    homePane: @Composable (paneFocus: FocusRequester) -> Unit = { TvHomePlaceholderPane(it) },
    filesPane: @Composable (paneFocus: FocusRequester) -> Unit = {
        TvPlaceholderPane(R.string.tv_placeholder_files, it)
    },
    searchPane: @Composable (paneFocus: FocusRequester) -> Unit = {
        TvPlaceholderPane(R.string.tv_placeholder_search, it)
    },
    historyPane: @Composable (paneFocus: FocusRequester) -> Unit = {
        TvPlaceholderPane(R.string.tv_placeholder_history, it)
    },
    accountPane: @Composable (paneFocus: FocusRequester) -> Unit = { TvAccountFallbackPane(account, onSignOut, it) },
    requestedDestination: TvDestination? = null,
    onDestinationRequestHandled: () -> Unit = {},
    /** Files' own Back step, such as leaving a folder; null when Files has none. */
    onFilesBack: (() -> Unit)? = null,
    showHome: Boolean = true,
    focusHomePane: Boolean = false,
) {
    val start = if (showHome) TvDestination.Home else TvDestination.Files
    var destination by rememberSaveable { mutableStateOf(start) }
    var tabBarHasFocus by remember { mutableStateOf(false) }
    val paneReleased = remember { mutableStateOf(false) }
    var focusPaneOf by remember { mutableStateOf<TvDestination?>(destination) }
    val tabFocus = remember { TvDestination.entries.associateWith { FocusRequester() } }
    val paneFocus = remember { FocusRequester() }
    val paneStates = rememberSaveableStateHolder()
    val panes = TvShellPanes(homePane, filesPane, searchPane, historyPane, accountPane)
    val tabs = TvDestination.entries.filter { showHome || it != TvDestination.Home }

    // Registered before any pane composes, so each pane's own Back handlers take precedence.
    BackHandler(enabled = !tabBarHasFocus) {
        paneReleased.value = true
        tabFocus.getValue(destination).requestFocus()
    }
    BackHandler(enabled = !tabBarHasFocus && destination == TvDestination.Files && onFilesBack != null) {
        onFilesBack?.invoke()
    }
    LaunchedEffect(requestedDestination) {
        if (requestedDestination != null) {
            val shown = requestedDestination.within(tabs, start)
            destination = shown
            focusPaneOf = shown
            paneReleased.value = false
            onDestinationRequestHandled()
        }
    }
    LaunchedEffect(showHome) {
        if (!showHome && destination == TvDestination.Home) destination = TvDestination.Files
    }
    LaunchedEffect(destination) { ProductAnalytics.capture(AnalyticsEvent.ScreenViewed(destination.analyticsScreen)) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .tvSafeAreaPadding(),
    ) {
        TvTabBar(
            tabs = tabs,
            selected = destination,
            tabFocus = tabFocus,
            onSelect = {
                destination = it
                paneReleased.value = true
            },
            modifier = Modifier
                .onFocusChanged { tabBarHasFocus = it.hasFocus }
                .focusProperties { enter = { tabBarEntry(it, tabFocus.getValue(destination)) } },
            paneFocus = paneFocus,
        )
        val notice = account.inactiveNotice
        if (notice != null) TvInactiveAccountNotice(notice, Modifier.padding(top = NOTICE_TOP_GAP))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = if (notice != null) NOTICE_PANE_GAP else TAB_BAR_GAP)
                .onFocusChanged { if (it.hasFocus) paneReleased.value = false },
        ) {
            CompositionLocalProvider(LocalTvPaneReleased provides paneReleased) {
                paneStates.SaveableStateProvider(destination.name) { panes.Pane(destination, paneFocus) }
            }
        }
    }

    if (tabBarHasFocus) {
        key(destination) {
            BackHandler(enabled = destination != start) {
                tabFocus.getValue(start).requestFocus()
            }
        }
    }
    val shown = destination
    val focusWanted = focusPaneOf == shown
    LaunchedEffect(shown, focusWanted) {
        if (focusWanted && focusPaneOf == shown) {
            focusPaneOf = null
            shown.entryFocus(focusHomePane, tabFocus, paneFocus).requestFocus()
        }
    }
}

private fun TvDestination.within(tabs: List<TvDestination>, start: TvDestination): TvDestination =
    if (this in tabs) this else start

private fun TvDestination.entryFocus(
    focusHomePane: Boolean,
    tabFocus: Map<TvDestination, FocusRequester>,
    paneFocus: FocusRequester,
): FocusRequester = if (this == TvDestination.Home && !focusHomePane) tabFocus.getValue(this) else paneFocus

private fun tabBarEntry(direction: FocusDirection, selectedTab: FocusRequester): FocusRequester =
    if (direction == FocusDirection.Enter) FocusRequester.Default else selectedTab

private class TvShellPanes(
    val home: @Composable (paneFocus: FocusRequester) -> Unit,
    val files: @Composable (paneFocus: FocusRequester) -> Unit,
    val search: @Composable (paneFocus: FocusRequester) -> Unit,
    val history: @Composable (paneFocus: FocusRequester) -> Unit,
    val account: @Composable (paneFocus: FocusRequester) -> Unit,
) {
    @Composable
    fun Pane(destination: TvDestination, paneFocus: FocusRequester) {
        when (destination) {
            TvDestination.Home -> home(paneFocus)
            TvDestination.Files -> files(paneFocus)
            TvDestination.Search -> search(paneFocus)
            TvDestination.History -> history(paneFocus)
            TvDestination.Account -> account(paneFocus)
        }
    }
}

@Composable
private fun TvTabBar(
    tabs: List<TvDestination>,
    selected: TvDestination,
    tabFocus: Map<TvDestination, FocusRequester>,
    onSelect: (TvDestination) -> Unit,
    paneFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier.height(TAB_BAR_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(DesignR.drawable.putio_wordmark),
            contentDescription = stringResource(R.string.tv_wordmark),
            modifier = Modifier.height(22.dp),
        )
        Row(
            modifier = modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { entry ->
                TvTab(
                    destination = entry,
                    selected = entry == selected,
                    onFocused = { if (entry != selected) onSelect(entry) },
                    modifier = Modifier
                        .focusRequester(tabFocus.getValue(entry))
                        .focusProperties { down = paneFocus },
                )
            }
        }
    }
}

@Composable
private fun TvTab(
    destination: TvDestination,
    selected: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val glyphTint = when {
        focused -> TvFocus.content
        selected -> colors.onSecondaryContainer
        else -> colors.onSurfaceVariant
    }
    Surface(
        onClick = onFocused,
        modifier = modifier
            .height(TAB_HEIGHT)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics {
                this.selected = selected
                role = Role.Tab
            },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colors.secondaryContainer else Color.Transparent,
            contentColor = if (selected) colors.onSurface else colors.onSurfaceVariant,
            focusedContainerColor = TvFocus.container,
            focusedContentColor = TvFocus.content,
            pressedContainerColor = TvFocus.container,
            pressedContentColor = TvFocus.content,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        interactionSource = interactions,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(TAB_PADDING),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(if (selected) destination.selectedIcon else destination.icon),
                contentDescription = null,
                tint = glyphTint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(destination.label),
                style = MaterialTheme.typography.labelMedium.copy(textMotion = TextMotion.Animated),
            )
        }
    }
}

@Composable
private fun TvHomePlaceholderPane(paneFocus: FocusRequester) {
    Box(modifier = Modifier.fillMaxSize().focusRequester(paneFocus).focusGroup()) {
        Text(
            text = stringResource(R.string.tv_placeholder_home),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TvPlaceholderPane(
    @StringRes placeholder: Int,
    paneFocus: FocusRequester,
) {
    ListItem(
        selected = false,
        onClick = {},
        headlineContent = { Text(stringResource(placeholder)) },
        scale = ListItemScale.None,
        modifier = Modifier.focusRequester(paneFocus),
    )
}

/** The default Account pane: who is signed in, and Sign out. */
@Composable
private fun TvAccountFallbackPane(
    account: TvAccount,
    onSignOut: () -> Unit,
    paneFocus: FocusRequester,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.tv_account_signed_in_as, account.username),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ListItem(
            selected = false,
            onClick = onSignOut,
            headlineContent = { Text(stringResource(R.string.tv_account_sign_out)) },
            scale = ListItemScale.None,
            modifier = Modifier
                .padding(top = 16.dp)
                .focusRequester(paneFocus),
        )
    }
}

internal val PANE_INSET = 16.dp
private val TAB_BAR_HEIGHT = 40.dp
private val TAB_BAR_GAP = 20.dp
private val NOTICE_TOP_GAP = 24.dp
private val NOTICE_PANE_GAP = 6.dp
private val TAB_HEIGHT = 28.dp
private val TAB_PADDING = PaddingValues(horizontal = 12.dp)

private val TvDestination.analyticsScreen: AnalyticsEvent.Screen
    get() =
        when (this) {
            TvDestination.Home -> AnalyticsEvent.Screen.Home
            TvDestination.Files -> AnalyticsEvent.Screen.Files
            TvDestination.Search -> AnalyticsEvent.Screen.Search
            TvDestination.History -> AnalyticsEvent.Screen.History
            TvDestination.Account -> AnalyticsEvent.Screen.Account
        }
