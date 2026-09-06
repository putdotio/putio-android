package io.putdotio.android

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.util.Base64

internal data class MoveProofFixture(
    val expectedAccountId: Long,
    val containerId: Long,
    val containerName: String,
    val sourceId: Long,
    val sourceName: String,
    val destinationId: Long,
    val destinationName: String,
    val folderItemId: Long,
    val folderName: String,
    val fileItemId: Long,
    val fileName: String,
    val fileSize: Long,
    val collisionItemId: Long,
    val collisionPeerId: Long,
    val collisionName: String,
) {
    companion object {
        fun parse(encoded: String?): MoveProofFixture {
            try {
                require(encoded != null && encoded.length <= 32_768)
                val decoded = Base64.getDecoder().decode(encoded).decodeToString(throwOnInvalidSequence = true)
                val numbers = mutableMapOf<String, Long>()
                val names = mutableMapOf<String, String>()
                val keys = mutableSetOf<String>()
                JsonReader(StringReader(decoded)).use { reader ->
                    reader.isLenient = false
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(keys.add(key))
                        when (key) {
                            "expectedAccountId", "containerId", "sourceId", "destinationId", "folderItemId",
                            "fileItemId", "fileSize", "collisionItemId", "collisionPeerId" -> {
                                require(reader.peek() == JsonToken.NUMBER)
                                val raw = reader.nextString()
                                require(raw.matches(Regex("[1-9][0-9]*")))
                                numbers[key] = requireNotNull(raw.toLongOrNull())
                            }
                            "containerName", "sourceName", "destinationName",
                            "folderName", "fileName", "collisionName" -> {
                                require(reader.peek() == JsonToken.STRING)
                                names[key] = reader.nextString().also { name ->
                                    require(name.isNotBlank() && name.none { it.isISOControl() })
                                }
                            }
                            else -> error("Unknown fixture field")
                        }
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                }
                require(numbers.size == 9 && names.size == 6 && keys.size == 15)
                require(numbers.getValue("fileSize") <= 128)
                require(numbers.filterKeys { it != "expectedAccountId" && it != "fileSize" }.values.toSet().size == 7)
                require(names.values.toSet().size == 6)
                require(names.getValue("folderName").any { it.code > 127 })
                require(names.getValue("fileName").any { it.code > 127 })
                return MoveProofFixture(
                    numbers.getValue("expectedAccountId"),
                    numbers.getValue("containerId"), names.getValue("containerName"),
                    numbers.getValue("sourceId"), names.getValue("sourceName"),
                    numbers.getValue("destinationId"), names.getValue("destinationName"),
                    numbers.getValue("folderItemId"), names.getValue("folderName"),
                    numbers.getValue("fileItemId"), names.getValue("fileName"), numbers.getValue("fileSize"),
                    numbers.getValue("collisionItemId"), numbers.getValue("collisionPeerId"),
                    names.getValue("collisionName"),
                )
            } catch (error: Exception) {
                // Parser errors may contain supplied payloads; report only their type.
                throw AssertionError("Invalid putio.move.fixture (${error.javaClass.simpleName})")
            }
        }
    }
}
