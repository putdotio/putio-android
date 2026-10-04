package io.putdotio.android.files

import io.putdotio.android.PutioFailure

/** The newest 401 anywhere in the stack or the copy; a session verdict that outranks every other failure. */
public fun FilesBrowserState.authoritativeSessionFailure(): PutioFailure? =
    stack.asReversed().firstNotNullOfOrNull { folder ->
        val contentFailure = folder.content.authoritativeSessionFailure()
        val operationFailure = (folder.operation as? FilesFolderOperation.Failed)?.failure
        listOfNotNull(contentFailure, operationFailure)
            .firstOrNull { it is PutioFailure.AuthenticationRequired }
    } ?: copyOutcome?.failure?.takeIf { it is PutioFailure.AuthenticationRequired }

public fun FilesContent.authoritativeSessionFailure(): PutioFailure? = when (this) {
    is FilesContent.Failed -> failure
    is FilesContent.Empty -> (paging as? FilesPaging.Failed)?.failure
    is FilesContent.Ready -> (paging as? FilesPaging.Failed)?.failure
    is FilesContent.Loading -> null
}?.takeIf { it is PutioFailure.AuthenticationRequired }
