package io.putdotio.android.files

// States as the reducers would hold them, for tests outside this module; request ids continue
// from nextRequestValue.

public fun filesBrowserState(
    stack: List<FilesFolderState>,
    nextRequestValue: Long,
    copyOutcome: FilesCopyOutcome? = null,
): FilesBrowserState = FilesBrowserState(stack, nextRequestValue, copyOutcome)

public fun filesMoveDestinationFolder(folder: FilesFolder, content: FilesContent): FilesMoveDestinationFolder =
    FilesMoveDestinationFolder(folder, content)

public fun filesMoveDestinationState(
    sourceItem: FilesItem?,
    sourceFolderId: FilesItemId?,
    stack: List<FilesMoveDestinationFolder>,
    nextRequestValue: Long,
): FilesMoveDestinationState = FilesMoveDestinationState(sourceItem, sourceFolderId, stack, nextRequestValue)

/** [copy] for tests outside this module; request ids keep counting from this state. */
public fun FilesBrowserState.copyForTest(
    stack: List<FilesFolderState> = this.stack,
    copyOutcome: FilesCopyOutcome? = this.copyOutcome,
): FilesBrowserState = copy(stack = stack, copyOutcome = copyOutcome)
