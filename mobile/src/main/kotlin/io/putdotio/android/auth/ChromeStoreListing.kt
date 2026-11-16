package io.putdotio.android.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

internal fun openChromeStoreListing(context: Context): Boolean = openStoreListing(context, CHROME_PACKAGE)

internal fun openStoreListing(context: Context, appPackage: String): Boolean =
    listOf(
        "market://details?id=$appPackage",
        "https://play.google.com/store/apps/details?id=$appPackage",
    ).any { listing ->
        try {
            val intent = Intent(Intent.ACTION_VIEW, listing.toUri()).setPackage(PLAY_STORE_PACKAGE)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

private const val PLAY_STORE_PACKAGE = "com.android.vending"
private const val CHROME_PACKAGE = "com.android.chrome"
