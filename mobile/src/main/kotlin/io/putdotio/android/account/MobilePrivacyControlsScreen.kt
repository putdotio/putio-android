package io.putdotio.android.account

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.R
import io.putdotio.android.design.PutioSwitch
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsMutation
import io.putdotio.android.settings.AccountSettingsState

internal const val MOBILE_PRIVACY_CONTROLS_ROUTE = "account/privacy-controls"
internal const val MOBILE_PRIVACY_CONTROLS_LIST_TAG = "mobile-privacy-controls-list"
internal const val MOBILE_PRIVACY_CONTROLS_ROW_TAG = "mobile-privacy-controls-row"
internal const val MOBILE_STRICTLY_NECESSARY_TAG = "mobile-strictly-necessary"
internal const val MOBILE_SERVICES_WE_USE_URL = "https://put.io/services-we-use"

// Purpose-based opt-outs per the frontend analytics contract. Strictly necessary
// processing is disclosed, never toggled; both account keys are cross-client.
@Composable
internal fun MobilePrivacyControlsScreen(
    settingsState: AccountSettingsState,
    onSettingsEvent: (AccountSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val openUrl: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (ignored: IllegalArgumentException) {
            // No app can open the link; there is nothing else to open it with.
        }
    }
    val onRetryChange = { onSettingsEvent(AccountSettingsEvent.RetryChange) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(MOBILE_PRIVACY_CONTROLS_LIST_TAG),
    ) {
        when (val content = settingsState.content) {
            is AccountSettingsContent.Loading ->
                item(key = PRIVACY_LOADING_KEY) { MobileAccountSettingsLoading() }

            is AccountSettingsContent.Failed ->
                item(key = PRIVACY_ERROR_KEY) {
                    MobileAccountSettingsError(
                        failure = content.failure,
                        onRetry = { onSettingsEvent(AccountSettingsEvent.RetryLoad) },
                    )
                }

            is AccountSettingsContent.Ready -> {
                val onChange = { change: AccountSettingsChange ->
                    onSettingsEvent(AccountSettingsEvent.ChangeRequested(change))
                }
                item(key = CONTROLS_KEY) {
                    MobileSettingsGroup {
                        MobileSettingsSegment(
                            segment = SettingsSegment.First,
                            // One focus stop: TalkBack reads the title, Always on, and the description together.
                            modifier = Modifier
                                .semantics(mergeDescendants = true) {}
                                .testTag(MOBILE_STRICTLY_NECESSARY_TAG),
                        ) {
                            PrivacyLine(
                                title = R.string.mobile_settings_strictly_necessary,
                                description = R.string.mobile_settings_strictly_necessary_description,
                                icon = R.drawable.ic_ph_shield_check,
                            ) {
                                Text(
                                    stringResource(R.string.mobile_settings_strictly_necessary_state),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        PrivacyToggle(
                            segment = SettingsSegment.Middle,
                            title = R.string.mobile_settings_diagnostics,
                            description = R.string.mobile_settings_diagnostics_description,
                            icon = R.drawable.ic_ph_bug,
                            checked = content.preferences.diagnosticsEnabled,
                            key = AccountSettingsKey.Diagnostics,
                            mutation = settingsState.mutation,
                            enabled = settingsState.accountControlsEnabled(),
                            onRetry = onRetryChange,
                            onChange = onChange,
                        )
                        PrivacyToggle(
                            segment = SettingsSegment.Last,
                            title = R.string.mobile_settings_product_analytics,
                            description = R.string.mobile_settings_product_analytics_description,
                            icon = R.drawable.ic_ph_chart_line,
                            checked = content.preferences.productAnalyticsEnabled,
                            key = AccountSettingsKey.ProductAnalytics,
                            mutation = settingsState.mutation,
                            enabled = settingsState.accountControlsEnabled(),
                            onRetry = onRetryChange,
                            onChange = onChange,
                        )
                    }
                }
                item(key = PRIVACY_CONTROLS_FOOTER_KEY) {
                    Text(
                        text = stringResource(R.string.mobile_settings_privacy_controls_footer),
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item(key = PRIVACY_LINKS_KEY) {
            MobileSettingsGroup {
                PrivacyLink(
                    segment = SettingsSegment.First,
                    title = R.string.mobile_settings_privacy_policy,
                    icon = R.drawable.ic_ph_file_text,
                    onClick = { openUrl(MOBILE_PRIVACY_POLICY_URL) },
                )
                PrivacyLink(
                    segment = SettingsSegment.Last,
                    title = R.string.mobile_settings_services_we_use,
                    icon = R.drawable.ic_ph_list_bullets,
                    onClick = { openUrl(MOBILE_SERVICES_WE_USE_URL) },
                )
            }
        }
    }
}

/** A two-line privacy row: the glyph and the trailing control sit on the title's line. */
@Composable
private fun PrivacyLine(
    @StringRes title: Int,
    @StringRes description: Int,
    @DrawableRes icon: Int,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = PutioTypeScale.bodyMd, color = MaterialTheme.colorScheme.onSurface)
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        trailing()
    }
}

@Composable
private fun PrivacyToggle(
    segment: SettingsSegment,
    @StringRes title: Int,
    @StringRes description: Int,
    @DrawableRes icon: Int,
    checked: Boolean,
    key: AccountSettingsKey,
    mutation: AccountSettingsMutation,
    enabled: Boolean,
    onRetry: () -> Unit,
    onChange: (AccountSettingsChange) -> Unit,
) {
    val saving = (mutation as? AccountSettingsMutation.Saving)?.change?.key == key
    val failure = (mutation as? AccountSettingsMutation.Failed)?.takeIf { it.change.key == key }
    MobileSettingsSegment(
        segment = segment,
        // The description is part of the switch's node, so TalkBack reads it with the control.
        interaction = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { onChange(AccountSettingsChange(key, it)) },
        ),
        below = failure?.let { { MobileAccountMutationError(it.failure, it.operation, onRetry) } },
    ) {
        PrivacyLine(title, description, icon) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                PutioSwitch(checked = checked, enabled = enabled)
            }
        }
    }
}

@Composable
private fun PrivacyLink(
    segment: SettingsSegment,
    @StringRes title: Int,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    MobileSettingsSegment(
        segment = segment,
        interaction = Modifier.clickable(onClick = onClick, role = Role.Button),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(
                stringResource(title),
                style = PutioTypeScale.bodyMd,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                painterResource(R.drawable.ic_ph_arrow_square_out),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private const val PRIVACY_LOADING_KEY = "privacy-controls-loading"
private const val PRIVACY_ERROR_KEY = "privacy-controls-error"
private const val CONTROLS_KEY = "privacy-controls-group"
private const val PRIVACY_CONTROLS_FOOTER_KEY = "privacy-controls-footer"
private const val PRIVACY_LINKS_KEY = "privacy-controls-links"
