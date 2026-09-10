package io.putdotio.android.tv.auth

import android.content.Context
import io.putdotio.android.BuildConfig

/** Which put.io OAuth app this TV build links as. Fire TV keeps its own registration. */
internal data class TvOAuthClient(
    val clientId: String,
    val clientName: String,
) {
    companion object {
        fun forDevice(context: Context): TvOAuthClient =
            select(isFireTv = context.packageManager.hasSystemFeature(FIRE_TV_SYSTEM_FEATURE))

        fun select(isFireTv: Boolean): TvOAuthClient =
            if (isFireTv) {
                TvOAuthClient(BuildConfig.PUTIO_TV_OAUTH_CLIENT_ID_FIRE_TV, FIRE_TV_CLIENT_NAME)
            } else {
                TvOAuthClient(BuildConfig.PUTIO_TV_OAUTH_CLIENT_ID_ANDROID_TV, ANDROID_TV_CLIENT_NAME)
            }
    }
}

/** Amazon declares this feature on every Fire TV device; nothing else does. */
internal const val FIRE_TV_SYSTEM_FEATURE = "amazon.hardware.fire_tv"
private const val ANDROID_TV_CLIENT_NAME = "put.io Android TV"
private const val FIRE_TV_CLIENT_NAME = "put.io Fire TV"
