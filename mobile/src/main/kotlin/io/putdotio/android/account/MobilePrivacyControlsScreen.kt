package io.putdotio.android.account

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.putdotio.android.R
import io.putdotio.android.design.R as DesignR
import io.putdotio.android.settings.AccountSettingsChange
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsKey
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.AccountSettingsState

internal const val MOBILE_PRIVACY_CONTROLS_ROUTE = "account/privacy-controls"
internal const val MOBILE_PRIVACY_CONTROLS_LIST_TAG = "mobile-privacy-controls-list"
internal const val MOBILE_PRIVACY_CONTROLS_ROW_TAG = "mobile-privacy-controls-row"
internal const val MOBILE_STRICTLY_NECESSARY_TAG = "mobile-strictly-necessary"
internal const val MOBILE_PRIVACY_POLICY_URL = "https://put.io/privacy-policy"
internal const val MOBILE_SERVICES_WE_USE_URL = "https://put.io/services-we-use"

/** Account's one Privacy controls row: the two account-wide toggles' current state, opening the page. */
internal fun LazyListScope.privacyControlsRow(
    preferences: AccountSettingsPreferences,
    onOpen: () -> Unit,
) {
    item(key = PRIVACY_CONTROLS_ROW_KEY) {
        val diagnostics = stringResource(
            if (preferences.diagnosticsEnabled) R.string.mobile_settings_diagnostics_on
            else R.string.mobile_settings_diagnostics_off,
        )
        val productAnalytics = stringResource(
            if (preferences.productAnalyticsEnabled) R.string.mobile_settings_product_analytics_on
            else R.string.mobile_settings_product_analytics_off,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.mobile_settings_privacy_controls)) },
            supportingContent = {
                Text(stringResource(R.string.mobile_settings_privacy_controls_state, diagnostics, productAnalytics))
            },
            leadingContent = {
                Icon(painterResource(R.drawable.ic_ph_shield_check), contentDescription = null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen, role = Role.Button)
                .testTag(MOBILE_PRIVACY_CONTROLS_ROW_TAG),
        )
    }
}

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
                item(key = STRICTLY_NECESSARY_KEY) {
                    MobileStrictlyNecessaryRow()
                }
                item(key = AccountSettingsKey.Diagnostics) {
                    MobileAccountSettingRow(
                        title = R.string.mobile_settings_diagnostics,
                        description = R.string.mobile_settings_diagnostics_description,
                        icon = R.drawable.ic_ph_bug,
                        checked = content.preferences.diagnosticsEnabled,
                        key = AccountSettingsKey.Diagnostics,
                        mutation = settingsState.mutation,
                        onRetry = onRetryChange,
                        onChange = onChange,
                    )
                }
                item(key = AccountSettingsKey.ProductAnalytics) {
                    MobileAccountSettingRow(
                        title = R.string.mobile_settings_product_analytics,
                        description = R.string.mobile_settings_product_analytics_description,
                        icon = R.drawable.ic_ph_chart_line,
                        checked = content.preferences.productAnalyticsEnabled,
                        key = AccountSettingsKey.ProductAnalytics,
                        mutation = settingsState.mutation,
                        onRetry = onRetryChange,
                        onChange = onChange,
                    )
                }
                item(key = PRIVACY_CONTROLS_FOOTER_KEY) {
                    Text(
                        text = stringResource(R.string.mobile_settings_privacy_controls_footer),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item(key = PRIVACY_LINKS_DIVIDER_KEY) {
            HorizontalDivider()
        }
        item(key = PRIVACY_POLICY_KEY) {
            MobilePrivacyLinkRow(
                title = R.string.mobile_settings_privacy_policy,
                icon = DesignR.drawable.ic_ph_file_text_fill,
                onClick = { openUrl(MOBILE_PRIVACY_POLICY_URL) },
            )
        }
        item(key = SERVICES_WE_USE_KEY) {
            MobilePrivacyLinkRow(
                title = R.string.mobile_settings_services_we_use,
                icon = R.drawable.ic_ph_link,
                onClick = { openUrl(MOBILE_SERVICES_WE_USE_URL) },
            )
        }
    }
}

@Composable
private fun MobileStrictlyNecessaryRow() {
    ListItem(
        headlineContent = { Text(stringResource(R.string.mobile_settings_strictly_necessary)) },
        supportingContent = {
            MobileAccountValueDescription(
                value = stringResource(R.string.mobile_settings_strictly_necessary_state),
                description = stringResource(R.string.mobile_settings_strictly_necessary_description),
            )
        },
        leadingContent = {
            Icon(painter = painterResource(R.drawable.ic_ph_shield_check), contentDescription = null)
        },
        // One focus stop: TalkBack reads the title, Always on, and the description together.
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag(MOBILE_STRICTLY_NECESSARY_TAG),
    )
}

@Composable
private fun MobilePrivacyLinkRow(
    @StringRes title: Int,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button),
    )
}

private const val PRIVACY_CONTROLS_ROW_KEY = "account-settings-privacy-controls"
private const val PRIVACY_LOADING_KEY = "privacy-controls-loading"
private const val PRIVACY_ERROR_KEY = "privacy-controls-error"
private const val STRICTLY_NECESSARY_KEY = "privacy-controls-strictly-necessary"
private const val PRIVACY_CONTROLS_FOOTER_KEY = "privacy-controls-footer"
private const val PRIVACY_LINKS_DIVIDER_KEY = "privacy-controls-links-divider"
private const val PRIVACY_POLICY_KEY = "privacy-controls-privacy-policy"
private const val SERVICES_WE_USE_KEY = "privacy-controls-services-we-use"
