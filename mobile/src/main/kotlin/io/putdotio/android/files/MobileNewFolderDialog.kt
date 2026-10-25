package io.putdotio.android.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.transfers.neutralTextButtonColors
import kotlinx.coroutines.launch

internal const val MOBILE_NEW_FOLDER_FIELD_TAG = "mobile-new-folder-field"
internal const val MOBILE_NEW_FOLDER_CREATE_TAG = "mobile-new-folder-create"

/** The folder New folder was asked for; it survives rotation and belongs to one session. */
@Stable
internal class MobileNewFolderRequest(private val folderId: MutableState<Long?>) {
    fun ask(folder: FilesItemId) {
        folderId.value = folder.value
    }

    /**
     * New folder's action for the bars and the empty state; none without a way to create one or in
     * a shared folder, which takes no new items.
     */
    fun action(creator: FilesFolderCreator?, folder: FilesFolderState): (() -> Unit)? =
        // The root is always the viewer's; another folder is unknown until its first page says who owns it.
        if (creator == null || !(folder.folder.id == FilesFolder.Root.id || folder.summary?.acceptsNewItems == true)) {
            null
        } else {
            { ask(folder.folder.id) }
        }

    fun clear() {
        folderId.value = null
    }

    fun isFor(folder: FilesItemId): Boolean = folderId.value == folder.value
}

@Composable
internal fun rememberNewFolderRequest(session: Any): MobileNewFolderRequest {
    val folderId = rememberSaveable(session) { mutableStateOf<Long?>(null) }
    return remember(folderId) { MobileNewFolderRequest(folderId) }
}

/** Shows the dialog while the folder it was asked for is the one on screen. */
@Composable
internal fun MobileNewFolderHost(
    request: MobileNewFolderRequest,
    folder: FilesFolder,
    onScreen: Boolean,
    creator: FilesFolderCreator?,
    onCreated: (FilesItem) -> Unit,
    onAuthenticationRequired: suspend () -> Unit,
) {
    if (creator == null || !onScreen || !request.isFor(folder.id)) return
    MobileNewFolderDialog(
        parentId = folder.id,
        parentName = folder.name ?: stringResource(R.string.mobile_destination_files),
        creator = creator,
        onCreated = { folder ->
            request.clear()
            onCreated(folder)
        },
        onDismiss = request::clear,
        onAuthenticationRequired = onAuthenticationRequired,
    )
}

/**
 * New folder: one outlined field under the parent's name, then Cancel and a filled Create. put.io
 * refuses names it can't take (a slash, a duplicate) and the dialog shows its reason.
 */
@Composable
internal fun MobileNewFolderDialog(
    parentId: FilesItemId,
    parentName: String,
    creator: FilesFolderCreator,
    onCreated: (FilesItem) -> Unit,
    onDismiss: () -> Unit,
    onAuthenticationRequired: suspend () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<PutioFailure?>(null) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    val canCreate = name.isNotBlank() && !creating
    val create: () -> Unit = create@{
        // Read the state now, not as composed: two Done presses can land before a recomposition.
        if (creating || name.isBlank()) return@create
        creating = true
        failure = null
        scope.launch {
            when (val result = creator.createFolder(parentId, name)) {
                is PutioResult.Success -> onCreated(result.value)
                is PutioResult.Failure -> {
                    creating = false
                    failure = result.failure
                    if (result.failure is PutioFailure.AuthenticationRequired) onAuthenticationRequired()
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        // Full width less the 24dp margins, as the board sets it, rather than the platform's narrower default.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp),
        title = { Text(stringResource(R.string.mobile_files_new_folder)) },
        text = {
            // Scrolls in short windows and at large text, so the field stays reachable.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.mobile_files_new_folder_in, parentName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        failure = null
                    },
                    enabled = !creating,
                    singleLine = true,
                    isError = failure != null,
                    supportingText = failure?.let { refused ->
                        { Text(refused.mobileMessage(), color = PutioDesignTokens.redTextSecondary) }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .focusRequester(focus)
                        .testTag(MOBILE_NEW_FOLDER_FIELD_TAG),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { create() },
                enabled = canCreate,
                modifier = Modifier.testTag(MOBILE_NEW_FOLDER_CREATE_TAG),
            ) {
                Text(stringResource(R.string.mobile_files_new_folder_create))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !creating,
                colors = neutralTextButtonColors(),
            ) {
                Text(stringResource(R.string.mobile_action_cancel))
            }
        },
    )
}
