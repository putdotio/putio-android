package io.putdotio.android

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.util.Base64

internal data class RenameProofFixture(
    val expectedAccountId: Long,
    val containerId: Long,
    val containerName: String,
    val renameItemId: Long,
    val renameOriginalName: String,
    val renameNewName: String,
    val cancelItemId: Long,
    val cancelOriginalName: String,
) {
    companion object {
        fun parse(encoded: String?): RenameProofFixture {
            try {
                require(encoded != null && encoded.length <= 32_768)
                val decoded = Base64.getDecoder().decode(encoded).decodeToString(throwOnInvalidSequence = true)
                val ids = mutableMapOf<String, Long>()
                val names = mutableMapOf<String, String>()
                JsonReader(StringReader(decoded)).use { reader ->
                    reader.isLenient = false
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(key !in ids && key !in names)
                        when (key) {
                            "expectedAccountId", "containerId", "renameItemId", "cancelItemId" -> {
                                require(reader.peek() == JsonToken.NUMBER)
                                val raw = reader.nextString()
                                require(raw.matches(Regex("[1-9][0-9]*")))
                                ids[key] = requireNotNull(raw.toLongOrNull())
                            }
                            "containerName", "renameOriginalName", "renameNewName", "cancelOriginalName" -> {
                                require(reader.peek() == JsonToken.STRING)
                                names[key] = reader.nextString().also { require(it.isNotEmpty()) }
                            }
                            else -> error("Unknown fixture field")
                        }
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                }
                require(ids.size == 4 && names.size == 4)
                val fixture = RenameProofFixture(
                    expectedAccountId = ids.getValue("expectedAccountId"),
                    containerId = ids.getValue("containerId"),
                    containerName = names.getValue("containerName"),
                    renameItemId = ids.getValue("renameItemId"),
                    renameOriginalName = names.getValue("renameOriginalName"),
                    renameNewName = names.getValue("renameNewName"),
                    cancelItemId = ids.getValue("cancelItemId"),
                    cancelOriginalName = names.getValue("cancelOriginalName"),
                )
                require(setOf(fixture.containerId, fixture.renameItemId, fixture.cancelItemId).size == 3)
                require(setOf(fixture.renameOriginalName, fixture.renameNewName, fixture.cancelOriginalName).size == 3)
                for (name in listOf(fixture.renameOriginalName, fixture.renameNewName)) {
                    require(' ' in name && name.any { it.code > 127 })
                }
                return fixture
            } catch (error: Exception) {
                // Parser exceptions may contain the supplied payload; never attach them to runner output.
                throw AssertionError("Invalid putio.rename.fixture (${error.javaClass.simpleName})")
            }
        }
    }
}
