package io.putdotio.android.downloads

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

internal class MobileDownloadSettings(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(downloadPreferences(context))

    var concurrency: Int
        get() = preferences.getInt(CONCURRENCY_KEY, DOWNLOAD_CONCURRENCY_DEFAULT)
            .takeIf { it in DOWNLOAD_CONCURRENCY_CHOICES } ?: DOWNLOAD_CONCURRENCY_DEFAULT
        set(value) {
            require(value in DOWNLOAD_CONCURRENCY_CHOICES) { "Unsupported download concurrency $value" }
            preferences.edit { putInt(CONCURRENCY_KEY, value) }
        }

    var notificationPermissionAsked: Boolean
        get() = preferences.getBoolean(NOTIFICATION_PROMPT_KEY, false)
        set(value) = preferences.edit { putBoolean(NOTIFICATION_PROMPT_KEY, value) }

    private companion object {
        const val CONCURRENCY_KEY = "concurrency"
        const val NOTIFICATION_PROMPT_KEY = "notification-permission-asked"
    }
}
