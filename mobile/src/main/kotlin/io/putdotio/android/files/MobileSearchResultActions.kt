package io.putdotio.android.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.putdotio.android.downloads.DownloadsState
import io.putdotio.android.sharing.MobilePublicLinkSheet
import io.putdotio.android.sharing.MobilePublicLinks

/** What a search result's sheet can reach: the session's actions, downloads and public links. */
internal class MobileSearchResultActions(
    val itemActions: MobileFilesItemActions,
    val downloads: DownloadsState,
    val onPlayItem: (FilesItem) -> Unit,
    val onDownloadItem: ((FilesItem) -> Unit)?,
    val onShareItem: ((FilesItem) -> Unit)?,
    val publicLinks: MobilePublicLinks?,
)

/**
 * A search result's actions sheet, opened by [content]'s overflow and long-press. The result isn't
 * in a loaded folder, so the sheet leaves out Rename, Move, Make a copy and the delete action, which
 * act on a folder's listing; opening the result reaches them in its folder.
 */
@Composable
internal fun MobileSearchResultActionsHost(
    results: List<FilesItem>,
    actions: MobileSearchResultActions,
    content: @Composable (onResultActions: (FilesItem) -> Unit) -> Unit,
) {
    var sheetItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var linkItemId by rememberSaveable { mutableStateOf<Long?>(null) }
    Box(Modifier.fillMaxSize()) {
        content { sheetItemId = it.id.value }
        MobileFilesNoticeHost(actions.itemActions.notices, Modifier.align(Alignment.BottomCenter))
    }
    results.firstOrNull { it.id.value == sheetItemId }?.let { item ->
        MobileFilesActions(
            item = item,
            folderId = item.parentId ?: FilesFolder.Root.id,
            operation = FilesFolderOperation.Idle,
            renameCompletion = null,
            onEvent = {},
            onDismiss = { sheetItemId = null },
            downloadStatus = actions.downloads.entry(item.id)?.status,
            onDownloadItem = actions.onDownloadItem,
            onShareItem = actions.onShareItem,
            onPublicLinkItem = actions.publicLinks?.let { { linked -> linkItemId = linked.id.value } },
            onPlayItem = actions.onPlayItem,
            itemActions = actions.itemActions,
            listingActions = false,
        )
    }
    val publicLinks = actions.publicLinks
    results.firstOrNull { it.id.value == linkItemId && it.acceptsOwnerActions }?.let { item ->
        if (publicLinks != null) {
            MobilePublicLinkSheet(
                item = item,
                publicLinks = publicLinks,
                onDismiss = { linkItemId = null },
                onCopied = actions.itemActions.onLinkCopied?.let { announce ->
                    {
                        linkItemId = null
                        announce()
                    }
                },
            )
        }
    }
}
