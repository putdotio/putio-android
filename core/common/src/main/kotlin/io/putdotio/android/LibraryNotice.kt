package io.putdotio.android

import com.mikepenz.aboutlibraries.Libs

public class LibraryNotice(
    public val id: String,
    public val name: String,
    public val version: String,
    public val license: String,
    public val url: String?,
)

public fun libraryNotices(definitions: String): List<LibraryNotice> =
    Libs.Builder().withJson(definitions).build().libraries
        .map { library ->
            LibraryNotice(
                id = library.uniqueId,
                name = library.name,
                version = library.artifactVersion.orEmpty(),
                license = library.licenses.joinToString(", ") { it.spdxId ?: it.name },
                url = library.scm?.url ?: library.website ?: library.licenses.firstNotNullOfOrNull { it.url },
            )
        }
        .distinctBy { Triple(it.name, it.version, it.license) }
        .sortedBy { it.name.lowercase() }
