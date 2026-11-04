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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import io.putdotio.android.MobileKeyCommand
import io.putdotio.android.MobileShortcutScope
import io.putdotio.android.MobileTooltip
import io.putdotio.android.R
import io.putdotio.android.mobileCtrlShortcut

internal const val MOBILE_FILES_NEW_FOLDER_ACTION_TAG = "mobile-files-new-folder-action"

/** What the Files bars act on: the current folder, its sort, and where search and New folder go. */
internal class MobileFilesBarActions(
    val folder: FilesFolderState,
    val onSelectSort: (FilesSort) -> Unit,
    val onOpenSearch: () -> Unit,
    /** Null where this folder can't take a new one. */
    val onNewFolder: (() -> Unit)?,
    val navigation: (@Composable () -> Unit)? = null,
    val accountSort: FilesSort? = null,
) {
    /** An empty folder has nothing to order, so it has no sort button. */
    val sortable: Boolean
        get() = folder.content !is FilesContent.Empty
}

/**
 * The Files root's search app bar: a pill reading "Search your files" that opens Search, with sort
 * and New folder inside it.
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
                if (actions.sortable) {
                    MobileFilesSortMenu(
                        actions.folder,
                        actions.onSelectSort,
                        accountSort = actions.accountSort,
                        colors = muted,
                        besideNewFolder = actions.onNewFolder != null,
                    )
                }
                MobileFilesNewFolderAction(actions.onNewFolder, colors = muted)
            }
        }
    }
}

/**
 * A folder's small top app bar: back, the folder name, search in this folder, sort and New folder.
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
            val backLabel = stringResource(R.string.mobile_action_back)
            MobileTooltip(backLabel) {
                IconButton(onClick = onBack, enabled = backEnabled) {
                    Icon(painter = painterResource(R.drawable.ic_ph_arrow_left), contentDescription = backLabel)
                }
            }
        },
        actions = {
            actions.navigation?.invoke()
            MobileTooltip(searchLabel, mobileCtrlShortcut(MobileShortcutScope.Files, MobileKeyCommand.Search)) {
                IconButton(onClick = actions.onOpenSearch, modifier = Modifier.testTag(MOBILE_FILES_SEARCH_TAG)) {
                    Icon(painter = painterResource(R.drawable.ic_ph_magnifying_glass), contentDescription = searchLabel)
                }
            }
            if (actions.sortable) {
                MobileFilesSortMenu(
                    actions.folder,
                    actions.onSelectSort,
                    accountSort = actions.accountSort,
                    besideNewFolder = actions.onNewFolder != null,
                )
            }
            MobileFilesNewFolderAction(actions.onNewFolder)
        },
        colors = TopAppBarDefaults.topAppBarColors(actionIconContentColor = MaterialTheme.colorScheme.onSurface),
    )
}

@Composable
private fun MobileFilesNewFolderAction(
    onNewFolder: (() -> Unit)?,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
) {
    if (onNewFolder == null) return
    val label = stringResource(R.string.mobile_files_new_folder)
    MobileTooltip(label) {
        IconButton(
            onClick = onNewFolder,
            colors = colors,
            modifier = Modifier.testTag(MOBILE_FILES_NEW_FOLDER_ACTION_TAG),
        ) {
            Icon(painter = painterResource(R.drawable.ic_ph_folder_plus), contentDescription = label)
        }
    }
}

private val SEARCH_BAR_HEIGHT = 56.dp
private val SEARCH_GLYPH_SIZE = 22.dp
