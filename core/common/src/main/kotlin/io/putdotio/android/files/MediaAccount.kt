package io.putdotio.android.files

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountInfoQuery

/**
 * The account with its download token, the narrow credential put.io accepts on media endpoints.
 * Every media URL the app builds carries this token, never the session's access token.
 */
public suspend fun PutioClient.loadMediaAccount(): AccountInfo =
    account.getInfo(AccountInfoQuery(downloadToken = true))
