package io.putdotio.android.tv

import android.content.Context
import androidx.core.content.edit
import io.putdotio.android.files.FilesItemId
import java.security.MessageDigest
import java.security.SecureRandom

@JvmInline
internal value class TvLinkKey(val value: String) {
    fun fileLinkSegment(id: FilesItemId): String = "${id.value}$SEPARATOR$value"

    fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(value.toByteArray(), candidate.toByteArray())

    companion object {
        const val SEPARATOR = '~'

        @Volatile
        private var cached: TvLinkKey? = null

        fun get(context: Context): TvLinkKey =
            cached ?: synchronized(this) { cached ?: load(context.applicationContext).also { cached = it } }

        private fun load(context: Context): TvLinkKey {
            val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            preferences.getString(PREFERENCE_KEY, null)?.let { return TvLinkKey(it) }
            val key = TvLinkKey(
                ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) },
            )
            preferences.edit { putString(PREFERENCE_KEY, key.value) }
            return key
        }

        private const val PREFERENCES_NAME = "io.put.tv.links"
        private const val PREFERENCE_KEY = "key"
        private const val KEY_BYTES = 16
    }
}
