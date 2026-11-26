package io.putdotio.android.files

import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.account.AccountInfoQuery

public suspend fun PutioClient.loadMediaAccount(): AccountInfo =
    account.getInfo(AccountInfoQuery(downloadToken = true))
