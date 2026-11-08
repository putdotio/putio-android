package io.putdotio.android

public class OpenSourceNotice(
    public val name: String,
    public val version: String,
    public val license: String,
    public val copyright: String,
    public val sourceUrl: String,
    public val licenseUrl: String,
)

public fun appSourceNotice(appVersion: String): OpenSourceNotice {
    val repository = "https://github.com/putdotio/putio-android"
    val tag = "v${appVersion.substringBefore('-')}"
    return OpenSourceNotice(
        name = "put.io",
        version = appVersion,
        license = "MIT",
        copyright = "Copyright © put.io",
        sourceUrl = "$repository/tree/$tag",
        licenseUrl = "$repository/blob/$tag/LICENSE",
    )
}
