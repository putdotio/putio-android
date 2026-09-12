package io.putdotio.android

import java.net.URI

/** Only an https URL with a host, a valid port, and no credentials is loaded as an avatar. */
internal fun String.isSupportedAvatarUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        // URI accepts "host:" and any integer port; a loader would not.
        uri.rawAuthority?.endsWith(':') != true &&
        (uri.port == -1 || uri.port in 1..MAX_PORT)
}

private const val MAX_PORT = 65535
