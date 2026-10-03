package io.putdotio.android.auth

import android.content.Context

internal fun handleAuthTabActivityResult(
    context: Context,
    resultCode: Int,
    rawResultUri: String?,
) {
    MobileOAuthRuntime.get(context).dispatchAuthTabResult(resultCode, rawResultUri)
}
