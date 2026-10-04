package io.putdotio.android.sharing

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItemId

public sealed interface PublicLinksContent {
    public data object Idle : PublicLinksContent

    public data object Loading : PublicLinksContent

    /** The account's links, newest first. */
    public data class Ready(val links: List<PublicLink>) : PublicLinksContent

    public data class Failed(val failure: PutioFailure) : PublicLinksContent
}

public sealed interface PublicLinksMutation {
    public data class Creating(val fileId: FilesItemId) : PublicLinksMutation

    public data class Revoking(val link: PublicLink) : PublicLinksMutation
}

/** How the last create or revoke ended; it stays until dismissed or the links are read again. */
public sealed interface PublicLinksOutcome {
    public val fileId: FilesItemId

    public data class Created(val link: PublicLink) : PublicLinksOutcome {
        override val fileId: FilesItemId get() = link.fileId
    }

    public data class CreateFailed(
        override val fileId: FilesItemId,
        val failure: PutioFailure,
    ) : PublicLinksOutcome {
        /** Web's own wording applies to these; anything else reads as [failure]. */
        val refusal: PublicLinkRefusal? get() = failure.publicLinkRefusal
    }

    public data class Revoked(val link: PublicLink) : PublicLinksOutcome {
        override val fileId: FilesItemId get() = link.fileId
    }

    public data class RevokeFailed(
        val link: PublicLink,
        val failure: PutioFailure,
    ) : PublicLinksOutcome {
        override val fileId: FilesItemId get() = link.fileId
    }
}

public data class PublicLinksState(
    val content: PublicLinksContent = PublicLinksContent.Idle,
    val mutation: PublicLinksMutation? = null,
    val outcome: PublicLinksOutcome? = null,
) {
    /**
     * put.io issues a new link on every create, so one waits for the account's links to load:
     * the viewer sees what already exists before making another.
     */
    val canCreate: Boolean
        get() = content is PublicLinksContent.Ready && mutation == null

    val canRevoke: Boolean
        get() = content is PublicLinksContent.Ready && mutation == null

    /** [fileId]'s links, newest first; null until the account's links have loaded. */
    public fun linksFor(fileId: FilesItemId): List<PublicLink>? =
        (content as? PublicLinksContent.Ready)?.links?.filter { it.fileId == fileId }

    /** A rejected session; the shell signs out and nothing else runs. */
    val authenticationFailure: PutioFailure?
        get() = listOfNotNull(
            (content as? PublicLinksContent.Failed)?.failure,
            (outcome as? PublicLinksOutcome.CreateFailed)?.failure,
            (outcome as? PublicLinksOutcome.RevokeFailed)?.failure,
        ).firstOrNull { it is PutioFailure.AuthenticationRequired }
}

public sealed interface PublicLinksEvent {
    /** Reads the account's links again; ignored while a request runs. */
    public data object Load : PublicLinksEvent

    public data class Create(val fileId: FilesItemId) : PublicLinksEvent

    public data class Revoke(val id: PublicLinkId) : PublicLinksEvent

    public data object DismissOutcome : PublicLinksEvent
}

internal sealed interface PublicLinksRequest {
    val id: Long

    data class Load(override val id: Long) : PublicLinksRequest

    data class Create(override val id: Long, val fileId: FilesItemId) : PublicLinksRequest

    data class Revoke(override val id: Long, val link: PublicLink) : PublicLinksRequest
}

/** One request at a time; a stale completion never lands because the controller matches its request. */
internal data class PublicLinksMachine(
    val state: PublicLinksState = PublicLinksState(),
    val request: PublicLinksRequest? = null,
    val nextRequestId: Long = 1L,
) {
    fun transition(event: PublicLinksEvent): PublicLinksMachine? {
        if (state.authenticationFailure != null) return null
        return when (event) {
            PublicLinksEvent.Load -> load()
            is PublicLinksEvent.Create -> create(event.fileId)
            is PublicLinksEvent.Revoke -> revoke(event.id)
            PublicLinksEvent.DismissOutcome ->
                if (state.outcome == null) null else copy(state = state.copy(outcome = null))
        }
    }

    private fun load(): PublicLinksMachine? =
        if (request != null) null else copy(
            state = state.copy(content = PublicLinksContent.Loading, outcome = null),
            request = PublicLinksRequest.Load(nextRequestId),
            nextRequestId = nextRequestId + 1L,
        )

    private fun create(fileId: FilesItemId): PublicLinksMachine? =
        if (request != null || !state.canCreate || fileId.value <= 0L) null else copy(
            state = state.copy(mutation = PublicLinksMutation.Creating(fileId), outcome = null),
            request = PublicLinksRequest.Create(nextRequestId, fileId),
            nextRequestId = nextRequestId + 1L,
        )

    private fun revoke(id: PublicLinkId): PublicLinksMachine? {
        val link = (state.content as? PublicLinksContent.Ready)?.links?.firstOrNull { it.id == id }
        return if (request != null || !state.canRevoke || link == null) null else copy(
            state = state.copy(mutation = PublicLinksMutation.Revoking(link), outcome = null),
            request = PublicLinksRequest.Revoke(nextRequestId, link),
            nextRequestId = nextRequestId + 1L,
        )
    }

    fun completeLoad(result: PutioResult<List<PublicLink>>): PublicLinksMachine =
        copy(
            state = state.copy(
                content = when (result) {
                    is PutioResult.Success -> PublicLinksContent.Ready(result.value.newestFirst())
                    is PutioResult.Failure -> PublicLinksContent.Failed(result.failure)
                },
            ),
            request = null,
        )

    fun completeCreate(fileId: FilesItemId, result: PutioResult<PublicLink>): PublicLinksMachine =
        when (result) {
            is PutioResult.Success -> copy(
                state = state.copy(
                    content = state.content.withLinks { (it + result.value).newestFirst() },
                    mutation = null,
                    outcome = PublicLinksOutcome.Created(result.value),
                ),
                request = null,
            )
            is PutioResult.Failure -> copy(
                state = state.copy(mutation = null, outcome = PublicLinksOutcome.CreateFailed(fileId, result.failure)),
                request = null,
            )
        }

    fun completeRevoke(link: PublicLink, result: PutioResult<Unit>): PublicLinksMachine =
        when (result) {
            is PutioResult.Success -> copy(
                state = state.copy(
                    content = state.content.withLinks { links -> links.filterNot { it.id == link.id } },
                    mutation = null,
                    outcome = PublicLinksOutcome.Revoked(link),
                ),
                request = null,
            )
            is PutioResult.Failure -> copy(
                state = state.copy(mutation = null, outcome = PublicLinksOutcome.RevokeFailed(link, result.failure)),
                request = null,
            )
        }
}

private fun PublicLinksContent.withLinks(change: (List<PublicLink>) -> List<PublicLink>): PublicLinksContent =
    if (this is PublicLinksContent.Ready) PublicLinksContent.Ready(change(links)) else this

// put.io issues ids in creation order.
private fun List<PublicLink>.newestFirst(): List<PublicLink> =
    distinctBy { it.id }.sortedByDescending { it.id.value }
