package io.putdotio.android

import android.content.Context
import io.putdotio.android.auth.MobileOAuthRuntime

internal fun handleAuthTabActivityResult(
    context: Context,
    resultCode: Int,
    rawResultUri: String?,
) {
    MobileOAuthRuntime.get(context).dispatchAuthTabResult(resultCode, rawResultUri)
}
