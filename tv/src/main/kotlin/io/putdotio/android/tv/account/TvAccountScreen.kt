package io.putdotio.android.tv.account

import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.putdotio.android.BuildConfig
import io.putdotio.android.PutioFailure
import io.putdotio.android.R
import io.putdotio.android.account.PrivacyLinks
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.isSupportedAvatarUrl
import io.putdotio.android.playback.playbackPreference
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
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.AppDiagnostics
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.settings.putioFailure
import io.putdotio.android.telemetry.AnalyticsEvent
import io.putdotio.android.telemetry.ProductAnalytics
import io.putdotio.android.tv.TvConfirmDialog
import io.putdotio.android.tv.TvQrCode
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.tvLabel
import io.putdotio.android.tv.rememberTvPaneHasFocus
import io.putdotio.sdk.files.PlaybackPreference
import io.putdotio.android.account.R as AccountR

internal const val TV_ACCOUNT_STORAGE_TAG = "tv-account-storage"

internal enum class TvAccountGroup(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Library(R.string.tv_account_group_library, R.drawable.ic_ph_trash),
    Playback(R.string.tv_account_group_playback, R.drawable.ic_ph_play_circle),
    Subtitles(R.string.tv_account_group_subtitles, R.drawable.ic_ph_subtitles),
    Privacy(R.string.tv_account_group_privacy, R.drawable.ic_ph_shield_check),
    About(R.string.tv_account_group_about, R.drawable.ic_ph_info),
    ;

    val focusKey: String get() = "group:$name"
}

internal enum class TvAccountSheet {
    Proxy,
    VideoPlayback,
    DefaultSort,
    PrivacyPolicy,
    ServicesWeUse,
    TrashOff,
    SignOut,
}

private object RowKey {
    const val SIGN_OUT = "sign-out"
    const val MANAGE_TRASH = "manage-trash"
    const val TRASH = "trash"
    const val DEFAULT_SORT = "default-sort"
    const val HISTORY = "history"
    const val RESUME = "resume"
    const val AUTOPLAY = "autoplay"
    const val VIDEO_PLAYBACK = "video-playback"
    const val PROXY = "proxy"
    const val SHOW_SUBTITLES = "show-subtitles"
    const val PICK_SUBTITLES = "pick-subtitles"
    const val DIAGNOSTICS = "diagnostics"
    const val ANALYTICS = "analytics"
    const val PRIVACY_POLICY = "privacy-policy"
    const val SERVICES = "services"
    const val VERSION = "version"
    const val CHANNEL = "channel"
    const val DEVICE = "device"
    const val ANDROID = "android"
    const val PLAYER = "player"
    const val RETRY_SETTINGS = "retry-settings"
    const val RETRY_CONFIG = "retry-config"
    const val RETRY_CHANGE = "retry-change"
    const val RETRY_CONFIG_CHANGE = "retry-config-change"
}

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
    trashSizeBytes: Long? = null,
    trashPane: (@Composable (paneFocus: FocusRequester) -> Unit)? = null,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>> = {
        AccountSettingsRepositoryResult.Failure(
            AccountSettingsFailure.Putio(
                PutioFailure.Unexpected(IllegalStateException("Tunnel routes are unavailable")),
            ),
        )
    },
    sessionKey: Any? = null,
) = key(sessionKey) {
    var showingTrash by rememberSaveable { mutableStateOf(false) }
    val group = rememberSaveable { mutableStateOf(TvAccountGroup.Library) }
    val lastFocus = rememberSaveable { mutableStateOf<String?>(null) }
    if (trashPane != null && showingTrash) {
        LaunchedEffect(Unit) { ProductAnalytics.capture(AnalyticsEvent.ScreenViewed(AnalyticsEvent.Screen.Trash)) }
        BackHandler { showingTrash = false }
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
            group = group,
            lastFocus = lastFocus,
            loadTunnelRoutes = loadTunnelRoutes,
        )
    }
}

private class TvAccountFocus {
    private val requesters = mutableMapOf<String, FocusRequester>()
    private val present = mutableSetOf<String>()
    private val fallbacks = mutableMapOf<String, String>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun attach(key: String, fallback: String?) {
        present += key
        if (fallback != null) fallbacks[key] = fallback
    }

    fun detach(key: String) {
        present -= key
    }

    fun resolve(key: String?, home: String): FocusRequester {
        var current = key
        while (current != null && current !in present) current = fallbacks[current]
        return requester(current ?: home)
    }
}

private class TvAccountContext(
    val focus: TvAccountFocus,
    val lastFocus: MutableState<String?>,
    val refocus: MutableIntState,
    val hasFocus: () -> Boolean,
)

@Composable
private fun Modifier.accountFocus(context: TvAccountContext, key: String, fallback: String? = null): Modifier {
    DisposableEffect(key) {
        context.focus.attach(key, fallback)
        onDispose {
            context.focus.detach(key)
            if (context.lastFocus.value == key && context.hasFocus()) context.refocus.intValue += 1
        }
    }
    return focusRequester(context.focus.requester(key))
        .onFocusChanged { if (it.isFocused) context.lastFocus.value = key }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
@Suppress("LongParameterList")
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
    group: MutableState<TvAccountGroup>,
    lastFocus: MutableState<String?>,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
) {
    var sheet by rememberSaveable { mutableStateOf<TvAccountSheet?>(null) }
    val focus = remember { TvAccountFocus() }
    val paneHasFocus = rememberTvPaneHasFocus()
    val refocus = remember { mutableIntStateOf(0) }
    val context = remember { TvAccountContext(focus, lastFocus, refocus) { paneHasFocus.value } }
    val sheetOpen = rememberUpdatedState(sheet != null)
    val entry = { focus.resolve(lastFocus.value, group.value.focusKey) }
    val rightPane = remember { FocusRequester() }
    val open = { next: TvAccountSheet, from: String ->
        lastFocus.value = from
        sheet = next
    }

    val wasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(sheet != null) {
        val closing = wasOpen.value && sheet == null
        wasOpen.value = sheet != null
        if (!closing || !paneHasFocus.value) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value) entry().requestFocus()
    }
    LaunchedEffect(refocus.intValue) {
        if (refocus.intValue == 0) return@LaunchedEffect
        withFrameNanos {}
        if (paneHasFocus.value && !sheetOpen.value) entry().requestFocus()
    }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        if (paneHasFocus.value && !sheetOpen.value) entry().requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = PANE_TOP)
            .focusRequester(paneFocus)
            .onFocusChanged { if (it.hasFocus) paneHasFocus.value = true }
            .focusProperties {
                enter = { entry() }
                exit = {
                    paneHasFocus.value = false
                    FocusRequester.Default
                }
            }
            .focusGroup(),
    ) {
        TvAccountGroups(
            account = account,
            trashSizeBytes = trashSizeBytes,
            group = group,
            context = context,
            rightPane = rightPane,
            onSignOut = { open(TvAccountSheet.SignOut, RowKey.SIGN_OUT) },
        )
        TvAccountGroupRows(
            group = group,
            focus = focus,
            rightPane = rightPane,
            rows = TvAccountRowsScope(
                context = context,
                settingsState = settingsState,
                appConfigState = appConfigState,
                onSettingsEvent = onSettingsEvent,
                onAppConfigEvent = onAppConfigEvent,
                open = open,
            ),
            trashSizeBytes = trashSizeBytes,
            onManageTrash = onManageTrash,
            playbackPreference = appConfigState.playbackPreference(),
        )
    }

    TvAccountSheets(
        sheet = sheet,
        settingsState = settingsState,
        appConfigState = appConfigState,
        onSettingsEvent = onSettingsEvent,
        onAppConfigEvent = onAppConfigEvent,
        onSignOut = onSignOut,
        loadTunnelRoutes = loadTunnelRoutes,
        returnFocus = { key -> focus.requester(key) },
        onClose = { sheet = null },
    )
}

@Composable
private fun TvAccountGroups(
    account: TvAccount,
    trashSizeBytes: Long?,
    group: MutableState<TvAccountGroup>,
    context: TvAccountContext,
    rightPane: FocusRequester,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier
            .offset(x = -GROUP_INSET)
            .width(LEFT_WIDTH)
            .focusProperties { right = rightPane },
    ) {
        TvAccountIdentity(account)
        TvStorageCard(account)
        Spacer(Modifier.height(12.dp))
        val androidContext = LocalContext.current
        val libraryValue = trashSizeBytes?.let {
            val size = Formatter.formatShortFileSize(androidContext, it.coerceAtLeast(0L))
            tvMonoArguments(R.string.tv_account_library_value, size)
        }
        TvAccountGroup.entries.forEach { item ->
            TvAccountGroupRow(
                title = stringResource(item.label),
                icon = item.icon,
                selected = group.value == item,
                onClick = { rightPane.requestFocus() },
                value = when (item) {
                    TvAccountGroup.Library -> libraryValue
                    TvAccountGroup.About -> monoText(BuildConfig.VERSION_NAME)
                    else -> null
                },
                modifier = Modifier
                    .accountFocus(context, item.focusKey)
                    .onFocusChanged { if (it.isFocused) group.value = item },
            )
        }
        Spacer(Modifier.height(8.dp))
        TvAccountGroupRow(
            title = stringResource(R.string.tv_account_sign_out),
            icon = R.drawable.ic_ph_sign_out,
            selected = false,
            onClick = onSignOut,
            danger = true,
            caret = false,
            modifier = Modifier.accountFocus(context, RowKey.SIGN_OUT, group.value.focusKey),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvAccountGroupRows(
    group: MutableState<TvAccountGroup>,
    focus: TvAccountFocus,
    rightPane: FocusRequester,
    rows: TvAccountRowsScope,
    trashSizeBytes: Long?,
    onManageTrash: (() -> Unit)?,
    playbackPreference: PlaybackPreference,
) {
    Column(
        modifier = Modifier
            .padding(start = RIGHT_START)
            .fillMaxWidth()
            .focusRequester(rightPane)
            .focusProperties {
                exit = { direction ->
                    if (direction == FocusDirection.Left) {
                        focus.requester(group.value.focusKey)
                    } else {
                        FocusRequester.Default
                    }
                }
            }
            .focusGroup()
            .verticalScroll(rememberScrollState()),
    ) {
        TvGroupLabel(stringResource(group.value.label))
        when (group.value) {
            TvAccountGroup.Library -> rows.Library(trashSizeBytes, onManageTrash)
            TvAccountGroup.Playback -> rows.Playback()
            TvAccountGroup.Subtitles -> rows.Subtitles()
            TvAccountGroup.Privacy -> rows.Privacy(rows.open)
            TvAccountGroup.About -> rows.About(playbackPreference)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
@Suppress("LongParameterList", "CyclomaticComplexMethod")
private fun TvAccountSheets(
    sheet: TvAccountSheet?,
    settingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    onSettingsEvent: (AccountSettingsEvent) -> Boolean,
    onAppConfigEvent: (AndroidAppConfigEvent) -> Boolean,
    onSignOut: () -> Unit,
    loadTunnelRoutes: suspend () -> AccountSettingsRepositoryResult<List<TunnelRouteOption>>,
    returnFocus: (String) -> FocusRequester,
    onClose: () -> Unit,
) {
    val settings = (settingsState.content as? AccountSettingsContent.Ready)?.preferences
    val config = (appConfigState.content as? AndroidAppConfigContent.Ready)?.preferences
    val change = { change: AccountSettingsChange ->
        onClose()
        onSettingsEvent(AccountSettingsEvent.ChangeRequested(change))
    }
    when (sheet) {
        TvAccountSheet.Proxy -> if (settings != null) {
            TvTunnelRouteSheet(
                selected = settings.tunnelRoute,
                loadRoutes = loadTunnelRoutes,
                onSave = { change(AccountSettingsChange.Route(it)) },
                onDismiss = onClose,
                returnFocus = returnFocus(RowKey.PROXY),
            )
        }
        TvAccountSheet.VideoPlayback -> if (config != null) {
            TvVideoPlaybackSheet(
                selected = config.videoPlaybackType,
                onSave = {
                    onClose()
                    onAppConfigEvent(AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.VideoPlayback(it)))
                },
                onDismiss = onClose,
                returnFocus = returnFocus(RowKey.VIDEO_PLAYBACK),
            )
        }
        TvAccountSheet.DefaultSort -> if (settings != null) {
            TvDefaultSortSheet(
                selected = settings.defaultSort,
                onSave = { change(AccountSettingsChange.Sort(it)) },
                onDismiss = onClose,
                returnFocus = returnFocus(RowKey.DEFAULT_SORT),
            )
        }
        TvAccountSheet.PrivacyPolicy -> TvQrSheet(
            title = stringResource(AccountR.string.privacy_policy),
            url = PrivacyLinks.POLICY,
            onDismiss = onClose,
            returnFocus = returnFocus(RowKey.PRIVACY_POLICY),
        )
        TvAccountSheet.ServicesWeUse -> TvQrSheet(
            title = stringResource(AccountR.string.privacy_services_we_use),
            url = PrivacyLinks.SERVICES_WE_USE,
            onDismiss = onClose,
            returnFocus = returnFocus(RowKey.SERVICES),
        )
        TvAccountSheet.TrashOff -> TvConfirmDialog(
            title = stringResource(R.string.tv_account_trash_disable_title),
            message = stringResource(R.string.tv_account_trash_disable_message),
            confirmLabel = stringResource(R.string.tv_account_trash_disable_confirm),
            onConfirm = { change(AccountSettingsChange(AccountSettingsKey.Trash, enabled = false)) },
            onDismiss = onClose,
            destructive = true,
        )
        TvAccountSheet.SignOut -> TvConfirmDialog(
            title = stringResource(R.string.tv_account_sign_out_title),
            message = stringResource(R.string.tv_account_sign_out_message),
            confirmLabel = stringResource(R.string.tv_account_sign_out),
            onConfirm = {
                onClose()
                onSignOut()
            },
            onDismiss = onClose,
            destructive = true,
        )
        null -> Unit
    }
}

private class TvAccountRowsScope(
    val context: TvAccountContext,
    val settingsState: AccountSettingsState,
    val appConfigState: AndroidAppConfigState,
    val onSettingsEvent: (AccountSettingsEvent) -> Boolean,
    val onAppConfigEvent: (AndroidAppConfigEvent) -> Boolean,
    val open: (TvAccountSheet, String) -> Unit,
) {
    private val settings = (settingsState.content as? AccountSettingsContent.Ready)?.preferences
    private val config = (appConfigState.content as? AndroidAppConfigContent.Ready)?.preferences
    private val settingsControls = settingsState.controlsEnabled()
    private val configControls = appConfigState.controlsEnabled()

    private fun toggle(key: AccountSettingsKey, enabled: Boolean) {
        if (settingsControls) onSettingsEvent(AccountSettingsEvent.ChangeRequested(AccountSettingsChange(key, enabled)))
    }

    @Composable
    fun Library(trashSizeBytes: Long?, onManageTrash: (() -> Unit)?) {
        val context = LocalContext.current
        val rows = buildList<@Composable (TvSegment) -> Unit> {
            if (onManageTrash != null) {
                add { segment ->
                    TvSettingRow(
                        title = stringResource(R.string.tv_account_manage_trash),
                        icon = R.drawable.ic_ph_trash,
                        segment = segment,
                        onClick = onManageTrash,
                        modifier = Modifier.accountFocus(this@TvAccountRowsScope.context, RowKey.MANAGE_TRASH),
                    ) { focused ->
                        trashSizeBytes?.let {
                            val size = Formatter.formatShortFileSize(context, it.coerceAtLeast(0L))
                            TvSettingValue(monoText(size), focused)
                        }
                    }
                }
            }
            settings?.let { preferences ->
                add { segment ->
                    TvSwitchSettingRow(
                        title = stringResource(R.string.tv_account_trash_enabled),
                        icon = R.drawable.ic_ph_trash_simple,
                        checked = preferences.trashEnabled,
                        segment = segment,
                        onToggle = { enabled ->
                            when {
                                !settingsControls -> Unit
                                enabled -> toggle(AccountSettingsKey.Trash, true)
                                else -> open(TvAccountSheet.TrashOff, RowKey.TRASH)
                            }
                        },
                        modifier = Modifier.accountFocus(this@TvAccountRowsScope.context, RowKey.TRASH),
                    )
                }
                add { segment ->
                    TvSettingRow(
                        title = stringResource(R.string.tv_account_default_sort),
                        icon = R.drawable.ic_ph_sort_ascending,
                        segment = segment,
                        onClick = { if (settingsControls) open(TvAccountSheet.DefaultSort, RowKey.DEFAULT_SORT) },
                        modifier = Modifier.accountFocus(this@TvAccountRowsScope.context, RowKey.DEFAULT_SORT),
                    ) { focused ->
                        val sort = preferences.defaultSort?.tvLabel() ?: R.string.tv_account_default_sort_unset
                        TvSettingValue(AnnotatedString(stringResource(sort)), focused)
                    }
                }
                add { segment ->
                    TvSwitchSettingRow(
                        title = stringResource(R.string.tv_account_history_enabled),
                        icon = R.drawable.ic_ph_clock_counter_clockwise,
                        checked = preferences.historyEnabled,
                        segment = segment,
                        onToggle = { toggle(AccountSettingsKey.History, it) },
                        modifier = Modifier.accountFocus(this@TvAccountRowsScope.context, RowKey.HISTORY),
                    )
                }
            }
        }
        Segments(rows)
        SettingsStatus()
        ChangeFailure(LIBRARY_KEYS)
    }

    @Composable
    fun Playback() {
        val rows = buildList<@Composable (TvSegment) -> Unit> {
            settings?.let { preferences ->
                add { segment ->
                    TvSwitchSettingRow(
                        title = stringResource(R.string.tv_account_resume_playback),
                        icon = R.drawable.ic_ph_play_circle,
                        checked = preferences.resumePlayback,
                        segment = segment,
                        onToggle = { toggle(AccountSettingsKey.ResumePlayback, it) },
                        modifier = Modifier.accountFocus(context, RowKey.RESUME),
                    )
                }
            }
            config?.let { preferences ->
                add { segment ->
                    TvSwitchSettingRow(
                        title = stringResource(R.string.tv_account_autoplay_next),
                        icon = R.drawable.ic_ph_skip_forward,
                        checked = preferences.autoplayNextVideo,
                        segment = segment,
                        onToggle = {
                            if (configControls) {
                                onAppConfigEvent(
                                    AndroidAppConfigEvent.ChangeRequested(AndroidAppConfigChange.AutoplayNextVideo(it)),
                                )
                            }
                        },
                        modifier = Modifier.accountFocus(context, RowKey.AUTOPLAY),
                    )
                }
                add { segment ->
                    TvSettingRow(
                        title = stringResource(R.string.tv_account_video_playback_type),
                        icon = R.drawable.ic_ph_film_strip,
                        segment = segment,
                        onClick = { if (configControls) open(TvAccountSheet.VideoPlayback, RowKey.VIDEO_PLAYBACK) },
                        modifier = Modifier.accountFocus(context, RowKey.VIDEO_PLAYBACK),
                    ) { focused ->
                        val type = stringResource(preferences.videoPlaybackType.tvLabel())
                        TvSettingValue(AnnotatedString(type), focused)
                    }
                }
            }
            settings?.let { preferences ->
                add { segment ->
                    TvSettingRow(
                        title = stringResource(R.string.tv_account_proxy),
                        icon = R.drawable.ic_ph_globe_simple,
                        segment = segment,
                        onClick = { if (settingsControls) open(TvAccountSheet.Proxy, RowKey.PROXY) },
                        modifier = Modifier.accountFocus(context, RowKey.PROXY),
                    ) { focused ->
                        TvSettingValue(AnnotatedString(preferences.tunnelRoute.tvLabel()), focused)
                    }
                }
            }
        }
        Segments(rows)
        SettingsStatus()
        ConfigStatus()
        ChangeFailure(PLAYBACK_KEYS)
        ConfigChangeFailure()
    }

    @Composable
    fun Subtitles() {
        val rows = buildList<@Composable (TvSegment) -> Unit> {
            settings?.let { preferences ->
                add { segment ->
                    TvSwitchSettingRow(
                        title = stringResource(R.string.tv_account_show_subtitles),
                        icon = R.drawable.ic_ph_subtitles,
                        checked = preferences.showSubtitles,
                        segment = segment,
                        onToggle = { toggle(AccountSettingsKey.ShowSubtitles, it) },
                        modifier = Modifier.accountFocus(context, RowKey.SHOW_SUBTITLES),
                    )
                }
                if (preferences.showSubtitles) {
                    add { segment ->
                        TvSwitchSettingRow(
                            title = stringResource(R.string.tv_account_auto_select_subtitles),
                            icon = R.drawable.ic_ph_list_checks,
                            checked = preferences.autoSelectSubtitles,
                            segment = segment,
                            onToggle = { toggle(AccountSettingsKey.AutoSelectSubtitles, it) },
                            modifier = Modifier.accountFocus(context, RowKey.PICK_SUBTITLES),
                        )
                    }
                }
            }
        }
        Segments(rows)
        SettingsStatus()
        ChangeFailure(SUBTITLE_KEYS)
    }

    @Composable
    fun Privacy(open: (TvAccountSheet, String) -> Unit) {
        val rows = buildList<@Composable (TvSegment) -> Unit> {
            add { segment ->
                TvSettingRow(
                    title = stringResource(AccountR.string.privacy_strictly_necessary),
                    description = stringResource(AccountR.string.privacy_strictly_necessary_description),
                    icon = R.drawable.ic_ph_lock_simple,
                    segment = segment,
                    focusable = false,
                ) { focused ->
                    val state = stringResource(AccountR.string.privacy_strictly_necessary_state)
                    TvSettingValue(AnnotatedString(state), focused)
                }
            }
            settings?.let { preferences ->
                add { segment ->
                    TvSwitchSettingRow(
                        title = stringResource(AccountR.string.privacy_diagnostics),
                        description = stringResource(AccountR.string.privacy_diagnostics_description),
                        icon = R.drawable.ic_ph_bug,
                        checked = preferences.diagnosticsEnabled,
                        segment = segment,
                        onToggle = { toggle(AccountSettingsKey.Diagnostics, it) },
                        modifier = Modifier.accountFocus(context, RowKey.DIAGNOSTICS),
                    )
                }
                add { segment ->
                    TvSwitchSettingRow(
                        title = stringResource(AccountR.string.privacy_product_analytics),
                        description = stringResource(AccountR.string.privacy_product_analytics_description),
                        icon = R.drawable.ic_ph_chart_bar,
                        checked = preferences.productAnalyticsEnabled,
                        segment = segment,
                        onToggle = { toggle(AccountSettingsKey.ProductAnalytics, it) },
                        modifier = Modifier.accountFocus(context, RowKey.ANALYTICS),
                    )
                }
            }
        }
        Segments(rows)
        SettingsStatus()
        ChangeFailure(PRIVACY_KEYS)
        Text(
            text = stringResource(AccountR.string.privacy_controls_footer),
            style = TvAccountType.footnote,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val qr = AnnotatedString(stringResource(R.string.tv_account_qr_value))
        TvSegments {
            TvSettingRow(
                title = stringResource(AccountR.string.privacy_policy),
                icon = R.drawable.ic_ph_file_text,
                segment = TvSegment.First,
                onClick = { open(TvAccountSheet.PrivacyPolicy, RowKey.PRIVACY_POLICY) },
                modifier = Modifier.accountFocus(context, RowKey.PRIVACY_POLICY),
            ) { focused -> TvSettingValue(qr, focused) }
            TvSettingRow(
                title = stringResource(AccountR.string.privacy_services_we_use),
                icon = R.drawable.ic_ph_list_bullets,
                segment = TvSegment.Last,
                onClick = { open(TvAccountSheet.ServicesWeUse, RowKey.SERVICES) },
                modifier = Modifier.accountFocus(context, RowKey.SERVICES),
            ) { focused -> TvSettingValue(qr, focused) }
        }
    }

    @Composable
    fun About(playbackPreference: PlaybackPreference) {
        val diagnostics = tvAppDiagnostics(playbackPreference)
        val channel = when (diagnostics.releaseChannel) {
            AppDiagnostics.ReleaseChannel.Stable -> R.string.tv_account_about_channel_stable
            AppDiagnostics.ReleaseChannel.Internal -> R.string.tv_account_about_channel_internal
        }
        val version =
            stringResource(R.string.tv_account_about_version_value, diagnostics.appVersion, diagnostics.versionCode)
        val facts = listOf(
            Triple(RowKey.VERSION, R.drawable.ic_ph_info, R.string.tv_account_about_version) to monoText(version),
            Triple(RowKey.CHANNEL, R.drawable.ic_ph_broadcast, R.string.tv_account_about_channel) to
                AnnotatedString(stringResource(channel)),
            Triple(RowKey.DEVICE, R.drawable.ic_ph_television, R.string.tv_account_device) to
                AnnotatedString(stringResource(R.string.tv_account_device_value, Build.MANUFACTURER, Build.MODEL)),
            Triple(RowKey.ANDROID, R.drawable.ic_ph_android_logo, R.string.tv_account_about_android_tv) to
                monoText(Build.VERSION.RELEASE),
            Triple(RowKey.PLAYER, R.drawable.ic_ph_play, R.string.tv_account_about_player) to
                AnnotatedString(stringResource(R.string.tv_account_about_player_value)),
        )
        TvSegments {
            facts.forEachIndexed { index, (fact, value) ->
                val (rowKey, icon, title) = fact
                TvSettingRow(
                    title = stringResource(title),
                    icon = icon,
                    segment = segmentOf(index, facts.size),
                    modifier = Modifier.accountFocus(context, rowKey),
                ) { focused -> TvSettingValue(value, focused) }
            }
        }
        TvSupportQr(diagnostics.supportText())
    }

    @Composable
    private fun Segments(rows: List<@Composable (TvSegment) -> Unit>) {
        if (rows.isEmpty()) return
        TvSegments { rows.forEachIndexed { index, row -> row(segmentOf(index, rows.size)) } }
    }

    @Composable
    private fun SettingsStatus() {
        when (val content = settingsState.content) {
            is AccountSettingsContent.Loading -> TvAccountStatus(stringResource(R.string.tv_account_settings_loading))
            is AccountSettingsContent.Failed -> TvAccountNotice(
                text = stringResource(R.string.tv_account_settings_error, content.failure.tvMessageText()),
                action = stringResource(R.string.tv_account_retry).takeUnless {
                    content.failure.putioFailure is PutioFailure.AuthenticationRequired
                },
                onAction = { onSettingsEvent(AccountSettingsEvent.RetryLoad) },
                modifier = Modifier.accountFocus(context, RowKey.RETRY_SETTINGS),
            )
            is AccountSettingsContent.Ready -> Unit
        }
    }

    @Composable
    private fun ConfigStatus() {
        when (val content = appConfigState.content) {
            is AndroidAppConfigContent.Loading -> TvAccountStatus(stringResource(R.string.tv_account_playback_loading))
            is AndroidAppConfigContent.Failed -> TvAccountNotice(
                text = stringResource(R.string.tv_account_playback_error, content.failure.tvAppConfigMessageText()),
                action = stringResource(R.string.tv_account_retry).takeUnless {
                    content.failure is PutioFailure.AuthenticationRequired
                },
                onAction = { onAppConfigEvent(AndroidAppConfigEvent.RetryLoad) },
                modifier = Modifier.accountFocus(context, RowKey.RETRY_CONFIG),
            )
            is AndroidAppConfigContent.Ready -> Unit
        }
    }

    @Composable
    private fun ChangeFailure(keys: Map<AccountSettingsKey, String>) {
        val failed = (settingsState.mutation as? AccountSettingsMutation.Failed) ?: return
        val rowKey = keys[failed.change.key] ?: return
        val message = when (failed.operation) {
            AccountSettingsMutation.Operation.Save -> R.string.tv_account_save_error
            AccountSettingsMutation.Operation.Refresh -> R.string.tv_account_refresh_error
        }
        TvAccountNotice(
            text = stringResource(message, failed.failure.tvMessageText()),
            action = stringResource(R.string.tv_account_retry).takeUnless {
                failed.failure.putioFailure is PutioFailure.AuthenticationRequired
            },
            onAction = { onSettingsEvent(AccountSettingsEvent.RetryChange) },
            modifier = Modifier.accountFocus(context, RowKey.RETRY_CHANGE, fallback = rowKey),
        )
    }

    @Composable
    private fun ConfigChangeFailure() {
        val failed = (appConfigState.mutation as? AndroidAppConfigMutation.Failed) ?: return
        val rowKey = when (failed.change) {
            is AndroidAppConfigChange.VideoPlayback -> RowKey.VIDEO_PLAYBACK
            is AndroidAppConfigChange.AutoplayNextVideo -> RowKey.AUTOPLAY
            AndroidAppConfigChange.ProductAnalyticsPromptAnswered -> return
        }
        val message = when (failed.operation) {
            AndroidAppConfigMutation.Operation.Save -> R.string.tv_account_save_error
            AndroidAppConfigMutation.Operation.Refresh -> R.string.tv_account_refresh_error
        }
        TvAccountNotice(
            text = stringResource(message, failed.failure.tvAppConfigMessageText()),
            action = stringResource(R.string.tv_account_retry).takeUnless {
                failed.failure is PutioFailure.AuthenticationRequired
            },
            onAction = { onAppConfigEvent(AndroidAppConfigEvent.RetryChange) },
            modifier = Modifier.accountFocus(context, RowKey.RETRY_CONFIG_CHANGE, fallback = rowKey),
        )
    }
}

@Composable
private fun TvAccountIdentity(account: TvAccount) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvAccountAvatar(account)
        Column(Modifier.weight(1f)) {
            Text(
                account.username,
                style = TvAccountType.name,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                account.email,
                style = PutioTypeScale.tvMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TvAccountAvatar(account: TvAccount) {
    val avatar = Modifier
        .size(AVATAR_SIZE)
        .clip(CircleShape)
        .background(PutioDesignTokens.componentBgActive)
    val resolved = account.avatarUrl?.takeIf(String::isSupportedAvatarUrl)
    Box(avatar, contentAlignment = Alignment.Center) {
        Text(
            account.username.take(1).uppercase(),
            style = TvAccountType.initial,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun TvStorageCard(account: TvAccount) {
    val context = LocalContext.current
    val storage = account.storage
    val size = Formatter.formatShortFileSize(context, storage.sizeBytes.coerceAtLeast(0L))
    val label = if (storage.showOptimisticUsage) {
        tvMonoArguments(
            R.string.tv_account_storage_free,
            Formatter.formatShortFileSize(context, storage.availableBytes.coerceAtLeast(0L)),
            size,
        )
    } else {
        tvMonoArguments(
            R.string.tv_account_storage_used,
            Formatter.formatShortFileSize(context, storage.usedBytes.coerceAtLeast(0L)),
            size,
        )
    }
    val usedPercent = (storage.usedFraction * PERCENT).toInt()
    val description = stringResource(R.string.tv_account_storage_description, label.text, usedPercent)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(PutioRadius.large))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${account.username}. $description" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.tv_account_storage),
                style = TvAccountType.storageTitle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(label, style = PutioTypeScale.tvMeta, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(PutioRadius.progress))
                .background(PutioDesignTokens.componentBgActive)
                .testTag(TV_ACCOUNT_STORAGE_TAG),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(storage.usedFraction)
                    .height(4.dp)
                    .background(PutioDesignTokens.yellowSolid),
            )
        }
    }
}

@Composable
private fun TvSupportQr(supportText: String) {
    val light = MaterialTheme.colorScheme.inverseSurface
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvQrCode(
            text = supportText,
            description = stringResource(R.string.tv_account_about_support_description),
            dark = MaterialTheme.colorScheme.background,
            light = light,
            modifier = Modifier
                .size(SUPPORT_QR_SIZE)
                .clip(RoundedCornerShape(SUPPORT_QR_RADIUS))
                .background(light)
                .padding(SUPPORT_QR_PADDING),
        )
        Text(
            stringResource(R.string.tv_account_about_support),
            style = TvAccountType.footnote,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun monoText(text: String): AnnotatedString =
    AnnotatedString(text, spanStyles = listOf(AnnotatedString.Range(monoSpan(), 0, text.length)))

private fun monoSpan(): SpanStyle = PutioTypeScale.tvMono.toSpanStyle()

private fun AccountSettingsState.controlsEnabled(): Boolean =
    when (val current = mutation) {
        AccountSettingsMutation.Idle -> true
        is AccountSettingsMutation.Saving -> false
        is AccountSettingsMutation.Failed -> current.failure.putioFailure !is PutioFailure.AuthenticationRequired
    }

private fun AndroidAppConfigState.controlsEnabled(): Boolean =
    when (val current = mutation) {
        AndroidAppConfigMutation.Idle -> true
        is AndroidAppConfigMutation.Saving -> false
        is AndroidAppConfigMutation.Failed -> current.failure !is PutioFailure.AuthenticationRequired
    }

internal fun tvAppDiagnostics(playbackPreference: PlaybackPreference): AppDiagnostics =
    AppDiagnostics(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        releaseChannel = AppDiagnostics.ReleaseChannel.fromFlavor(BuildConfig.FLAVOR),
        buildType = BuildConfig.BUILD_TYPE,
        runtime = AppDiagnostics.Runtime.AndroidTv,
        runtimeVersion = Build.VERSION.SDK_INT,
        deviceClass = AppDiagnostics.DeviceClass.Tv,
        player = when (playbackPreference) {
            PlaybackPreference.HLS -> "media3/hls"
            PlaybackPreference.MP4 -> "media3/mp4"
        },
    )

private val LIBRARY_KEYS = mapOf(
    AccountSettingsKey.Trash to RowKey.TRASH,
    AccountSettingsKey.DefaultSort to RowKey.DEFAULT_SORT,
    AccountSettingsKey.History to RowKey.HISTORY,
)
private val PLAYBACK_KEYS = mapOf(
    AccountSettingsKey.ResumePlayback to RowKey.RESUME,
    AccountSettingsKey.TunnelRoute to RowKey.PROXY,
)
private val SUBTITLE_KEYS = mapOf(
    AccountSettingsKey.ShowSubtitles to RowKey.SHOW_SUBTITLES,
    AccountSettingsKey.AutoSelectSubtitles to RowKey.PICK_SUBTITLES,
)
private val PRIVACY_KEYS = mapOf(
    AccountSettingsKey.Diagnostics to RowKey.DIAGNOSTICS,
    AccountSettingsKey.ProductAnalytics to RowKey.ANALYTICS,
)

private val PANE_TOP = 4.dp
private val GROUP_INSET = 12.dp
private val LEFT_WIDTH = 372.dp
private val RIGHT_START = 432.dp
private val AVATAR_SIZE = 40.dp
private val SUPPORT_QR_SIZE = 80.dp
private val SUPPORT_QR_RADIUS = 10.dp
private val SUPPORT_QR_PADDING = 5.dp
private const val PERCENT = 100
