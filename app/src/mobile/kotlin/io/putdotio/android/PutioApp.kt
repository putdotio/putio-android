package io.putdotio.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.putdotio.android.auth.CustomTabsOAuthBrowser
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthController
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.MobileSignedOutReason
import io.putdotio.android.auth.OAuthBrowserLaunchResult
import io.putdotio.android.auth.OAuthLaunchResult
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesBrowserController
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesContent
import io.putdotio.android.files.FilesFailure
import io.putdotio.android.files.FilesPaging
import io.putdotio.android.files.SdkFilesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val MOBILE_NAV_BAR_TAG = "mobile-navigation-bar"
internal const val MOBILE_NAV_RAIL_TAG = "mobile-navigation-rail"

private val TabletMinWidth = 600.dp

@Composable
fun PutioApp() {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) { MobileOAuthRuntime.get(context) }
    val oauthBrowser = remember { CustomTabsOAuthBrowser() }

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
    oauthBrowser: CustomTabsOAuthBrowser,
) {
    val authController = runtime.authController
    val authState by authController.state.collectAsStateWithLifecycle()
    val rootScope = rememberCoroutineScope()
    val activity = LocalContext.current.findActivity()

    LaunchedEffect(authController) {
        authController.restoreSession()
    }
    OAuthBrowserReturnEffect(
        runtime = runtime,
        authController = authController,
        rootScope = rootScope,
    )

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
                                val launchResult = activity?.let { oauthBrowser.launch(it, authorization) }
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
                authController = authController,
                rootScope = rootScope,
            )
    }
}

@Composable
private fun OAuthBrowserReturnEffect(
    runtime: MobileOAuthRuntime,
    authController: MobileAuthController,
    rootScope: CoroutineScope,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var leftWhileAwaitingCallback by rememberSaveable { mutableStateOf(false) }
    var callbackSequenceWhenPaused by rememberSaveable { mutableStateOf<Long?>(null) }
    val returnTracker = remember(lifecycleOwner) {
        MobileOAuthBrowserReturnTracker(
            initialLeftWhileAwaitingCallback = leftWhileAwaitingCallback,
            initialCallbackSequenceWhenPaused = callbackSequenceWhenPaused,
        )
    }

    DisposableEffect(lifecycleOwner, runtime, authController, rootScope, returnTracker) {
        val observer = LifecycleEventObserver { _, event ->
            val awaitingCallback =
                authController.state.value == MobileAuthState.AwaitingOAuthCallback
            val cancelledInBrowser = returnTracker.onLifecycleEvent(
                event = event,
                awaitingCallback = awaitingCallback,
                callbackDispatchSequence = runtime.callbackDispatchSequence(),
            )
            leftWhileAwaitingCallback = returnTracker.leftWhileAwaitingCallback
            callbackSequenceWhenPaused = returnTracker.callbackSequenceWhenPaused
            if (cancelledInBrowser) {
                rootScope.launch { authController.cancelSignIn() }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    if (!canSignIn || reason == MobileSignedOutReason.OAuthNotConfigured) {
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
    authController: MobileAuthController,
    rootScope: CoroutineScope,
) {
    val filesController = remember(runtime.putioClient, account.userId) {
        FilesBrowserController(
            repository = SdkFilesRepository(runtime.putioClient),
            parentScope = rootScope,
        )
    }
    val filesState by filesController.state.collectAsStateWithLifecycle()
    val authoritativeFailure = filesState.authoritativeSessionFailure()

    DisposableEffect(filesController) {
        onDispose { filesController.close() }
    }
    LaunchedEffect(authoritativeFailure) {
        if (authoritativeFailure != null) {
            authController.rejectAuthoritativeSession()
        }
    }

    MobileShell(
        filesState = filesState,
        account = account,
        onFilesEvent = filesController::dispatch,
        onSignOut = { rootScope.launch { authController.logout() } },
    )
}

@Composable
internal fun MobileShell(
    filesState: FilesBrowserState,
    account: MobileAccount,
    onFilesEvent: (FilesBrowserEvent) -> Unit,
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selectedDestination = MobileDestination.fromRoute(backStackEntry?.destination?.route)

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
                account = account,
                onFilesEvent = onFilesEvent,
                onSignOut = onSignOut,
            )
        } else {
            PhoneShell(
                navController = navController,
                selectedDestination = selectedDestination,
                filesState = filesState,
                account = account,
                onFilesEvent = onFilesEvent,
                onSignOut = onSignOut,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneShell(
    navController: NavHostController,
    selectedDestination: MobileDestination,
    filesState: FilesBrowserState,
    account: MobileAccount,
    onFilesEvent: (FilesBrowserEvent) -> Unit,
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
            account = account,
            onFilesEvent = onFilesEvent,
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
    account: MobileAccount,
    onFilesEvent: (FilesBrowserEvent) -> Unit,
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
                account = account,
                onFilesEvent = onFilesEvent,
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
    account: MobileAccount,
    onFilesEvent: (FilesBrowserEvent) -> Unit,
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
            MobileEmptyState(
                title = stringResource(R.string.mobile_search_empty_title),
                message = stringResource(R.string.mobile_search_empty_message),
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
        failure?.takeIf {
            it is FilesFailure.AuthenticationRequired || it is FilesFailure.AccessDenied
        }
    }

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun NavHostController.navigateTo(destination: MobileDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
