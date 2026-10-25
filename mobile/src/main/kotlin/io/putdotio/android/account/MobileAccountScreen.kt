package io.putdotio.android.account

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import io.putdotio.android.AccountStorage
import io.putdotio.android.BuildConfig
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.apiReason
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.putioMono
import io.putdotio.android.files.FilesSort
import io.putdotio.android.files.labelResource
import io.putdotio.android.isSupportedAvatarUrl
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsFailure
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigChange
import io.putdotio.android.settings.AndroidAppConfigContent
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigMutation
import io.putdotio.android.settings.AndroidAppConfigPreferences
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.VideoPlaybackType
import io.putdotio.android.settings.apiReason
import io.putdotio.android.settings.putioFailure
import io.putdotio.android.transfers.neutralTextButtonColors
import io.putdotio.android.trash.MOBILE_MANAGE_TRASH_TAG

internal const val MOBILE_ACCOUNT_LIST_TAG = "mobile-account-list"
internal const val MOBILE_MANAGE_PUBLIC_LINKS_TAG = "mobile-manage-public-links"
internal const val MOBILE_ACCOUNT_AVATAR_FALLBACK_TAG = "mobile-account-avatar-fallback"
internal const val MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG = "mobile-account-storage-progress"
internal const val MOBILE_ACCOUNT_SIGN_OUT_TAG = "mobile-account-sign-out"
internal const val MOBILE_TUNNEL_ROUTE_ROW_TAG = "mobile-tunnel-route-row"
internal const val MOBILE_VIDEO_PLAYBACK_ROW_TAG = "mobile-video-playback-row"
internal const val MOBILE_PRIVACY_POLICY_ROW_TAG = "mobile-privacy-policy-row"
internal const val MOBILE_PRIVACY_POLICY_URL = "https://put.io/privacy-policy"

/** What Account's Library rows show beside their titles, when the app already knows it. */
internal data class MobileAccountLibrary(
    /** The account's exclusive links, once they have loaded; null hides the count. */
    val exclusiveLinkCount: Int? = null,
    /** What Trash holds, once Trash has loaded; null hides the size. */
    val trashSizeBytes: Long? = null,
)

/**
 * The Account tab under its title: the identity line, the storage card, then the settings as segmented
 * groups in DESIGN.md's order, and Sign out alone behind its confirm.
 */
@Composable
internal fun MobileAccountScreen(
    account: MobileAccount,
    sessionId: MobileAuthSessionId,
    settingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    onSettingsEvent: (AccountSettingsEvent) -> Unit,
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
    /** Signs out; true also deletes this account's downloads from the device first. */
    onSignOut: (removeDownloads: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManageTrash: () -> Unit = {},
    onManagePublicLinks: (() -> Unit)? = null,
    onPrivacyControls: () -> Unit = {},
    onAppInfo: () -> Unit = {},
    library: MobileAccountLibrary = MobileAccountLibrary(),
    /** Bytes this account's downloads hold on the device; null when it holds none, which hides the option. */
    downloadsOnDeviceBytes: Long? = null,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
        AccountSettingsRepositoryResult.Failure(
            AccountSettingsFailure.Putio(
                PutioFailure.Unexpected(IllegalStateException("Tunnel routes are unavailable")),
            ),
        )
    },
) {
    var dialog by rememberSaveable(sessionId) { mutableStateOf(AccountDialog.None) }
    val uriHandler = LocalUriHandler.current
    val rows = AccountRows(
        settingsState = settingsState,
        appConfigState = appConfigState,
        onSettingsChange = { change ->
            if (change.disablesTrash) {
                dialog = AccountDialog.TrashOff
            } else {
                onSettingsEvent(AccountSettingsEvent.ChangeRequested(change))
            }
        },
        onRetrySettings = { onSettingsEvent(AccountSettingsEvent.RetryChange) },
        onAppConfigEvent = onAppConfigEvent,
        onOpen = { dialog = it },
    )
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(MOBILE_ACCOUNT_LIST_TAG),
    ) {
        item(key = IDENTITY_KEY) { MobileAccountIdentity(account) }
        item(key = STORAGE_KEY) { MobileAccountStorage(account.storage) }
        libraryGroup(rows, library, onManagePublicLinks, onManageTrash)
        filesItems(settingsState, rows, onRetryLoad = { onSettingsEvent(AccountSettingsEvent.RetryLoad) })
        playbackItems(settingsState, appConfigState, rows, onRetryLoad = {
            onAppConfigEvent(AndroidAppConfigEvent.RetryLoad)
        })
        privacyItems(settingsState, rows, onPrivacyControls)
        aboutItems(
            rows = rows,
            onAppInfo = onAppInfo,
            onPrivacyPolicy = { uriHandler.openSafely(MOBILE_PRIVACY_POLICY_URL) },
            onSignOut = { dialog = AccountDialog.SignOut },
        )
    }
    MobileAccountDialogs(
        dialog = dialog,
        settingsState = settingsState,
        appConfigState = appConfigState,
        onDismiss = { dialog = AccountDialog.None },
        onSettingsEvent = onSettingsEvent,
        onAppConfigEvent = onAppConfigEvent,
        onSignOut = onSignOut,
        downloadsOnDeviceBytes = downloadsOnDeviceBytes,
        loadTunnelRoutes = loadTunnelRoutes,
    )
}

private enum class AccountDialog { None, TrashOff, SignOut, DefaultSort, Proxy, VideoPlayback }

private fun LazyListScope.libraryGroup(
    rows: AccountRows,
    library: MobileAccountLibrary,
    onManagePublicLinks: (() -> Unit)?,
    onManageTrash: () -> Unit,
) {
    val links = onManagePublicLinks?.let { open ->
        SettingsRow { segment ->
            rows.Link(
                segment = segment,
                title = R.string.mobile_settings_exclusive_links,
                icon = R.drawable.ic_ph_link,
                value = library.exclusiveLinkCount?.let { settingsValue(it.toString(), mono = true) },
                onClick = open,
                tag = MOBILE_MANAGE_PUBLIC_LINKS_TAG,
            )
        }
    }
    val trash = SettingsRow { segment ->
        val context = LocalContext.current
        rows.Link(
            segment = segment,
            title = R.string.mobile_settings_trash_row,
            icon = R.drawable.ic_ph_trash,
            value = library.trashSizeBytes?.let {
                settingsValue(Formatter.formatShortFileSize(context, it.coerceAtLeast(0L)), mono = true)
            },
            onClick = onManageTrash,
            tag = MOBILE_MANAGE_TRASH_TAG,
        )
    }
    settingsGroup(LIBRARY_KEY, R.string.mobile_settings_section_library, listOfNotNull(links, trash))
}

/** Files, or the account settings' loading or failure row while they are not ready. */
private fun LazyListScope.filesItems(settingsState: AccountSettingsState, rows: AccountRows, onRetryLoad: () -> Unit) {
    when (val content = settingsState.content) {
        is AccountSettingsContent.Loading -> item(key = SETTINGS_LOADING_KEY) { MobileAccountSettingsLoading() }
        is AccountSettingsContent.Failed -> item(key = SETTINGS_ERROR_KEY) {
            MobileAccountSettingsError(failure = content.failure, onRetry = onRetryLoad)
        }
        is AccountSettingsContent.Ready -> settingsGroup(
            key = FILES_KEY,
            label = R.string.mobile_settings_section_files,
            rows = listOf(
                SettingsRow { rows.DefaultSort(it, content.preferences) },
                SettingsRow {
                    rows.AccountSwitch(it, R.string.mobile_settings_trash, R.drawable.ic_ph_trash_simple,
                        AccountSettingsKey.Trash, content.preferences.trashEnabled)
                },
            ),
        )
    }
}

/**
 * Playback mixes account-wide settings (resume, proxy) with this app's `/config` (autoplay, video
 * playback); each row shows once its source has loaded, and `/config`'s loading or failure follows.
 */
private fun LazyListScope.playbackItems(
    settingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    rows: AccountRows,
    onRetryLoad: () -> Unit,
) {
    val settings = (settingsState.content as? AccountSettingsContent.Ready)?.preferences
    val config = (appConfigState.content as? AndroidAppConfigContent.Ready)?.preferences
    settingsGroup(
        key = PLAYBACK_KEY,
        label = R.string.mobile_settings_section_playback,
        rows = listOfNotNull(
            settings?.let { preferences ->
                SettingsRow {
                    rows.AccountSwitch(it, R.string.mobile_settings_resume_playback, R.drawable.ic_ph_play_circle,
                        AccountSettingsKey.ResumePlayback, preferences.resumePlayback)
                }
            },
            config?.let { preferences -> SettingsRow { rows.AutoplayNext(it, preferences) } },
            config?.let { preferences -> SettingsRow { rows.VideoPlayback(it, preferences) } },
            settings?.let { preferences -> SettingsRow { rows.Proxy(it, preferences) } },
        ),
    )
    when (val content = appConfigState.content) {
        is AndroidAppConfigContent.Loading -> item(key = APP_CONFIG_LOADING_KEY) {
            MobileAccountLoadingRow(R.string.mobile_settings_playback_loading)
        }
        is AndroidAppConfigContent.Failed -> item(key = APP_CONFIG_ERROR_KEY) {
            MobileAppConfigLoadError(content.failure, onRetry = onRetryLoad)
        }
        is AndroidAppConfigContent.Ready -> Unit
    }
}

private fun LazyListScope.privacyItems(
    settingsState: AccountSettingsState,
    rows: AccountRows,
    onPrivacyControls: () -> Unit,
) {
    val preferences = (settingsState.content as? AccountSettingsContent.Ready)?.preferences ?: return
    // Picking subtitles means nothing while they are hidden.
    val pickSubtitles = SettingsRow {
        rows.AccountSwitch(it, R.string.mobile_settings_auto_select_subtitles, R.drawable.ic_ph_list_checks,
            AccountSettingsKey.AutoSelectSubtitles, preferences.autoSelectSubtitles)
    }.takeIf { preferences.showSubtitles }
    settingsGroup(
        key = SUBTITLES_KEY,
        label = R.string.mobile_settings_section_subtitles,
        rows = listOfNotNull(
            SettingsRow {
                rows.AccountSwitch(it, R.string.mobile_settings_show_subtitles, R.drawable.ic_ph_subtitles,
                    AccountSettingsKey.ShowSubtitles, preferences.showSubtitles)
            },
            pickSubtitles,
        ),
    )
    settingsGroup(
        key = PRIVACY_KEY,
        label = R.string.mobile_settings_section_privacy,
        rows = listOf(
            SettingsRow {
                rows.AccountSwitch(it, R.string.mobile_settings_history, R.drawable.ic_ph_clock_counter_clockwise,
                    AccountSettingsKey.History, preferences.historyEnabled)
            },
            SettingsRow { rows.PrivacyControls(it, preferences, onPrivacyControls) },
        ),
    )
}

/** About, then Sign out alone, in red. */
private fun LazyListScope.aboutItems(
    rows: AccountRows,
    onAppInfo: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onSignOut: () -> Unit,
) {
    settingsGroup(
        key = ABOUT_KEY,
        label = R.string.mobile_settings_section_about,
        rows = listOf(
            SettingsRow {
                rows.Link(it, R.string.mobile_settings_about, R.drawable.ic_ph_info,
                    settingsValue(BuildConfig.VERSION_NAME, mono = true), onAppInfo, MOBILE_ABOUT_ROW_TAG)
            },
            SettingsRow {
                rows.Link(it, R.string.mobile_settings_privacy_policy, R.drawable.ic_ph_file_text, null,
                    onPrivacyPolicy, MOBILE_PRIVACY_POLICY_ROW_TAG)
            },
        ),
    )
    item(key = SIGN_OUT_KEY) {
        Column(Modifier.padding(top = 16.dp, bottom = 24.dp)) {
            MobileSettingsGroup {
                MobileSettingsSegment(
                    segment = SettingsSegment.Only,
                    interaction = Modifier
                        .clickable(role = Role.Button, onClick = onSignOut)
                        .testTag(MOBILE_ACCOUNT_SIGN_OUT_TAG),
                ) {
                    MobileSettingsLine(
                        title = stringResource(R.string.mobile_account_sign_out),
                        icon = R.drawable.ic_ph_sign_out,
                        trailing = SettingsTrailing.None,
                        titleColor = PutioDesignTokens.redTextSecondary,
                        iconColor = PutioDesignTokens.redTextSecondary,
                    )
                }
            }
        }
    }
}

/** The open dialog; a choice opens only while its setting has loaded and takes input. */
@Composable
private fun MobileAccountDialogs(
    dialog: AccountDialog,
    settingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    onDismiss: () -> Unit,
    onSettingsEvent: (AccountSettingsEvent) -> Unit,
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
    onSignOut: (removeDownloads: Boolean) -> Unit,
    downloadsOnDeviceBytes: Long?,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
) {
    val settings = (settingsState.content as? AccountSettingsContent.Ready)?.preferences
        ?.takeIf { settingsState.accountControlsEnabled() }
    val config = (appConfigState.content as? AndroidAppConfigContent.Ready)?.preferences
        ?.takeIf { appConfigState.appConfigControlsEnabled() }
    val change = { settingsChange: AccountSettingsChange ->
        onDismiss()
        onSettingsEvent(AccountSettingsEvent.ChangeRequested(settingsChange))
    }
    when (dialog) {
        AccountDialog.None -> Unit
        AccountDialog.TrashOff -> MobileTrashOffDialog(
            onDismiss = onDismiss,
            onConfirm = { change(AccountSettingsChange(AccountSettingsKey.Trash, enabled = false)) },
        )
        AccountDialog.SignOut -> MobileSignOutDialog(
            downloadsOnDeviceBytes = downloadsOnDeviceBytes,
            onDismiss = onDismiss,
            onSignOut = { removeDownloads ->
                onDismiss()
                onSignOut(removeDownloads)
            },
        )
        AccountDialog.DefaultSort -> if (settings != null) {
            MobileDefaultSortDialog(settings.defaultSort, { change(AccountSettingsChange.Sort(it)) }, onDismiss)
        }
        AccountDialog.Proxy -> if (settings != null) {
            MobileTunnelRouteDialog(
                selected = settings.tunnelRoute,
                loadRoutes = loadTunnelRoutes,
                onSave = { change(AccountSettingsChange.Route(it)) },
                onDismiss = onDismiss,
            )
        }
        AccountDialog.VideoPlayback -> if (config != null) {
            MobilePlaybackTypeDialog(
                selected = config.videoPlaybackType,
                onSave = {
                    onDismiss()
                    onAppConfigEvent(AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.VideoPlayback(it)))
                },
                onDismiss = onDismiss,
            )
        }
    }
}

/** One row of a group; it draws itself into the [SettingsSegment] its place gives it. */
private class SettingsRow(val content: @Composable (SettingsSegment) -> Unit)

private fun LazyListScope.settingsGroup(key: String, @StringRes label: Int, rows: List<SettingsRow>) {
    if (rows.isEmpty()) return
    item(key = key) {
        Column {
            MobileSettingsLabel(stringResource(label))
            MobileSettingsGroup {
                rows.forEachIndexed { index, row -> row.content(settingsSegment(index, rows.size)) }
            }
        }
    }
}

/** The rows' shared wiring: which mutation each one shows, and whether controls take input. */
private class AccountRows(
    private val settingsState: AccountSettingsState,
    private val appConfigState: AndroidAppConfigState,
    private val onSettingsChange: (AccountSettingsChange) -> Unit,
    private val onRetrySettings: () -> Unit,
    private val onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
    private val onOpen: (AccountDialog) -> Unit,
) {
    private val settingsEnabled = settingsState.accountControlsEnabled()
    private val configEnabled = appConfigState.appConfigControlsEnabled()

    @Composable
    fun Link(
        segment: SettingsSegment,
        @StringRes title: Int,
        icon: Int,
        value: SettingsTrailing.Value?,
        onClick: () -> Unit,
        tag: String,
    ) {
        MobileSettingsSegment(
            segment = segment,
            interaction = Modifier.clickable(role = Role.Button, onClick = onClick).testTag(tag),
        ) {
            MobileSettingsLine(stringResource(title), icon, value ?: SettingsTrailing.None)
        }
    }

    @Composable
    fun AccountSwitch(
        segment: SettingsSegment,
        @StringRes title: Int,
        icon: Int,
        key: AccountSettingsKey,
        checked: Boolean,
    ) {
        val mutation = settingsState.mutation
        val saving = (mutation as? AccountSettingsMutation.Saving)?.change?.key == key
        val failure = (mutation as? AccountSettingsMutation.Failed)?.takeIf { it.change.key == key }
        SwitchSegment(
            segment = segment,
            title = title,
            icon = icon,
            checked = checked,
            enabled = settingsEnabled,
            saving = saving,
            onToggle = { onSettingsChange(AccountSettingsChange(key, it)) },
            failure = failure?.let { { MobileAccountMutationError(it.failure, it.operation, onRetrySettings) } },
        )
    }

    @Composable
    fun AutoplayNext(segment: SettingsSegment, preferences: AndroidAppConfigPreferences) {
        val mutation = appConfigState.mutation
        val change = { candidate: AndroidAppConfigChange? -> candidate is AndroidAppConfigChange.AutoplayNextVideo }
        SwitchSegment(
            segment = segment,
            title = R.string.mobile_settings_autoplay_next,
            icon = R.drawable.ic_ph_skip_forward,
            checked = preferences.autoplayNextVideo,
            enabled = configEnabled,
            saving = change((mutation as? AndroidAppConfigMutation.Saving)?.change),
            onToggle = {
                onAppConfigEvent(AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.AutoplayNextVideo(it)))
            },
            failure = (mutation as? AndroidAppConfigMutation.Failed)?.takeIf { change(it.change) }?.let { failed ->
                { MobileAppConfigMutationError(failed.failure, failed.operation) { onRetryConfig() } }
            },
        )
    }

    @Composable
    fun VideoPlayback(segment: SettingsSegment, preferences: AndroidAppConfigPreferences) {
        val mutation = appConfigState.mutation
        val change = { candidate: AndroidAppConfigChange? -> candidate is AndroidAppConfigChange.VideoPlayback }
        ChoiceSegment(
            segment = segment,
            title = R.string.mobile_settings_video_playback_type,
            icon = R.drawable.ic_ph_film_strip,
            value = stringResource(preferences.videoPlaybackType.labelResource()),
            enabled = configEnabled,
            saving = change((mutation as? AndroidAppConfigMutation.Saving)?.change),
            onChoose = { onOpen(AccountDialog.VideoPlayback) },
            tag = MOBILE_VIDEO_PLAYBACK_ROW_TAG,
            failure = (mutation as? AndroidAppConfigMutation.Failed)?.takeIf { change(it.change) }?.let { failed ->
                { MobileAppConfigMutationError(failed.failure, failed.operation) { onRetryConfig() } }
            },
        )
    }

    @Composable
    fun DefaultSort(segment: SettingsSegment, preferences: AccountSettingsPreferences) {
        AccountChoice(
            segment = segment,
            title = R.string.mobile_settings_default_sort,
            icon = R.drawable.ic_ph_sort_ascending,
            value = preferences.defaultSort.displayName(),
            onChoose = { onOpen(AccountDialog.DefaultSort) },
            tag = MOBILE_DEFAULT_SORT_ROW_TAG,
        ) { it is AccountSettingsChange.Sort }
    }

    @Composable
    fun Proxy(segment: SettingsSegment, preferences: AccountSettingsPreferences) {
        AccountChoice(
            segment = segment,
            title = R.string.mobile_settings_proxy,
            icon = R.drawable.ic_ph_globe_simple,
            value = preferences.tunnelRoute.displayName(),
            onChoose = { onOpen(AccountDialog.Proxy) },
            tag = MOBILE_TUNNEL_ROUTE_ROW_TAG,
        ) { it is AccountSettingsChange.Route }
    }

    @Composable
    fun PrivacyControls(segment: SettingsSegment, preferences: AccountSettingsPreferences, onOpen: () -> Unit) {
        val diagnostics = stringResource(
            if (preferences.diagnosticsEnabled) R.string.mobile_settings_diagnostics_on
            else R.string.mobile_settings_diagnostics_off,
        )
        val analytics = stringResource(
            if (preferences.productAnalyticsEnabled) R.string.mobile_settings_analytics_on
            else R.string.mobile_settings_analytics_off,
        )
        Link(
            segment = segment,
            title = R.string.mobile_settings_privacy_controls,
            icon = R.drawable.ic_ph_shield_check,
            value = settingsValue(
                stringResource(R.string.mobile_settings_privacy_controls_state, diagnostics, analytics),
            ),
            onClick = onOpen,
            tag = MOBILE_PRIVACY_CONTROLS_ROW_TAG,
        )
    }

    @Composable
    private fun AccountChoice(
        segment: SettingsSegment,
        @StringRes title: Int,
        icon: Int,
        value: String,
        onChoose: () -> Unit,
        tag: String,
        matches: (AccountSettingsChange) -> Boolean,
    ) {
        val mutation = settingsState.mutation
        val failure = (mutation as? AccountSettingsMutation.Failed)?.takeIf { matches(it.change) }
        ChoiceSegment(
            segment = segment,
            title = title,
            icon = icon,
            value = value,
            enabled = settingsEnabled,
            saving = (mutation as? AccountSettingsMutation.Saving)?.change?.let(matches) == true,
            onChoose = onChoose,
            tag = tag,
            failure = failure?.let { { MobileAccountMutationError(it.failure, it.operation, onRetrySettings) } },
        )
    }

    private fun onRetryConfig() = onAppConfigEvent(AndroidAppConfigEvent.RetryChange)
}

@Composable
private fun SwitchSegment(
    segment: SettingsSegment,
    @StringRes title: Int,
    icon: Int,
    checked: Boolean,
    enabled: Boolean,
    saving: Boolean,
    onToggle: (Boolean) -> Unit,
    failure: (@Composable () -> Unit)?,
) {
    MobileSettingsSegment(
        segment = segment,
        interaction = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onToggle,
        ),
        below = failure?.let { { it() } },
    ) {
        MobileSettingsLine(
            title = stringResource(title),
            icon = icon,
            trailing = SettingsTrailing.Switch(checked, enabled),
            saving = saving,
        )
    }
}

@Composable
private fun ChoiceSegment(
    segment: SettingsSegment,
    @StringRes title: Int,
    icon: Int,
    value: String,
    enabled: Boolean,
    saving: Boolean,
    onChoose: () -> Unit,
    tag: String,
    failure: (@Composable () -> Unit)?,
) {
    MobileSettingsSegment(
        segment = segment,
        interaction = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onChoose).testTag(tag),
        below = failure?.let { { it() } },
    ) {
        MobileSettingsLine(
            title = stringResource(title),
            icon = icon,
            trailing = settingsValue(value),
            saving = saving,
        )
    }
}

@Composable
private fun MobileAccountIdentity(account: MobileAccount) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MobileAccountAvatar(account.avatarUrl, account.username)
        Column {
            Text(account.username, style = PutioTypeScale.bodyLg.copy(fontWeight = FontWeight.Medium))
            Text(
                account.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MobileAccountAvatar(avatarUrl: String?, username: String) {
    val resolvedUrl = avatarUrl?.takeIf(String::isSupportedAvatarUrl)
    val avatarModifier = Modifier
        .size(40.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    if (resolvedUrl == null) {
        MobileAccountAvatarFallback(username, avatarModifier)
        return
    }
    SubcomposeAsyncImage(
        model = resolvedUrl,
        contentDescription = null,
        modifier = avatarModifier,
        contentScale = ContentScale.Crop,
        loading = { MobileAccountAvatarFallback(username, Modifier.fillMaxSize()) },
        error = { MobileAccountAvatarFallback(username, Modifier.fillMaxSize()) },
        success = { SubcomposeAsyncImageContent() },
    )
}

/** The username's first letter on the avatar's disc, as web and iOS draw an account without a picture. */
@Composable
private fun MobileAccountAvatarFallback(username: String, modifier: Modifier) {
    Box(modifier = modifier.testTag(MOBILE_ACCOUNT_AVATAR_FALLBACK_TAG), contentAlignment = Alignment.Center) {
        Text(
            text = username.firstOrNull()?.uppercase().orEmpty(),
            style = PutioTypeScale.bodyLg.copy(fontWeight = FontWeight.Medium),
        )
    }
}

/** Storage is full when nothing is left to use; transfers then wait. */
internal val AccountStorage.isFull: Boolean
    get() = sizeBytes > 0L && (availableBytes <= 0L || usedBytes >= sizeBytes)

@Composable
private fun MobileAccountStorage(storage: AccountStorage) {
    val context = LocalContext.current
    val mono = MaterialTheme.typography.bodySmall.putioMono().toSpanStyle()
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))
    val full = storage.isFull
    val (template, shown) = when {
        full -> R.string.mobile_account_storage_full_value to storage.usedBytes
        storage.showOptimisticUsage -> R.string.mobile_account_storage_free to storage.availableBytes
        else -> R.string.mobile_account_storage_used to storage.usedBytes
    }
    val value = monoArguments(stringResource(template), mono, size(shown), size(storage.sizeBytes))
    MobileSettingsGroup(Modifier.semantics(mergeDescendants = true) {}) {
        MobileSettingsSegment(SettingsSegment.Only) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.mobile_account_storage),
                        style = PutioTypeScale.bodyMd,
                        modifier = Modifier.weight(1f).padding(end = 16.dp),
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { storage.usedFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .testTag(MOBILE_ACCOUNT_STORAGE_PROGRESS_TAG),
                    color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                if (full) {
                    Text(
                        stringResource(R.string.mobile_account_storage_full),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

private const val IDENTITY_KEY = "account-identity"
private const val STORAGE_KEY = "account-storage"
private const val LIBRARY_KEY = "account-library"
private const val SETTINGS_LOADING_KEY = "account-settings-loading"
private const val SETTINGS_ERROR_KEY = "account-settings-error"
private const val FILES_KEY = "account-files"
private const val PLAYBACK_KEY = "account-playback"
private const val APP_CONFIG_LOADING_KEY = "app-config-loading"
private const val APP_CONFIG_ERROR_KEY = "app-config-error"
private const val SUBTITLES_KEY = "account-subtitles"
private const val PRIVACY_KEY = "account-privacy"
private const val ABOUT_KEY = "account-about"
private const val SIGN_OUT_KEY = "account-sign-out"
