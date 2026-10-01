package io.putdotio.android.files

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One JSON document per user in private SharedPreferences; sign-out clears every user's. */
internal class MobileMoveTargetStore internal constructor(
    private val preferences: SharedPreferences,
    private val key: String,
) : FilesMoveTargetStore {
    constructor(context: Context, userId: Long) : this(preferences(context), "user-$userId")

    override fun read(): FilesMoveTargetMemory =
        preferences.getString(key, null)?.let { raw ->
            try {
                JSONObject(raw).toMemory()
            } catch (_: JSONException) {
                null
            }
        } ?: FilesMoveTargetMemory()

    override fun write(memory: FilesMoveTargetMemory) {
        preferences.edit { putString(key, memory.toJson().toString()) }
    }

    companion object {
        private const val PREFERENCES_NAME = "io.putdotio.android.files.move-target"

        private fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        fun clearAll(context: Context) {
            preferences(context).edit { clear() }
        }
    }
}

private fun FilesMoveTargetMemory.toJson(): JSONObject =
    JSONObject()
        .put("remember", remember)
        .put("lastTarget", JSONArray().also { array ->
            lastTarget.forEach { array.put(JSONObject().put("id", it.id.value).put("name", it.name)) }
        })

private fun JSONObject.toMemory(): FilesMoveTargetMemory {
    val path = getJSONArray("lastTarget")
    val folders = (0 until path.length()).map { index ->
        val folder = path.getJSONObject(index)
        FilesFolder(FilesItemId(folder.getLong("id")), folder.optString("name").takeIf { folder.has("name") })
    }
    // A damaged path opens at root rather than somewhere unexpected.
    return FilesMoveTargetMemory(
        remember = getBoolean("remember"),
        lastTarget = folders.takeIf { list -> list.all { it.id.value > 0L } }.orEmpty(),
    )
}
