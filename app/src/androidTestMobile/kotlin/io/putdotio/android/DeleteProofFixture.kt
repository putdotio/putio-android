package io.putdotio.android

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.util.Base64

internal data class DeleteProofFixture(
    val expectedAccountId: Long,
    val containerId: Long,
    val containerName: String,
    val actionItemId: Long,
    val actionName: String,
    val expectedTrashEnabled: Boolean,
    val cancelItemId: Long,
    val cancelName: String,
) {
    companion object {
        fun parse(encoded: String?): DeleteProofFixture {
            try {
                require(encoded != null && encoded.length <= 32_768)
                val decoded = Base64.getDecoder().decode(encoded).decodeToString(throwOnInvalidSequence = true)
                val ids = mutableMapOf<String, Long>()
                val names = mutableMapOf<String, String>()
                var trash: Boolean? = null
                val keys = mutableSetOf<String>()
                JsonReader(StringReader(decoded)).use { reader ->
                    reader.isLenient = false
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(keys.add(key))
                        when (key) {
                            "expectedAccountId", "containerId", "actionItemId", "cancelItemId" -> {
                                require(reader.peek() == JsonToken.NUMBER)
                                val raw = reader.nextString()
                                require(raw.matches(Regex("[1-9][0-9]*")))
                                ids[key] = requireNotNull(raw.toLongOrNull())
                            }
                            "containerName", "actionName", "cancelName" -> {
                                require(reader.peek() == JsonToken.STRING)
                                names[key] = reader.nextString().also { require(it.isNotEmpty()) }
                            }
                            "expectedTrashEnabled" -> {
                                require(reader.peek() == JsonToken.BOOLEAN)
                                trash = reader.nextBoolean()
                            }
                            else -> error("Unknown fixture field")
                        }
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                }
                require(ids.size == 4 && names.size == 3 && keys.size == 8)
                val fixture = DeleteProofFixture(
                    expectedAccountId = ids.getValue("expectedAccountId"),
                    containerId = ids.getValue("containerId"),
                    containerName = names.getValue("containerName"),
                    actionItemId = ids.getValue("actionItemId"),
                    actionName = names.getValue("actionName"),
                    expectedTrashEnabled = requireNotNull(trash),
                    cancelItemId = ids.getValue("cancelItemId"),
                    cancelName = names.getValue("cancelName"),
                )
                require(setOf(fixture.containerId, fixture.actionItemId, fixture.cancelItemId).size == 3)
                require(setOf(fixture.containerName, fixture.actionName, fixture.cancelName).size == 3)
                return fixture
            } catch (error: Exception) {
                // Parser exceptions may contain the supplied payload; never attach them to runner output.
                throw AssertionError("Invalid putio.delete.fixture (${error.javaClass.simpleName})")
            }
        }
    }
}
