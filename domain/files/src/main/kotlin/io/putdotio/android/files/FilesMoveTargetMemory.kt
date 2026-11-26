package io.putdotio.android.files

public data class FilesMoveTargetMemory(
    val remember: Boolean = false,
    val lastTarget: List<FilesFolder> = emptyList(),
) {
    public fun startPath(sourceItem: FilesItem?): List<FilesFolder> = startPath(listOfNotNull(sourceItem))

    public fun startPath(sourceItems: List<FilesItem>): List<FilesFolder> = when {
        !remember -> emptyList()
        lastTarget.any { target -> sourceItems.any { it.id == target.id } } -> emptyList()
        else -> lastTarget
    }

    public fun chosen(path: List<FilesFolder>): FilesMoveTargetMemory =
        if (remember) copy(lastTarget = path.filter { it.id.value > 0L }) else this
}

public interface FilesMoveTargetStore {
    public fun read(): FilesMoveTargetMemory

    public fun write(memory: FilesMoveTargetMemory)
}
