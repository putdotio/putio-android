package io.putdotio.android.files

import androidx.compose.runtime.staticCompositionLocalOf

internal fun interface MobileFilesViewports {
    fun latest(content: FilesContent.Ready): FilesViewportPosition?
}

internal val LocalMobileFilesViewports = staticCompositionLocalOf { MobileFilesViewports { null } }

internal fun FilesBrowserState.viewportOf(content: FilesContent.Ready): FilesViewportPosition? =
    stack.firstNotNullOfOrNull { folder ->
        (folder.content as? FilesContent.Ready)?.takeIf { it.items === content.items }?.viewport
    }
