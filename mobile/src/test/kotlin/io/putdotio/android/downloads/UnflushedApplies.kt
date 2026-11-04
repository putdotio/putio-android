package io.putdotio.android.downloads

import android.content.SharedPreferences

/**
 * A process that dies before the system flushes an apply: applied edits stay in memory, and only
 * a commit writes the memory, theirs included, to the disk.
 */
internal class UnflushedApplies(private val disk: SharedPreferences) : SharedPreferences by disk {
    private val memory = mutableMapOf<String, String?>()

    override fun getString(key: String, defValue: String?): String? =
        if (key in memory) memory[key] else disk.getString(key, defValue)

    override fun contains(key: String): Boolean = key in memory || disk.contains(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by disk.edit() {
        private val edits = mutableMapOf<String, String?>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            edits[key] = value
            return this
        }

        override fun apply() {
            memory += edits
        }

        override fun commit(): Boolean {
            memory += edits
            val writer = disk.edit()
            memory.forEach { (key, value) -> writer.putString(key, value) }
            return writer.commit()
        }
    }
}
