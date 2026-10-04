package io.putdotio.android.files

/**
 * Web's "Remember target folder" for the Move and Make a copy picker, kept on this device for one
 * account rather than in web's `/config`. [lastTarget] is the path below root to the folder last
 * chosen while [remember] was on; turning [remember] off keeps it, as web does.
 */
public data class FilesMoveTargetMemory(
    val remember: Boolean = false,
    val lastTarget: List<FilesFolder> = emptyList(),
) {
    /** Where the picker opens, below root; a move never opens inside the item it moves. */
    public fun startPath(sourceItem: FilesItem?): List<FilesFolder> = when {
        !remember -> emptyList()
        sourceItem != null && lastTarget.any { it.id == sourceItem.id } -> emptyList()
        else -> lastTarget
    }

    /** Web records the chosen folder only while the toggle is on. */
    public fun chosen(path: List<FilesFolder>): FilesMoveTargetMemory =
        if (remember) copy(lastTarget = path.filter { it.id.value > 0L }) else this
}

public interface FilesMoveTargetStore {
    public fun read(): FilesMoveTargetMemory

    public fun write(memory: FilesMoveTargetMemory)
}
