package io.putdotio.android.files

internal class InMemoryMoveTargetStore(var memory: FilesMoveTargetMemory = FilesMoveTargetMemory()) :
    FilesMoveTargetStore {
    override fun read(): FilesMoveTargetMemory = memory

    override fun write(memory: FilesMoveTargetMemory) {
        this.memory = memory
    }
}
