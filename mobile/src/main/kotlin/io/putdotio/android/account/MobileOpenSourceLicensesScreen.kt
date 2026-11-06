package io.putdotio.android.account

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
import androidx.compose.foundation.lazy.items
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
import io.putdotio.android.BuildConfig
import io.putdotio.android.OpenSourceNotice
import io.putdotio.android.R
import io.putdotio.android.appSourceNotice
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.playback.playbackNotices
import io.putdotio.android.telemetry.telemetryNotices

internal const val MOBILE_OPEN_SOURCE_LICENSES_ROUTE = "account/open-source-licenses"
internal const val MOBILE_OPEN_SOURCE_LICENSES_ROW_TAG = "mobile-open-source-licenses-row"
internal const val MOBILE_OPEN_SOURCE_LICENSES_LIST_TAG = "mobile-open-source-licenses-list"

@Composable
internal fun MobileOpenSourceLicensesScreen(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(MOBILE_OPEN_SOURCE_LICENSES_LIST_TAG),
    ) {
        item(key = INTRO_KEY) {
            Text(
                text = stringResource(R.string.mobile_settings_open_source_licenses_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 4.dp, bottom = 8.dp),
            )
        }
        items(OPEN_SOURCE_NOTICES, key = { it.name }) { notice ->
            NoticeGroup(notice, onOpen = { uriHandler.openSafely(it) })
        }
    }
}

@Composable
private fun NoticeGroup(notice: OpenSourceNotice, onOpen: (String) -> Unit) {
    MobileSettingsGroup(Modifier.padding(top = 12.dp)) {
        MobileSettingsSegment(
            segment = SettingsSegment.First,
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.mobile_settings_open_source_name, notice.name, notice.version),
                    style = PutioTypeScale.bodyMd,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    notice.license,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    notice.copyright,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        NoticeLink(SettingsSegment.Middle, R.string.mobile_settings_open_source_source) { onOpen(notice.sourceUrl) }
        NoticeLink(SettingsSegment.Last, R.string.mobile_settings_open_source_license) { onOpen(notice.licenseUrl) }
    }
}

@Composable
private fun NoticeLink(segment: SettingsSegment, @StringRes title: Int, onClick: () -> Unit) {
    MobileSettingsSegment(
        segment = segment,
        interaction = Modifier.clickable(onClick = onClick, role = Role.Button),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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

private const val INTRO_KEY = "open-source-licenses-intro"
private val OPEN_SOURCE_NOTICES = listOf(appSourceNotice(BuildConfig.VERSION_NAME)) + playbackNotices + telemetryNotices
