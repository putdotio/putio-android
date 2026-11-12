package io.putdotio.android.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

internal fun openChromeStoreListing(context: Context): Boolean =
    listOf(
        "market://details?id=$CHROME_PACKAGE",
        "https://play.google.com/store/apps/details?id=$CHROME_PACKAGE",
    ).any { listing ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, listing.toUri()).setPackage(PLAY_STORE_PACKAGE))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

private const val PLAY_STORE_PACKAGE = "com.android.vending"
private const val CHROME_PACKAGE = "com.android.chrome"
