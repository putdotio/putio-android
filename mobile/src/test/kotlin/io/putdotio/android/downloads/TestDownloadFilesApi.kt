package io.putdotio.android.downloads

import io.putdotio.android.PutioHttpClient
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.PutioConfig
import io.putdotio.sdk.files.FilesApi

internal val testDownloadFilesApi: FilesApi =
    PutioClient(PutioConfig(), okHttpClient = PutioHttpClient.shared).files
