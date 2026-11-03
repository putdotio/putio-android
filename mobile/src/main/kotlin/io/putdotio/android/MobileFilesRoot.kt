package io.putdotio.android

import androidx.navigation.NavHostController
import io.putdotio.android.files.FilesBrowserEvent
import io.putdotio.android.files.FilesBrowserState
import io.putdotio.android.files.FilesSort
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsState

/** A tab the bar or rail chose; Files chosen while it shows goes back to its root instead. */
internal fun NavHostController.select(
    destination: MobileDestination,
    selected: MobileDestination,
    showFilesRoot: () -> Unit,
) {
    if (destination == MobileDestination.Files && selected == MobileDestination.Files) {
        showFilesRoot()
    } else {
        navigateTo(destination)
    }
}

/** The account's default sort as last read, which a folder without its own order follows. */
internal fun AccountSettingsState.defaultSort(): FilesSort? =
    (content as? AccountSettingsContent.Ready)?.preferences?.defaultSort

/** Pops Files one folder at a time to its root; a folder the reducer won't leave yet stops it there. */
internal fun popFilesToRoot(state: FilesBrowserState, onFilesEvent: (FilesBrowserEvent) -> Boolean) {
    repeat(state.stack.size - 1) {
        if (!onFilesEvent(FilesBrowserEvent.NavigateBack)) return
    }
}
