package io.putdotio.android.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.putdotio.android.R
import io.putdotio.android.trash.MOBILE_MANAGE_TRASH_TAG

/** Account's subpages: Downloads where this device keeps them, public links, and Trash. */
internal fun LazyListScope.manageItems(
    onManageDownloads: (() -> Unit)?,
    onManagePublicLinks: (() -> Unit)?,
    onManageTrash: () -> Unit,
) {
    if (onManageDownloads != null) {
        item(key = "manage-downloads") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.mobile_downloads_manage)) },
                supportingContent = { Text(stringResource(R.string.mobile_downloads_manage_description)) },
                leadingContent = {
                    Icon(painterResource(R.drawable.ic_ph_arrow_circle_down), contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onManageDownloads, role = Role.Button)
                    .testTag(MOBILE_MANAGE_DOWNLOADS_TAG),
            )
        }
    }
    if (onManagePublicLinks != null) {
        item(key = "manage-public-links") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.mobile_public_links_manage)) },
                supportingContent = { Text(stringResource(R.string.mobile_public_links_manage_description)) },
                leadingContent = { Icon(painterResource(R.drawable.ic_ph_link), contentDescription = null) },
                modifier = Modifier.clickable(onClick = onManagePublicLinks, role = Role.Button)
                    .testTag(MOBILE_MANAGE_PUBLIC_LINKS_TAG),
            )
        }
    }
    item(key = "manage-trash") {
        ListItem(
            headlineContent = { Text(stringResource(R.string.mobile_trash_manage)) },
            supportingContent = { Text(stringResource(R.string.mobile_trash_manage_description)) },
            leadingContent = {
                Icon(painterResource(R.drawable.ic_ph_trash), contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onManageTrash, role = Role.Button)
                .testTag(MOBILE_MANAGE_TRASH_TAG),
        )
    }
}
