package io.putdotio.android.tv

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.putdotio.android.R

internal fun tvLauncherFallbackArt(context: Context): String {
    val resources = context.resources
    val art = R.drawable.tv_launcher_card
    return Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(resources.getResourceTypeName(art))
        .appendPath(resources.getResourceEntryName(art))
        .build()
        .toString()
}
