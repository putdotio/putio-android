package io.putdotio.android.downloads

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Device-wide download preferences. The one Media3 manager serves every account
 * on this device, so its concurrency is a device setting, as on iOS.
 */
internal class MobileDownloadSettings(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(downloadPreferences(context))

    /** Survives process death; Media3 reads it whenever the manager is built. */
    var concurrency: Int
        get() = preferences.getInt(CONCURRENCY_KEY, DOWNLOAD_CONCURRENCY_DEFAULT)
            .takeIf { it in DOWNLOAD_CONCURRENCY_CHOICES } ?: DOWNLOAD_CONCURRENCY_DEFAULT
        set(value) {
            require(value in DOWNLOAD_CONCURRENCY_CHOICES) { "Unsupported download concurrency $value" }
            preferences.edit(commit = true) { putInt(CONCURRENCY_KEY, value) }
        }

    /** The first download asks for notification permission once; later asks come from Downloads. */
    var notificationPermissionAsked: Boolean
        get() = preferences.getBoolean(NOTIFICATION_PROMPT_KEY, false)
        set(value) = preferences.edit { putBoolean(NOTIFICATION_PROMPT_KEY, value) }

    private companion object {
        const val CONCURRENCY_KEY = "concurrency"
        const val NOTIFICATION_PROMPT_KEY = "notification-permission-asked"
    }
}
