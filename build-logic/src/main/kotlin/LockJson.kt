import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

internal object LockJson {
    fun duplicateKey(json: String): String? {
        val scopes = ArrayDeque<MutableSet<String>?>()
        var expectKey = false
        var index = 0
        while (index < json.length) {
            when (json[index]) {
                '"' -> {
                    val end = stringEnd(json, index)
                    if (expectKey) {
                        val key = Json.parseToJsonElement(json.substring(index, end + 1)).jsonPrimitive.content
                        if (!scopes.last()!!.add(key)) return key
                        expectKey = false
                    }
                    index = end
                }
                '{' -> {
                    scopes.addLast(mutableSetOf())
                    expectKey = true
                }
                '[' -> {
                    scopes.addLast(null)
                    expectKey = false
                }
                '}', ']' -> {
                    scopes.removeLast()
                    expectKey = false
                }
                ',' -> expectKey = scopes.lastOrNull() != null
            }
            index++
        }
        return null
    }

    private fun stringEnd(json: String, start: Int): Int {
        var index = start + 1
        while (json[index] != '"') index += if (json[index] == '\\') 2 else 1
        return index
    }
}
