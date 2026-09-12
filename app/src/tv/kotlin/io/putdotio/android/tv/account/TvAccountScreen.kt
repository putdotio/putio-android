package io.putdotio.android.tv.account

import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.putdotio.android.BuildConfig
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.isSupportedAvatarUrl
import io.putdotio.android.playback.playbackPreference
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigFailure
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.AppDiagnostics
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.tv.FULL_WIDTH_FOCUSED_SCALE
import io.putdotio.android.tv.TvButton
import io.putdotio.android.tv.TvChoice
import io.putdotio.android.tv.TvChoiceDialog
import io.putdotio.android.tv.TvPaneFocusOwner
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.sdk.files.PlaybackPreference

internal const val TV_ACCOUNT_STORAGE_TAG = "tv-account-storage"

/**
 * Account per oracle captures 09–12 and 14: the identity and quota header with Sign out
 * on the right, then Playback settings, Storage settings, and App and device information
 * as full-width rows with `primary` switches and centred choice dialogs, and Sign out as
 * the final row. Manage your trash swaps in [trashPane]; Back returns to that row.
 *
 * Focus enters on the first settings row once account settings are ready, and on the
 * header's Sign out until then. A dialog's close returns focus to the row that opened it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvAccountScreen(
    account: TvAccount,
    settingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    onSettingsEvent: (AccountSettingsEvent) -> Boolean,
    onAppConfigEvent: (AndroidAppConfigEvent) -> Boolean,
    onSignOut: () -> Unit,
    paneFocus: FocusRequester,
    modifier: Modifier = Modifier,
    /** The trash's total size when a listing has been read; the row shows it as its value. */
    trashSizeBytes: Long? = null,
    /** Shown in the pane's place after Manage your trash; null hides the row. */
    trashPane: (@Composable (paneFocus: FocusRequester) -> Unit)? = null,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
        AccountSettingsRepositoryResult.Failure(
            AccountSettingsFailure.Unexpected(IllegalStateException("Tunnel routes are unavailable")),
        )
    },
    /** Changes with the signed-in session so one account's dialogs never greet the next. */
    sessionKey: Any? = null,
) = key(sessionKey) {
    // Saved across recreation, forgotten with the pane: leaving for another destination and
    // coming back lands on Account itself, and the row that opened Trash takes focus on Back.
    var showingTrash by rememberSaveable { mutableStateOf(false) }
    // Set by Back only, so a first visit to Account never pulls focus off the drawer.
    val returningFromTrash = remember { mutableStateOf(false) }
    if (trashPane != null && showingTrash) {
        BackHandler {
            showingTrash = false
            returningFromTrash.value = true
        }
        trashPane(paneFocus)
    } else {
        TvAccountBody(
            account = account,
            settingsState = settingsState,
            appConfigState = appConfigState,
            onSettingsEvent = onSettingsEvent,
            onAppConfigEvent = onAppConfigEvent,
            onSignOut = onSignOut,
            paneFocus = paneFocus,
            modifier = modifier,
            trashSizeBytes = trashSizeBytes,
            onManageTrash = if (trashPane == null) null else ({ showingTrash = true }),
            returningFromTrash = returningFromTrash,
            loadTunnelRoutes = loadTunnelRoutes,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun TvAccountBody(
    account: TvAccount,
    settingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    onSettingsEvent: (AccountSettingsEvent) -> Boolean,
    onAppConfigEvent: (AndroidAppConfigEvent) -> Boolean,
    onSignOut: () -> Unit,
    paneFocus: FocusRequester,
    modifier: Modifier,
    trashSizeBytes: Long?,
    onManageTrash: (() -> Unit)?,
    returningFromTrash: MutableState<Boolean>,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
) {
    var chooseRoute by rememberSaveable { mutableStateOf(false) }
    var choosePlaybackType by rememberSaveable { mutableStateOf(false) }
    var confirmTrashOff by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val settingsReady = settingsState.content as? AccountSettingsContent.Ready
    val appConfigReady = appConfigState.content as? AndroidAppConfigContent.Ready

    val signOutFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    val trashFocus = remember { FocusRequester() }
    val paneHasFocus = remember { mutableStateOf(true) }
    // Losing the Trash pane's focused node makes the window re-enter this pane on its own;
    // the entry point is the row that opened Trash from the first composition, so that
    // re-entry lands there rather than on the first row.
    val entryTarget = remember { mutableStateOf(if (returningFromTrash.value) trashFocus else signOutFocus) }
    val rowsPresent = rememberUpdatedState(settingsReady != null)
    val owner = remember {
        TvPaneFocusOwner(entryTarget, paneHasFocus) { if (rowsPresent.value) firstRowFocus else signOutFocus }
    }
    val dialogOpen = chooseRoute || choosePlaybackType || confirmTrashOff || showDiagnostics
    val dialogShowing = rememberUpdatedState(dialogOpen)
    val dialogWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) {
        val closing = dialogWasOpen.value && !dialogOpen
        dialogWasOpen.value = dialogOpen
        if (!closing || !paneHasFocus.value) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value) entryTarget.value.requestFocus()
    }
    // Back from Trash: the pane is recomposed with the row that opened it, which takes focus
    // once the rows are laid out. Otherwise the entry point takes it, unless settings rows
    // are about to: the body only mounts when the shell is entering the pane or Trash is
    // handing it back, so this never pulls focus off the drawer, and the shell's own request
    // through the Column's entry cannot be relied on before the first layout.
    LaunchedEffect(Unit) {
        if (returningFromTrash.value) {
            withFrameNanos {}
            returningFromTrash.value = false
            if (paneHasFocus.value && !dialogShowing.value) trashFocus.requestFocus()
        } else if (!rowsPresent.value && paneHasFocus.value && !dialogShowing.value) {
            entryTarget.value.requestFocus()
        }
    }
    // A row or notice that held focus left composition (Try again starts the retry that
    // removes it): the entry point it named takes focus once the frame has settled.
    val refocusRequests by owner.refocusRequests
    LaunchedEffect(refocusRequests) {
        if (refocusRequests == 0) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value && !dialogShowing.value) owner.focusEntry()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(paneFocus)
            .onFocusChanged { if (it.hasFocus) paneHasFocus.value = true }
            .focusProperties {
                enter = { entryTarget.value }
                exit = {
                    paneHasFocus.value = false
                    FocusRequester.Default
                }
            }
            .focusGroup(),
    ) {
        TvAccountHeader(
            account = account,
            onSignOut = onSignOut,
            signOutModifier = owner.section(signOutFocus).focusRequester(signOutFocus),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
        ) {
            TvSectionHeader(stringResource(R.string.tv_account_section_playback))
            TvAccountSettingsSection(
                state = settingsState,
                onEvent = onSettingsEvent,
                owner = owner,
                firstRowFocus = firstRowFocus,
                signOutFocus = signOutFocus,
                dialogShowing = dialogShowing.value,
                onChooseRoute = { chooseRoute = true },
            )
            TvAppConfigSection(
                state = appConfigState,
                onEvent = onAppConfigEvent,
                owner = owner,
                onChoosePlaybackType = { choosePlaybackType = true },
            )
            settingsReady?.let { ready ->
                val controls = settingsState.controlsEnabled()
                val change = { change: AccountSettingsChange ->
                    if (controls) onSettingsEvent(AccountSettingsEvent.ChangeRequested(change))
                }
                TvSettingsSwitchRow(
                    title = stringResource(R.string.tv_account_show_subtitles),
                    icon = R.drawable.ic_ph_subtitles,
                    checked = ready.preferences.showSubtitles,
                    key = AccountSettingsKey.ShowSubtitles,
                    state = settingsState,
                    onEvent = onSettingsEvent,
                    onToggle = { change(AccountSettingsChange(AccountSettingsKey.ShowSubtitles, it)) },
                    owner = owner,
                )
                // The oracle words this one negatively; the account key is the positive one.
                TvSettingsSwitchRow(
                    title = stringResource(R.string.tv_account_no_auto_subtitles),
                    icon = R.drawable.ic_ph_list_checks,
                    checked = !ready.preferences.autoSelectSubtitles,
                    key = AccountSettingsKey.AutoSelectSubtitles,
                    state = settingsState,
                    onEvent = onSettingsEvent,
                    onToggle = { change(AccountSettingsChange(AccountSettingsKey.AutoSelectSubtitles, !it)) },
                    owner = owner,
                )
            }
            TvSectionHeader(stringResource(R.string.tv_account_section_storage))
            settingsReady?.let { ready ->
                val controls = settingsState.controlsEnabled()
                TvSettingsSwitchRow(
                    title = stringResource(R.string.tv_account_trash_enabled),
                    icon = R.drawable.ic_ph_recycle,
                    checked = ready.preferences.trashEnabled,
                    key = AccountSettingsKey.Trash,
                    state = settingsState,
                    onEvent = onSettingsEvent,
                    onToggle = { enabled ->
                        if (!controls) {
                            Unit
                        } else if (!enabled) {
                            confirmTrashOff = true
                        } else {
                            onSettingsEvent(
                                AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.Trash, true)),
                            )
                        }
                    },
                    owner = owner,
                )
            }
            // Trash is its own listing: reachable whether or not account settings loaded.
            if (onManageTrash != null) {
                val context = LocalContext.current
                TvChoiceRow(
                    title = stringResource(R.string.tv_account_manage_trash),
                    value = trashSizeBytes?.let { Formatter.formatShortFileSize(context, it.coerceAtLeast(0L)) },
                    icon = R.drawable.ic_ph_trash,
                    onClick = onManageTrash,
                    owner = owner,
                    focus = trashFocus,
                )
            }
            settingsReady?.let { ready ->
                val controls = settingsState.controlsEnabled()
                TvSettingsSwitchRow(
                    title = stringResource(R.string.tv_account_history_enabled),
                    icon = R.drawable.ic_ph_clock_counter_clockwise,
                    checked = ready.preferences.historyEnabled,
                    key = AccountSettingsKey.History,
                    state = settingsState,
                    onEvent = onSettingsEvent,
                    onToggle = {
                        if (controls) {
                            onSettingsEvent(
                                AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.History, it)),
                            )
                        }
                    },
                    owner = owner,
                )
            }
            TvSectionHeader(stringResource(R.string.tv_account_section_device))
            TvDeviceSection(owner = owner, onDiagnostics = { showDiagnostics = true })
            // Sign out is the last row, per the contract.
            TvAccountRow(
                title = stringResource(R.string.tv_account_sign_out),
                icon = R.drawable.ic_ph_sign_out,
                onClick = onSignOut,
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
                owner = owner,
            )
        }
    }

    if (chooseRoute && settingsReady != null) {
        TvTunnelRouteDialog(
            selected = settingsReady.preferences.tunnelRoute,
            loadRoutes = loadTunnelRoutes,
            onSelect = { route ->
                chooseRoute = false
                onSettingsEvent(AccountSettingsEvent.ChangeRequested(AccountSettingsChange.Route(route)))
            },
            onDismiss = { chooseRoute = false },
        )
    }
    if (choosePlaybackType && appConfigReady != null) {
        TvChoiceDialogForPlayback(
            selected = appConfigReady.preferences.videoPlaybackType,
            onSelect = { type ->
                choosePlaybackType = false
                onAppConfigEvent(AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.VideoPlayback(type)))
            },
            onDismiss = { choosePlaybackType = false },
        )
    }
    if (confirmTrashOff) {
        TvTrashDisableDialog(
            onConfirm = {
                confirmTrashOff = false
                onSettingsEvent(
                    AccountSettingsEvent.ChangeRequested(AccountSettingsChange(AccountSettingsKey.Trash, enabled = false)),
                )
            },
            onDismiss = { confirmTrashOff = false },
        )
    }
    if (showDiagnostics) {
        TvDiagnosticsDialog(
            diagnostics = tvAppDiagnostics(appConfigState.playbackPreference()),
            onDismiss = { showDiagnostics = false },
        )
    }
}

@Composable
private fun TvAccountHeader(
    account: TvAccount,
    onSignOut: () -> Unit,
    signOutModifier: Modifier,
) {
    val context = LocalContext.current
    val storage = account.storage
    val available = Formatter.formatShortFileSize(context, storage.availableBytes.coerceAtLeast(0L))
    val size = Formatter.formatShortFileSize(context, storage.sizeBytes.coerceAtLeast(0L))
    val usedFraction = if (storage.sizeBytes <= 0L) 0f else {
        (storage.usedBytes.toDouble() / storage.sizeBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
    val usedPercent = (usedFraction * PERCENT).toInt()
    val storageDescription = stringResource(R.string.tv_account_storage_description, available, size, usedPercent)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvAccountAvatar(account.avatarUrl)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
                .semantics(mergeDescendants = true) { contentDescription = "${account.username}. $storageDescription" },
        ) {
            Text(
                text = account.username,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.tv_account_storage, available, size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .width(STORAGE_BAR_WIDTH)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag(TV_ACCOUNT_STORAGE_TAG),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(usedFraction)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        TvButton(onClick = onSignOut, modifier = signOutModifier) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_sign_out),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.tv_account_sign_out))
        }
    }
}

@Composable
private fun TvAccountAvatar(avatarUrl: String?) {
    val avatarModifier = Modifier
        .size(AVATAR_SIZE)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
    val fallback = painterResource(R.drawable.ic_ph_user_circle_fill)
    val resolved = avatarUrl?.takeIf(String::isSupportedAvatarUrl)
    if (resolved == null) {
        Box(modifier = avatarModifier, contentAlignment = Alignment.Center) {
            Icon(painter = fallback, contentDescription = null, modifier = Modifier.size(40.dp))
        }
    } else {
        AsyncImage(
            model = resolved,
            contentDescription = null,
            modifier = avatarModifier,
            contentScale = ContentScale.Crop,
            placeholder = fallback,
            error = fallback,
        )
    }
}

@Composable
private fun TvAccountSettingsSection(
    state: AccountSettingsState,
    onEvent: (AccountSettingsEvent) -> Boolean,
    owner: TvPaneFocusOwner,
    firstRowFocus: FocusRequester,
    signOutFocus: FocusRequester,
    dialogShowing: Boolean,
    onChooseRoute: () -> Unit,
) {
    when (val content = state.content) {
        is AccountSettingsContent.Loading -> TvAccountStatusText(stringResource(R.string.tv_account_settings_loading))
        is AccountSettingsContent.Failed -> TvAccountNotice(
            text = stringResource(R.string.tv_account_settings_error, stringResource(content.failure.tvMessage())),
            action = stringResource(R.string.tv_account_retry).takeUnless {
                content.failure is AccountSettingsFailure.AuthenticationRequired
            },
            onAction = { onEvent(AccountSettingsEvent.RetryLoad) },
            owner = owner,
        )
        is AccountSettingsContent.Ready -> {
            val controls = state.controlsEnabled()
            // Rows that arrive while the header's Sign out is the entry point become it, and
            // take focus if Sign out holds it; a row the user has since moved to, or a dialog
            // another row opened, keeps the entry point so focus returns there.
            val dialogUp = rememberUpdatedState(dialogShowing)
            LaunchedEffect(Unit) {
                val fromHeader = owner.owns(signOutFocus)
                owner.claim(firstRowFocus, from = signOutFocus)
                withFrameNanos {}
                if (fromHeader && owner.owns(firstRowFocus) && !dialogUp.value) firstRowFocus.requestFocus()
            }
            TvChoiceRow(
                title = stringResource(R.string.tv_account_tunnel_route),
                value = content.preferences.tunnelRoute.tvLabel(),
                icon = R.drawable.ic_ph_tree_structure,
                onClick = { if (controls) onChooseRoute() },
                owner = owner,
                focus = firstRowFocus,
            )
            TvSettingsFailureNotice(state, AccountSettingsKey.TunnelRoute, onEvent, owner, firstRowFocus)
            TvSettingsSwitchRow(
                title = stringResource(R.string.tv_account_resume_playback),
                icon = R.drawable.ic_ph_bookmark_simple,
                checked = content.preferences.resumePlayback,
                key = AccountSettingsKey.ResumePlayback,
                state = state,
                onEvent = onEvent,
                onToggle = {
                    if (controls) {
                        onEvent(
                            AccountSettingsEvent.ChangeRequested(
                                AccountSettingsChange(AccountSettingsKey.ResumePlayback, it),
                            ),
                        )
                    }
                },
                owner = owner,
            )
        }
    }
}

/** A switch on an account setting, with the failure of its own last write shown under it. */
@Composable
private fun TvSettingsSwitchRow(
    title: String,
    @DrawableRes icon: Int,
    checked: Boolean,
    key: AccountSettingsKey,
    state: AccountSettingsState,
    onEvent: (AccountSettingsEvent) -> Boolean,
    onToggle: (Boolean) -> Unit,
    owner: TvPaneFocusOwner,
) {
    val focus = remember { FocusRequester() }
    TvSwitchRow(title = title, icon = icon, checked = checked, onToggle = onToggle, owner = owner, focus = focus)
    TvSettingsFailureNotice(state, key, onEvent, owner, focus)
}

/** The failed write for [key], beside its row; Try again hands focus back to that row. */
@Composable
private fun TvSettingsFailureNotice(
    state: AccountSettingsState,
    key: AccountSettingsKey,
    onEvent: (AccountSettingsEvent) -> Boolean,
    owner: TvPaneFocusOwner,
    rowFocus: FocusRequester,
) {
    val failed = (state.mutation as? AccountSettingsMutation.Failed)?.takeIf { it.change.key == key } ?: return
    val message = when (failed.operation) {
        AccountSettingsMutation.Operation.Save -> R.string.tv_account_save_error
        AccountSettingsMutation.Operation.Refresh -> R.string.tv_account_refresh_error
    }
    TvAccountNotice(
        text = stringResource(message, stringResource(failed.failure.tvMessage())),
        action = stringResource(R.string.tv_account_retry).takeUnless {
            failed.failure is AccountSettingsFailure.AuthenticationRequired
        },
        onAction = { onEvent(AccountSettingsEvent.RetryChange) },
        owner = owner,
        returnTo = rowFocus,
    )
}

@Composable
private fun TvAppConfigSection(
    state: AndroidAppConfigState,
    onEvent: (AndroidAppConfigEvent) -> Boolean,
    owner: TvPaneFocusOwner,
    onChoosePlaybackType: () -> Unit,
) {
    when (val content = state.content) {
        is AndroidAppConfigContent.Loading -> TvAccountStatusText(stringResource(R.string.tv_account_playback_loading))
        is AndroidAppConfigContent.Failed -> TvAccountNotice(
            text = stringResource(R.string.tv_account_playback_error, stringResource(content.failure.tvMessage())),
            action = stringResource(R.string.tv_account_retry).takeUnless {
                content.failure is AndroidAppConfigFailure.AuthenticationRequired
            },
            onAction = { onEvent(AndroidAppConfigEvent.RetryLoad) },
            owner = owner,
        )
        is AndroidAppConfigContent.Ready -> {
            val controls = state.controlsEnabled()
            val typeFocus = remember { FocusRequester() }
            TvChoiceRow(
                title = stringResource(R.string.tv_account_video_playback_type),
                value = stringResource(content.preferences.videoPlaybackType.tvLabel()),
                icon = R.drawable.ic_ph_monitor_play,
                onClick = { if (controls) onChoosePlaybackType() },
                owner = owner,
                focus = typeFocus,
            )
            TvAppConfigFailureNotice(state, onEvent, owner, typeFocus) { it is AndroidAppConfigChange.VideoPlayback }
            val autoplayFocus = remember { FocusRequester() }
            TvSwitchRow(
                title = stringResource(R.string.tv_account_autoplay_next),
                icon = R.drawable.ic_ph_list_checks,
                checked = content.preferences.autoplayNextVideo,
                onToggle = {
                    if (controls) {
                        onEvent(AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.AutoplayNextVideo(it)))
                    }
                },
                owner = owner,
                focus = autoplayFocus,
            )
            TvAppConfigFailureNotice(state, onEvent, owner, autoplayFocus) { it is AndroidAppConfigChange.AutoplayNextVideo }
        }
    }
}

@Composable
private fun TvAppConfigFailureNotice(
    state: AndroidAppConfigState,
    onEvent: (AndroidAppConfigEvent) -> Boolean,
    owner: TvPaneFocusOwner,
    rowFocus: FocusRequester,
    owns: (AndroidAppConfigChange) -> Boolean,
) {
    val failed = (state.mutation as? AndroidAppConfigMutation.Failed)?.takeIf { owns(it.change) } ?: return
    val message = when (failed.operation) {
        AndroidAppConfigMutation.Operation.Save -> R.string.tv_account_save_error
        AndroidAppConfigMutation.Operation.Refresh -> R.string.tv_account_refresh_error
    }
    TvAccountNotice(
        text = stringResource(message, stringResource(failed.failure.tvMessage())),
        action = stringResource(R.string.tv_account_retry).takeUnless {
            failed.failure is AndroidAppConfigFailure.AuthenticationRequired
        },
        onAction = { onEvent(AndroidAppConfigEvent.RetryChange) },
        owner = owner,
        returnTo = rowFocus,
    )
}

@Composable
private fun TvDeviceSection(
    owner: TvPaneFocusOwner,
    onDiagnostics: () -> Unit,
) {
    TvInfoRow(
        title = stringResource(R.string.tv_account_app),
        value = stringResource(
            R.string.tv_account_app_value,
            BuildConfig.APPLICATION_ID,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        ),
        icon = R.drawable.ic_ph_android_logo,
    )
    TvInfoRow(
        title = stringResource(R.string.tv_account_device),
        value = stringResource(R.string.tv_account_device_value, Build.MANUFACTURER, Build.MODEL),
        icon = R.drawable.ic_ph_television,
    )
    TvInfoRow(
        title = stringResource(R.string.tv_account_os),
        value = stringResource(R.string.tv_account_os_value, Build.VERSION.RELEASE),
        icon = R.drawable.ic_ph_circles_four,
    )
    TvChoiceRow(
        title = stringResource(R.string.tv_account_diagnostics),
        value = null,
        icon = R.drawable.ic_ph_bug,
        onClick = onDiagnostics,
        owner = owner,
    )
}

/** A fact with nothing to do: laid out like a row, but not in the D-pad chain. */
@Composable
private fun TvInfoRow(
    title: String,
    value: String,
    @DrawableRes icon: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TvRowIcon(icon)
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TvChoiceDialogForPlayback(
    selected: VideoPlaybackType,
    onSelect: (VideoPlaybackType) -> Unit,
    onDismiss: () -> Unit,
) {
    TvChoiceDialog(
        title = stringResource(R.string.tv_account_video_playback_type),
        // MP4 above the default, as the oracle lists them.
        choices = listOf(VideoPlaybackType.Mp4, VideoPlaybackType.Hls).map { TvChoice(it, stringResource(it.tvLabel())) },
        selected = selected,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

@Composable
private fun TvSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun TvAccountStatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/** A message with an optional action, in its own focus group so Up from a row reaches it. */
@Composable
private fun TvAccountNotice(
    text: String,
    action: String?,
    onAction: () -> Unit,
    owner: TvPaneFocusOwner,
    /** Where focus goes when the notice leaves while its action holds focus; the pane's home otherwise. */
    returnTo: FocusRequester? = null,
) {
    val actionFocus = remember { FocusRequester() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TvButton(
                onClick = onAction,
                modifier = owner.section(actionFocus, fallback = returnTo?.let { { it } }).focusRequester(actionFocus),
            ) {
                Text(action)
            }
        }
    }
}

@Composable
private fun TvSwitchRow(
    title: String,
    @DrawableRes icon: Int,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    owner: TvPaneFocusOwner,
    focus: FocusRequester = remember { FocusRequester() },
) {
    ListItem(
        selected = false,
        onClick = { onToggle(!checked) },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { TvRowIcon(icon) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
        modifier = owner.section(focus)
            .focusRequester(focus)
            .fillMaxWidth()
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            },
    )
}

@Composable
private fun TvChoiceRow(
    title: String,
    value: String?,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    owner: TvPaneFocusOwner,
    focus: FocusRequester = remember { FocusRequester() },
) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (value == null) null else ({ Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) }),
        leadingContent = { TvRowIcon(icon) },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.ic_ph_caret_right),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
        modifier = owner.section(focus)
            .focusRequester(focus)
            .fillMaxWidth(),
    )
}

@Composable
private fun TvAccountRow(
    title: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    owner: TvPaneFocusOwner,
    modifier: Modifier = Modifier,
    value: String? = null,
) {
    val focus = remember { FocusRequester() }
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (value == null) null else ({ Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) }),
        leadingContent = { TvRowIcon(icon) },
        scale = ListItemDefaults.scale(focusedScale = FULL_WIDTH_FOCUSED_SCALE),
        modifier = modifier
            .then(owner.section(focus))
            .focusRequester(focus)
            .fillMaxWidth(),
    )
}

@Composable
private fun TvRowIcon(@DrawableRes icon: Int) {
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = PutioDesignTokens.yellowSolid,
        modifier = Modifier.size(ListItemDefaults.IconSize),
    )
}

/** Controls stay focusable while a write is in flight; they just do not start another. */
private fun AccountSettingsState.controlsEnabled(): Boolean =
    when (val current = mutation) {
        AccountSettingsMutation.Idle -> true
        is AccountSettingsMutation.Saving -> false
        is AccountSettingsMutation.Failed -> current.failure !is AccountSettingsFailure.AuthenticationRequired
    }

private fun AndroidAppConfigState.controlsEnabled(): Boolean =
    when (val current = mutation) {
        AndroidAppConfigMutation.Idle -> true
        is AndroidAppConfigMutation.Saving -> false
        is AndroidAppConfigMutation.Failed -> current.failure !is AndroidAppConfigFailure.AuthenticationRequired
    }

internal fun tvAppDiagnostics(playbackPreference: PlaybackPreference): AppDiagnostics =
    AppDiagnostics(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        releaseChannel = AppDiagnostics.ReleaseChannel.fromFlavor(BuildConfig.FLAVOR_channel),
        buildType = BuildConfig.BUILD_TYPE,
        runtime = AppDiagnostics.Runtime.AndroidTv,
        runtimeVersion = Build.VERSION.SDK_INT,
        deviceClass = AppDiagnostics.DeviceClass.Tv,
        // Media3 is the only player; the stream method is the one playback actually uses.
        player = when (playbackPreference) {
            PlaybackPreference.HLS -> "media3/hls"
            PlaybackPreference.MP4 -> "media3/mp4"
        },
    )

private val AVATAR_SIZE = 72.dp
private val STORAGE_BAR_WIDTH = 160.dp
private const val PERCENT = 100
