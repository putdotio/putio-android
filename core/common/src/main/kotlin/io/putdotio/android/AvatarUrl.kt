package io.putdotio.android

import java.net.URI

public fun String.isSupportedAvatarUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.rawAuthority?.endsWith(':') != true &&
        (uri.port == -1 || uri.port in 1..MAX_PORT)
}

private const val MAX_PORT = 65535
