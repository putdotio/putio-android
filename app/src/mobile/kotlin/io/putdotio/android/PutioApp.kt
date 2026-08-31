package io.putdotio.android

import android.content.Intent
import android.app.Application
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.putdotio.android.auth.AuthTabOAuthBrowser
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthController
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.auth.OAuthBrowserLaunchResult
import io.putdotio.android.auth.OAuthLaunchResult
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.FilesItem
import io.putdotio.android.files.SdkFilesRepository
import io.putdotio.android.history.HistoryContent
import io.putdotio.android.history.HistoryEvent
import io.putdotio.android.history.HistoryState
import io.putdotio.android.history.SdkHistoryRepository
import io.putdotio.android.search.RecentSearchEdit
import io.putdotio.android.search.SdkSearchRepository
import io.putdotio.android.search.SearchContent
import io.putdotio.android.search.SearchState
import io.putdotio.android.search.SearchTerm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

internal const val MOBILE_NAV_BAR_TAG = "mobile-navigation-bar"
internal const val MOBILE_NAV_RAIL_TAG = "mobile-navigation-rail"

private val TabletMinWidth = 600.dp

@Composable
fun PutioApp(
    authTabLauncher: ActivityResultLauncher<Intent>? = null,
) {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) { MobileOAuthRuntime.get(context) }
    val oauthBrowser = remember(context.applicationContext, authTabLauncher) {
        authTabLauncher?.let { AuthTabOAuthBrowser(context, it) }
    }

    PutioTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            MobileAuthRoot(
                runtime = runtime,
                oauthBrowser = oauthBrowser,
            )
        }
    }
}

@Composable
private fun MobileAuthRoot(
    runtime: MobileOAuthRuntime,
    oauthBrowser: AuthTabOAuthBrowser?,
) {
    val context = LocalContext.current
    val authController = runtime.authController
    val authState by authController.state.collectAsStateWithLifecycle()
    val rootScope = rememberCoroutineScope()
    val filesViewModel = viewModel<MobileFilesViewModel>(
        factory = remember(authController) { mobileFilesViewModelFactory(authController.state) },
    )
    val searchHistoryViewModel = viewModel<MobileSearchHistoryViewModel>(
        factory = remember(authController, context.applicationContext) {
            mobileSearchHistoryViewModelFactory(
                context.applicationContext as Application,
                authController.state,
            )
        },
    )

    LaunchedEffect(authController) {
        authController.restoreSession()
    }
    when (val state = authState) {
        MobileAuthState.Initializing,
        MobileAuthState.RestoringSession,
        -> MobileLoadingState(stringResource(R.string.mobile_auth_restoring))

        is MobileAuthState.ValidatingSession ->
            MobileLoadingState(stringResource(R.string.mobile_auth_validating))

        MobileAuthState.SigningOut ->
            MobileLoadingState(stringResource(R.string.mobile_auth_signing_out))

        is MobileAuthState.SignedOut ->
            MobileSignedOutScreen(
                reason = state.reason,
                canSignIn = authController.isOAuthConfigured,
                onSignIn = {
                    rootScope.launch {
                        when (val authorization = authController.beginSignIn()) {
                            is OAuthLaunchResult.Ready -> {
                                val launchResult = oauthBrowser?.launch(authorization)
                                if (launchResult !is OAuthBrowserLaunchResult.Launched) {
                                    authController.failSignIn()
                                }
                            }

                            OAuthLaunchResult.NotAllowed,
                            OAuthLaunchResult.NotConfigured,
                            OAuthLaunchResult.StorageUnavailable,
                            -> Unit
                        }
                    }
                },
            )

        MobileAuthState.AwaitingOAuthCallback ->
            MobileAuthMessageScreen(
                title = stringResource(R.string.mobile_auth_browser_title),
                message = stringResource(R.string.mobile_auth_browser_message),
                actionLabel = stringResource(R.string.mobile_auth_cancel),
                onAction = { rootScope.launch { authController.cancelSignIn() } },
            )

        is MobileAuthState.ValidationUnavailable ->
            MobileAuthMessageScreen(
                title = stringResource(R.string.mobile_auth_unavailable_title),
                message = stringResource(R.string.mobile_auth_unavailable_message),
                actionLabel = stringResource(R.string.mobile_action_retry),
                onAction = { rootScope.launch { authController.retryValidation() } },
            )

        is MobileAuthState.SignedIn ->
            SignedInMobileRoot(
                runtime = runtime,
                account = state.account,
                sessionId = state.sessionId,
                filesViewModel = filesViewModel,
                searchHistoryViewModel = searchHistoryViewModel,
                authController = authController,
                rootScope = rootScope,
            )
    }
}

@Composable
internal fun MobileSignedOutScreen(
    reason: MobileSignedOutReason?,
    canSignIn: Boolean,
    onSignIn: () -> Unit,
) {
    @StringRes val title: Int
    @StringRes val message: Int
    when (reason) {
        null -> {
            title = R.string.mobile_auth_signed_out_title
            message = R.string.mobile_auth_signed_out_message
        }

        MobileSignedOutReason.SessionExpired -> {
            title = R.string.mobile_auth_expired_title
            message = R.string.mobile_auth_expired_message
        }

        MobileSignedOutReason.SignInFailed -> {
            title = R.string.mobile_auth_failed_title
            message = R.string.mobile_auth_failed_message
        }

        MobileSignedOutReason.OAuthNotConfigured -> {
            title = R.string.mobile_auth_not_configured_title
            message = R.string.mobile_auth_not_configured_message
        }

        MobileSignedOutReason.SecureStorageUnavailable -> {
            title = R.string.mobile_auth_storage_title
            message = R.string.mobile_auth_storage_message
        }
    }

    if (
        !canSignIn ||
        reason == MobileSignedOutReason.OAuthNotConfigured ||
        reason == MobileSignedOutReason.SecureStorageUnavailable
    ) {
        MobileEmptyState(
            title = stringResource(title),
            message = stringResource(message),
        )
        return
    }

    MobileAuthMessageScreen(
        title = stringResource(title),
        message = stringResource(message),
        actionLabel = stringResource(R.string.mobile_auth_sign_in),
        onAction = onSignIn,
    )
}

@Composable
private fun SignedInMobileRoot(
    runtime: MobileOAuthRuntime,
    account: MobileAccount,
    sessionId: MobileAuthSessionId,
    filesViewModel: MobileFilesViewModel,
    searchHistoryViewModel: MobileSearchHistoryViewModel,
    authController: MobileAuthController,
    rootScope: CoroutineScope,
) {
    val filesRepository = remember(runtime.putioClient) {
        SdkFilesRepository(runtime.putioClient)
    }
    val filesController = remember(filesViewModel, filesRepository, account.userId, sessionId) {
        filesViewModel.controllerFor(
            userId = account.userId,
            sessionId = sessionId,
            repository = filesRepository,
        )
    }
    val searchRepository = remember(runtime.putioClient) { SdkSearchRepository(runtime.putioClient) }
    val historyRepository = remember(runtime.putioClient) { SdkHistoryRepository(runtime.putioClient) }
    val searchHistorySession =
        remember(searchHistoryViewModel, runtime.putioClient, account, sessionId) {
            searchHistoryViewModel.controllersFor(
                userId = account.userId,
                sessionId = sessionId,
                historyEnabled = account.historyEnabled,
                putioClient = runtime.putioClient,
                searchRepository = searchRepository,
                historyRepository = historyRepository,
                filesItemResolver = filesRepository,
            )
        }
    if (filesController == null || searchHistorySession == null) {
        MobileLoadingState(stringResource(R.string.mobile_state_loading))
        return
    }
    val filesState by filesController.state.collectAsStateWithLifecycle()
    val searchState by searchHistorySession.search.state.collectAsStateWithLifecycle()
    val historyState by searchHistorySession.history.state.collectAsStateWithLifecycle()
    val navigationFailure by searchHistorySession.navigationFailure.collectAsStateWithLifecycle()
    val recentSearchFailure by searchHistorySession.recentSearchFailure.collectAsStateWithLifecycle()
    val authoritativeFailure =
        filesState.authoritativeSessionFailure()
            ?: searchState.authoritativeSessionFailure()
            ?: historyState.authoritativeSessionFailure()
            ?: recentSearchFailure?.takeIf { it is FilesFailure.AuthenticationRequired }
            ?: navigationFailure?.takeIf { it is FilesFailure.AuthenticationRequired }

    LaunchedEffect(authoritativeFailure) {
        if (authoritativeFailure != null) {
            authController.rejectAuthoritativeSession()
        }
    }

    MobileShell(
        filesState = filesState,
        searchHistoryState =
            MobileSearchHistoryState(
                search = searchState,
                history = historyState,
                recentSearchFailure =
                    recentSearchFailure?.takeUnless { it is FilesFailure.AuthenticationRequired },
            ),
        account = account,
        onFilesEvent = filesController::dispatch,
        searchHistoryActions =
            MobileSearchHistoryActions(
                onQueryChanged = { searchHistorySession.search.updateQuery(it) },
                onSubmit = { searchHistorySession.search.submit() },
                onResult = { searchHistorySession.search.openResult(it.id) },
                onNextPage = { searchHistorySession.search.loadNextPage() },
                onRetry = { searchHistorySession.search.retry() },
                onRecentSearch = { term ->
                    searchHistorySession.search.updateQuery(term.value)
                    searchHistorySession.search.submit()
                },
                onRecentEdit = { searchHistorySession.search.editRecentSearches(it) },
                onRecentRetry = searchHistorySession::retryRecentSearches,
                onHistoryEvent = { searchHistorySession.history.dispatch(it) },
            ),
        contentNavigation = searchHistorySession.navigation,
        navigationFailure = navigationFailure,
        onDismissNavigationFailure = searchHistorySession::dismissNavigationFailure,
        onSignOut = { rootScope.launch { authController.logout() } },
    )
}

@Composable
internal fun MobileShell(
    filesState: FilesBrowserState,
    searchHistoryState: MobileSearchHistoryState = emptySearchHistoryState(),
    account: MobileAccount,
    onFilesEvent: (FilesBrowserEvent) -> Unit,
    searchHistoryActions: MobileSearchHistoryActions = MobileSearchHistoryActions(),
    contentNavigation: Flow<FilesItem> = emptyFlow(),
    navigationFailure: FilesFailure? = null,
    onDismissNavigationFailure: () -> Unit = {},
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedDestination = MobileDestination.fromRoute(backStackEntry?.destination?.route)

    LaunchedEffect(contentNavigation) {
        contentNavigation.collect { item ->
            onFilesEvent(FilesBrowserEvent.OpenExternalItem(item))
            navController.navigateTo(MobileDestination.Files)
        }
    }

    BackHandler(
        enabled = selectedDestination == MobileDestination.Files && filesState.canNavigateBack,
    ) {
        onFilesEvent(FilesBrowserEvent.NavigateBack)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= TabletMinWidth) {
            TabletShell(
                navController = navController,
                selectedDestination = selectedDestination,
                filesState = filesState,
                searchHistoryState = searchHistoryState,
                account = account,
                onFilesEvent = onFilesEvent,
                searchHistoryActions = searchHistoryActions,
                onSignOut = onSignOut,
            )
        } else {
            PhoneShell(
                navController = navController,
                selectedDestination = selectedDestination,
                filesState = filesState,
                searchHistoryState = searchHistoryState,
                account = account,
                onFilesEvent = onFilesEvent,
                searchHistoryActions = searchHistoryActions,
                onSignOut = onSignOut,
            )
        }
    }
    if (navigationFailure != null && navigationFailure !is FilesFailure.AuthenticationRequired) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissNavigationFailure,
            title = { Text(stringResource(R.string.mobile_navigation_error_title)) },
            text = { Text(stringResource(navigationFailure.mobileMessageResource())) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onDismissNavigationFailure) {
                    Text(stringResource(R.string.mobile_action_ok))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneShell(
    navController: NavHostController,
    selectedDestination: MobileDestination,
    filesState: FilesBrowserState,
    searchHistoryState: MobileSearchHistoryState,
    account: MobileAccount,
    onFilesEvent: (FilesBrowserEvent) -> Unit,
    searchHistoryActions: MobileSearchHistoryActions,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            MobileTopBar(
                destination = selectedDestination,
                filesState = filesState,
                onFilesBack = { onFilesEvent(FilesBrowserEvent.NavigateBack) },
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag(MOBILE_NAV_BAR_TAG)) {
                MobileDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == selectedDestination,
                        onClick = { navController.navigateTo(destination) },
                        icon = { MobileDestinationIcon(destination, destination == selectedDestination) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        MobileNavHost(
            navController = navController,
            filesState = filesState,
            searchHistoryState = searchHistoryState,
            account = account,
            onFilesEvent = onFilesEvent,
            searchHistoryActions = searchHistoryActions,
            onSignOut = onSignOut,
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabletShell(
    navController: NavHostController,
    selectedDestination: MobileDestination,
    filesState: FilesBrowserState,
    searchHistoryState: MobileSearchHistoryState,
    account: MobileAccount,
    onFilesEvent: (FilesBrowserEvent) -> Unit,
    searchHistoryActions: MobileSearchHistoryActions,
    onSignOut: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail(modifier = Modifier.testTag(MOBILE_NAV_RAIL_TAG)) {
            MobileDestination.entries.forEach { destination ->
                NavigationRailItem(
                    selected = destination == selectedDestination,
                    onClick = { navController.navigateTo(destination) },
                    icon = { MobileDestinationIcon(destination, destination == selectedDestination) },
                    label = { Text(stringResource(destination.labelRes)) },
                )
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            topBar = {
                MobileTopBar(
                    destination = selectedDestination,
                    filesState = filesState,
                    onFilesBack = { onFilesEvent(FilesBrowserEvent.NavigateBack) },
                )
            },
        ) { padding ->
            MobileNavHost(
                navController = navController,
                filesState = filesState,
                searchHistoryState = searchHistoryState,
                account = account,
                onFilesEvent = onFilesEvent,
                searchHistoryActions = searchHistoryActions,
                onSignOut = onSignOut,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileTopBar(
    destination: MobileDestination,
    filesState: FilesBrowserState,
    onFilesBack: () -> Unit,
) {
    val filesFolderName = filesState.current.folder.name?.takeIf(String::isNotBlank)
    TopAppBar(
        title = {
            Text(
                if (destination == MobileDestination.Files && filesFolderName != null) {
                    filesFolderName
                } else {
                    stringResource(destination.labelRes)
                },
            )
        },
        navigationIcon = {
            if (destination == MobileDestination.Files && filesState.canNavigateBack) {
                IconButton(onClick = onFilesBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_ph_arrow_left),
                        contentDescription = stringResource(R.string.mobile_action_back),
                    )
                }
            }
        },
    )
}

@Composable
private fun MobileDestinationIcon(
    destination: MobileDestination,
    selected: Boolean,
) {
    Icon(
        painter = painterResource(if (selected) destination.selectedIcon else destination.icon),
        contentDescription = null,
    )
}

@Composable
private fun MobileNavHost(
    navController: NavHostController,
    filesState: FilesBrowserState,
    searchHistoryState: MobileSearchHistoryState,
    account: MobileAccount,
    onFilesEvent: (FilesBrowserEvent) -> Unit,
    searchHistoryActions: MobileSearchHistoryActions,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = MobileDestination.start.route,
        modifier = modifier,
    ) {
        composable(MobileDestination.Files.route) {
            MobileFilesScreen(
                state = filesState,
                onEvent = onFilesEvent,
            )
        }
        composable(MobileDestination.Search.route) {
            MobileSearchHistoryScreen(
                searchState = searchHistoryState.search,
                historyState = searchHistoryState.history,
                recentSearchFailure = searchHistoryState.recentSearchFailure,
                onSearchQueryChanged = searchHistoryActions.onQueryChanged,
                onSearchSubmit = searchHistoryActions.onSubmit,
                onSearchResult = searchHistoryActions.onResult,
                onSearchNextPage = searchHistoryActions.onNextPage,
                onSearchRetry = searchHistoryActions.onRetry,
                onRecentSearch = searchHistoryActions.onRecentSearch,
                onRecentEdit = searchHistoryActions.onRecentEdit,
                onRecentRetry = searchHistoryActions.onRecentRetry,
                onHistoryEvent = searchHistoryActions.onHistoryEvent,
            )
        }
        composable(MobileDestination.Transfers.route) {
            MobileEmptyState(
                title = stringResource(R.string.mobile_transfers_empty_title),
                message = stringResource(R.string.mobile_transfers_empty_message),
            )
        }
        composable(MobileDestination.Account.route) {
            MobileAccountScreen(
                account = account,
                onSignOut = onSignOut,
            )
        }
    }
}

internal fun FilesBrowserState.authoritativeSessionFailure(): FilesFailure? =
    stack.asReversed().firstNotNullOfOrNull { folder ->
        val failure = when (val content = folder.content) {
            is FilesContent.Failed -> content.failure
            is FilesContent.Empty -> (content.paging as? FilesPaging.Failed)?.failure
            is FilesContent.Ready -> (content.paging as? FilesPaging.Failed)?.failure
            is FilesContent.Loading -> null
        }
        failure?.takeIf { it is FilesFailure.AuthenticationRequired }
    }

internal fun SearchState.authoritativeSessionFailure(): FilesFailure? =
    when (val value = content) {
        is SearchContent.Failed -> value.failure
        is SearchContent.Empty -> (value.paging as? io.putdotio.android.search.SearchPaging.Failed)?.failure
        is SearchContent.Ready -> (value.paging as? io.putdotio.android.search.SearchPaging.Failed)?.failure
        SearchContent.Idle,
        is SearchContent.Debouncing,
        is SearchContent.Loading,
        -> null
    }?.takeIf { it is FilesFailure.AuthenticationRequired }

internal fun HistoryState.authoritativeSessionFailure(): FilesFailure? =
    listOfNotNull(
        when (val value = content) {
            is HistoryContent.Failed -> value.failure
            is HistoryContent.Ready ->
                (value.paging as? io.putdotio.android.history.HistoryPaging.Failed)?.failure
            HistoryContent.Disabled,
            HistoryContent.Empty,
            is HistoryContent.Loading,
            -> null
        },
        (clearing as? io.putdotio.android.history.HistoryClearing.Failed)?.failure,
    ).firstOrNull { it is FilesFailure.AuthenticationRequired }

internal data class MobileSearchHistoryState(
    val search: SearchState,
    val history: HistoryState,
    val recentSearchFailure: FilesFailure?,
)

internal data class MobileSearchHistoryActions(
    val onQueryChanged: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onResult: (FilesItem) -> Unit = {},
    val onNextPage: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onRecentSearch: (SearchTerm) -> Unit = {},
    val onRecentEdit: (RecentSearchEdit) -> Unit = {},
    val onRecentRetry: () -> Unit = {},
    val onHistoryEvent: (HistoryEvent) -> Unit = {},
)

private fun emptySearchHistoryState(): MobileSearchHistoryState =
    MobileSearchHistoryState(
        search =
            SearchState(
                query = "",
                content = SearchContent.Idle,
                recentTerms = emptyList(),
                consumedCursors = emptySet(),
                nextRequestValue = 1L,
            ),
        history = HistoryState(HistoryContent.Disabled),
        recentSearchFailure = null,
    )

private fun NavHostController.navigateTo(destination: MobileDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
