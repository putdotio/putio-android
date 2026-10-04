package io.putdotio.android.sharing

import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItemId
import io.putdotio.sdk.files.PutioFileType
import java.time.Instant

public class FakePublicLinksRepository(
    links: List<PublicLink> = emptyList(),
) : PublicLinksRepository {
    public val links: MutableList<PublicLink> = links.toMutableList()
    public val created: MutableList<FilesItemId> = mutableListOf()
    public val revoked: MutableList<PublicLinkId> = mutableListOf()
    public var listCount: Int = 0
    private var nextId = (links.maxOfOrNull { it.id.value } ?: 0L) + 1L
    public var onList: suspend () -> PutioResult<List<PublicLink>> = { PutioResult.Success(this.links.toList()) }
    public var onCreate: suspend (FilesItemId) -> PutioResult<PublicLink> = { fileId ->
        val link = publicLink(id = nextId++, fileId = fileId.value)
        this.links += link
        PutioResult.Success(link)
    }
    public var onRevoke: suspend (PublicLinkId) -> PutioResult<Unit> = { id ->
        this.links.removeAll { it.id == id }
        PutioResult.Success(Unit)
    }

    override suspend fun list(): PutioResult<List<PublicLink>> {
        listCount += 1
        return onList()
    }

    override suspend fun create(fileId: FilesItemId): PutioResult<PublicLink> {
        created += fileId
        return onCreate(fileId)
    }

    override suspend fun revoke(id: PublicLinkId): PutioResult<Unit> {
        revoked += id
        return onRevoke(id)
    }
}

public fun publicLink(
    id: Long = 1L,
    fileId: Long = 7L,
    name: String = "Harbor film.mp4",
    type: PutioFileType = PutioFileType.VIDEO,
    token: String = "token-$id",
    expiresAt: Instant? = Instant.parse("2026-10-07T19:36:01Z"),
): PublicLink = PublicLink(
    id = PublicLinkId(id),
    fileId = FilesItemId(fileId),
    fileName = name,
    fileType = type,
    url = PublicLinkUrl.of(token),
    expiresAt = expiresAt,
)
