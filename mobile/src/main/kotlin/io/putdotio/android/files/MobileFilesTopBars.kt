package io.putdotio.android.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.putdotio.android.MOBILE_FILES_SEARCH_TAG
import io.putdotio.android.R
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale

internal const val MOBILE_FILES_OVERFLOW_TAG = "mobile-files-overflow"

/** What the Files bars act on: the current folder, its sort, and where search and New folder go. */
internal class MobileFilesBarActions(
    val folder: FilesFolderState,
    val onSelectSort: (FilesSort) -> Unit,
    val onOpenSearch: () -> Unit,
    /** Null where this folder can't take a new one. */
    val onNewFolder: (() -> Unit)?,
    val navigation: (@Composable () -> Unit)? = null,
) {
    /** An empty folder has nothing to order, so it has no sort button. */
    val sortable: Boolean
        get() = folder.content !is FilesContent.Empty
}

/**
 * The Files root's search app bar: a pill reading "Search your files" that opens Search, with sort
 * and overflow inside it.
 */
@Composable
internal fun MobileFilesSearchBar(actions: MobileFilesBarActions, modifier: Modifier = Modifier) {
    val muted = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
        Surface(
            onClick = actions.onOpenSearch,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                .fillMaxWidth()
                .height(SEARCH_BAR_HEIGHT)
                .testTag(MOBILE_FILES_SEARCH_TAG)
                .semantics { role = Role.Button },
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_magnifying_glass),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(SEARCH_GLYPH_SIZE),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.mobile_files_search_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                actions.navigation?.invoke()
                if (actions.sortable) MobileFilesSortMenu(actions.folder, actions.onSelectSort, colors = muted)
                MobileFilesOverflow(actions.onNewFolder, colors = muted)
            }
        }
    }
}

/**
 * A folder's small top app bar: back, the folder name, search in this folder, sort and overflow.
 * Back is disabled while a move or this folder's delete settles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileFolderTopBar(
    title: String,
    actions: MobileFilesBarActions,
    onBack: () -> Unit,
    backEnabled: Boolean,
) {
    val searchLabel = stringResource(R.string.mobile_files_search_in, title)
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack, enabled = backEnabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_ph_arrow_left),
                    contentDescription = stringResource(R.string.mobile_action_back),
                )
            }
        },
        actions = {
            actions.navigation?.invoke()
            IconButton(onClick = actions.onOpenSearch, modifier = Modifier.testTag(MOBILE_FILES_SEARCH_TAG)) {
                Icon(painter = painterResource(R.drawable.ic_ph_magnifying_glass), contentDescription = searchLabel)
            }
            if (actions.sortable) MobileFilesSortMenu(actions.folder, actions.onSelectSort)
            MobileFilesOverflow(actions.onNewFolder)
        },
        colors = TopAppBarDefaults.topAppBarColors(actionIconContentColor = MaterialTheme.colorScheme.onSurface),
    )
}

/** The bar's overflow; it holds New folder, and shows only when it has something to offer. */
@Composable
private fun MobileFilesOverflow(
    onNewFolder: (() -> Unit)?,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
) {
    if (onNewFolder == null) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            colors = colors,
            modifier = Modifier.testTag(MOBILE_FILES_OVERFLOW_TAG),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ph_dots_three_vertical),
                contentDescription = stringResource(R.string.mobile_files_more),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(PutioRadius.large),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mobile_files_new_folder), style = PutioTypeScale.bodyMd) },
                onClick = {
                    expanded = false
                    onNewFolder()
                },
                leadingIcon = {
                    Icon(painter = painterResource(R.drawable.ic_ph_folder_plus), contentDescription = null)
                },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

private val SEARCH_BAR_HEIGHT = 56.dp
private val SEARCH_GLYPH_SIZE = 22.dp
