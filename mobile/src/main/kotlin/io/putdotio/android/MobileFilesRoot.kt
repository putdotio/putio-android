package io.putdotio.android

import androidx.navigation.NavHostController
import io.putdotio.android.files.FilesSort
import io.putdotio.android.settings.AccountSettingsContent
import io.putdotio.android.settings.AccountSettingsState

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

internal fun AccountSettingsState.defaultSort(): FilesSort? =
    (content as? AccountSettingsContent.Ready)?.preferences?.defaultSort
