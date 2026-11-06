package io.putdotio.android

public class OpenSourceNotice(
    public val name: String,
    public val version: String,
    public val license: String,
    public val copyright: String,
    public val sourceUrl: String,
    public val licenseUrl: String,
)

public fun appSourceNotice(appVersion: String): OpenSourceNotice =
    OpenSourceNotice(
        name = "put.io",
        version = appVersion,
        license = "GPL-3.0",
        copyright = "Copyright © put.io",
        sourceUrl = "https://github.com/putdotio/putio-android/tree/v$appVersion",
        licenseUrl = "https://www.gnu.org/licenses/gpl-3.0.html",
    )
