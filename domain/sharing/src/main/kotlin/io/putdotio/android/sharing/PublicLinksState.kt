package io.putdotio.android.sharing

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesItemId

public sealed interface PublicLinksContent {
    public data object Idle : PublicLinksContent

    public data object Loading : PublicLinksContent

    public data class Ready(val links: List<PublicLink>) : PublicLinksContent

    public data class Failed(val failure: PutioFailure) : PublicLinksContent
}

public sealed interface PublicLinksMutation {
    public data class Creating(val fileId: FilesItemId) : PublicLinksMutation

    public data class Revoking(val link: PublicLink) : PublicLinksMutation
}

public sealed interface PublicLinksOutcome {
    public val fileId: FilesItemId

    public data class Created(val link: PublicLink) : PublicLinksOutcome {
        override val fileId: FilesItemId get() = link.fileId
    }

    public data class CreateFailed(
        override val fileId: FilesItemId,
        val failure: PutioFailure,
    ) : PublicLinksOutcome {
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
    val refreshing: Boolean = false,
) {
    val canCreate: Boolean
        get() = content is PublicLinksContent.Ready && mutation == null && !refreshing

    val canRevoke: Boolean
        get() = canCreate

    public fun linksFor(fileId: FilesItemId): List<PublicLink>? =
        (content as? PublicLinksContent.Ready)?.links?.filter { it.fileId == fileId }

    val authenticationFailure: PutioFailure?
        get() = listOfNotNull(
            (content as? PublicLinksContent.Failed)?.failure,
            (outcome as? PublicLinksOutcome.CreateFailed)?.failure,
            (outcome as? PublicLinksOutcome.RevokeFailed)?.failure,
        ).firstOrNull { it is PutioFailure.AuthenticationRequired }
}

public sealed interface PublicLinksEvent {
    public data object Load : PublicLinksEvent

    public data class Create(val fileId: FilesItemId) : PublicLinksEvent

    public data class Revoke(val id: PublicLinkId) : PublicLinksEvent

    public data object DismissOutcome : PublicLinksEvent
}

internal sealed interface PublicLinksRequest {
    val id: Long

    data class Load(override val id: Long, val refresh: Boolean = false) : PublicLinksRequest

    data class Create(override val id: Long, val fileId: FilesItemId) : PublicLinksRequest

    data class Revoke(override val id: Long, val link: PublicLink) : PublicLinksRequest
}

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

    fun completeLoad(request: PublicLinksRequest.Load, result: PutioResult<List<PublicLink>>): PublicLinksMachine =
        copy(
            state = state.copy(
                content = when (result) {
                    is PutioResult.Success -> PublicLinksContent.Ready(result.value.newestFirst())
                    is PutioResult.Failure ->
                        if (request.refresh && result.failure !is PutioFailure.AuthenticationRequired) {
                            state.content
                        } else {
                            PublicLinksContent.Failed(result.failure)
                        }
                },
                refreshing = false,
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
            is PutioResult.Failure -> revokeFailed(link, result.failure)
        }

    private fun revokeFailed(link: PublicLink, failure: PutioFailure): PublicLinksMachine {
        val outcome = PublicLinksOutcome.RevokeFailed(link, failure)
        return if (failure is PutioFailure.AuthenticationRequired) {
            copy(state = state.copy(mutation = null, outcome = outcome), request = null)
        } else {
            copy(
                state = state.copy(mutation = null, outcome = outcome, refreshing = true),
                request = PublicLinksRequest.Load(nextRequestId, refresh = true),
                nextRequestId = nextRequestId + 1L,
            )
        }
    }
}

private fun PublicLinksContent.withLinks(change: (List<PublicLink>) -> List<PublicLink>): PublicLinksContent =
    if (this is PublicLinksContent.Ready) PublicLinksContent.Ready(change(links)) else this

private fun List<PublicLink>.newestFirst(): List<PublicLink> =
    distinctBy { it.id }.sortedByDescending { it.id.value }
