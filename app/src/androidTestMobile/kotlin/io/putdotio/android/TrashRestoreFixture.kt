package io.putdotio.android

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.util.Base64
import java.util.UUID

/** The host ledger owns mutation state; this input names only its four disposable items. */
internal data class TrashRestoreFixture(
    val runId: String,
    val expectedAccountId: Long,
    val container: TrashRestoreFixtureItem,
    val file: TrashRestoreFixtureItem,
    val cancel: TrashRestoreFixtureItem,
    val sentinel: TrashRestoreFixtureItem,
    val fileSize: Long,
) {
    companion object {
        fun parse(encoded: String?, expectedRunId: String?): TrashRestoreFixture {
            try {
                require(encoded != null && encoded.length <= 32_768)
                require(expectedRunId != null && UUID.fromString(expectedRunId).toString() == expectedRunId)
                val decoded = Base64.getDecoder().decode(encoded).decodeToString(throwOnInvalidSequence = true)
                val numbers = mutableMapOf<String, Long>()
                val strings = mutableMapOf<String, String>()
                val keys = mutableSetOf<String>()
                val roles = listOf("container", "file", "cancel", "sentinel")
                val numberKeys = roles.flatMap { listOf("${it}Id", "${it}ParentId") } +
                    listOf("expectedAccountId", "fileSize")
                val stringKeys = roles.flatMap { listOf("${it}Name", "${it}FileType") } + "runId"
                JsonReader(StringReader(decoded)).use { reader ->
                    reader.isLenient = false
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(keys.add(key))
                        when (key) {
                            in numberKeys -> {
                                require(reader.peek() == JsonToken.NUMBER)
                                val raw = reader.nextString()
                                require(raw.matches(Regex("0|[1-9][0-9]*")))
                                numbers[key] = requireNotNull(raw.toLongOrNull())
                            }
                            in stringKeys -> {
                                require(reader.peek() == JsonToken.STRING)
                                strings[key] = reader.nextString().also {
                                    require(it.isNotBlank() && it.length <= 255 && it.none(Char::isISOControl))
                                    require(it.toByteArray(Charsets.UTF_8).decodeToString() == it)
                                }
                            }
                            else -> error("Unknown fixture field")
                        }
                    }
                    reader.endObject()
                    require(reader.peek() == JsonToken.END_DOCUMENT)
                }
                require(keys == (numberKeys + stringKeys).toSet())
                require(strings.getValue("runId") == expectedRunId)
                require(numbers.getValue("expectedAccountId") > 0)
                require(numbers.getValue("fileSize") in 1..128)
                val items = roles.associateWith { role ->
                    val id = numbers.getValue("${role}Id")
                    val parent = numbers.getValue("${role}ParentId")
                    val name = strings.getValue("${role}Name")
                    val kind = strings.getValue("${role}FileType")
                    require(id > 0)
                    require(if (role == "file") kind in setOf("FILE", "TEXT") else kind == "FOLDER")
                    require(parent == if (role == "container") 0L else numbers.getValue("containerId"))
                    if (role == "container") require(name == "android-trash-restore-proof-$expectedRunId")
                    else require(name.contains(expectedRunId) && name.any { it.code > 127 })
                    TrashRestoreFixtureItem(id, name, parent, kind)
                }
                require(items.values.map { it.id }.toSet().size == 4)
                require(items.values.map { it.name }.toSet().size == 4)
                return TrashRestoreFixture(
                    expectedRunId, numbers.getValue("expectedAccountId"), items.getValue("container"),
                    items.getValue("file"), items.getValue("cancel"), items.getValue("sentinel"),
                    numbers.getValue("fileSize"),
                )
            } catch (error: Exception) {
                // Parsing diagnostics can contain supplied data; never preserve the payload or cause.
                throw AssertionError("Invalid putio.trash.restore.fixture (${error.javaClass.simpleName})")
            }
        }
    }
}

internal data class TrashRestoreFixtureItem(val id: Long, val name: String, val parentId: Long, val fileType: String)
