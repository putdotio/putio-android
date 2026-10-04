package io.putdotio.android.sharing

import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.parsePutioTimestamp
import io.putdotio.android.putioRequest
import io.putdotio.sdk.OkResponse
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.errors.PutioRequestData
import io.putdotio.sdk.errors.PutioSerializationException
import io.putdotio.sdk.sharing.PublicShare

public interface PublicLinksRepository {
    /** Every public link the account holds. */
    public suspend fun list(): PutioResult<List<PublicLink>>

    /** A new link to [fileId]; put.io issues another one on every call. */
    public suspend fun create(fileId: FilesItemId): PutioResult<PublicLink>

    public suspend fun revoke(id: PublicLinkId): PutioResult<Unit>
}

public class SdkPublicLinksRepository internal constructor(
    private val listShares: suspend () -> List<PublicShare>,
    private val createShare: suspend (Long) -> PublicShare,
    private val deleteShare: suspend (Long) -> OkResponse,
) : PublicLinksRepository {
    public constructor(client: PutioClient) : this(
        listShares = { client.sharing.publicShares.list() },
        createShare = { client.sharing.publicShares.create(it) },
        deleteShare = { client.sharing.publicShares.delete(it) },
    )

    override suspend fun list(): PutioResult<List<PublicLink>> = putioRequest {
        listShares().map { it.toPublicLink(PutioRequestData("GET", "/public_share/list")) }
    }

    override suspend fun create(fileId: FilesItemId): PutioResult<PublicLink> = putioRequest {
        // Root and the virtual shared folders are not the viewer's to share.
        require(fileId.value > 0L) { "A public link needs one positive file ID" }
        createShare(fileId.value).toPublicLink(PutioRequestData("POST", "/public_share/${fileId.value}"))
    }

    override suspend fun revoke(id: PublicLinkId): PutioResult<Unit> = putioRequest {
        require(id.value > 0L) { "Revoking needs a positive public link ID" }
        deleteShare(id.value)
        Unit
    }
}

private fun PublicShare.toPublicLink(request: PutioRequestData): PublicLink {
    if (id <= 0L || userFile.id <= 0L || token.value.isBlank()) {
        throw PutioSerializationException(request, "", IllegalArgumentException("Public link without an ID or token"))
    }
    return PublicLink(
        id = PublicLinkId(id),
        fileId = FilesItemId(userFile.id),
        fileName = userFile.name,
        fileType = userFile.fileType,
        url = PublicLinkUrl.of(token.value),
        expiresAt = parsePutioTimestamp(expirationDate),
    )
}
