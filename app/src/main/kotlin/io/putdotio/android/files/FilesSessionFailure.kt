package io.putdotio.android.files

/** The newest 401 anywhere in the stack; a session verdict that outranks every other failure. */
internal fun FilesBrowserState.authoritativeSessionFailure(): FilesFailure? =
    stack.asReversed().firstNotNullOfOrNull { folder ->
        val contentFailure = folder.content.authoritativeSessionFailure()
        val operationFailure = (folder.operation as? FilesFolderOperation.Failed)?.failure
        listOfNotNull(contentFailure, operationFailure)
            .firstOrNull { it is FilesFailure.AuthenticationRequired }
    }

internal fun FilesContent.authoritativeSessionFailure(): FilesFailure? = when (this) {
    is FilesContent.Failed -> failure
    is FilesContent.Empty -> (paging as? FilesPaging.Failed)?.failure
    is FilesContent.Ready -> (paging as? FilesPaging.Failed)?.failure
    is FilesContent.Loading -> null
}?.takeIf { it is FilesFailure.AuthenticationRequired }
