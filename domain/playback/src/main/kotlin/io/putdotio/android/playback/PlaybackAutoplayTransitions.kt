package io.putdotio.android.playback

internal fun PlaybackState.playerEnded(): PlaybackTransition {
    // Autoplay-next is a video contract; audio ends where it ends.
    if (content !is PlaybackContent.Ready || target.mediaType != PlaybackMediaType.VIDEO) {
        return PlaybackTransition(this, consumed = false)
    }
    val requestId = PlaybackRequestId(nextRequestValue)
    return PlaybackTransition(
        state =
            copy(
                content = PlaybackContent.FindingNext(requestId),
                nextRequestValue = nextRequestValue + 1,
                resumePositionMillis = null,
            ),
        effect = PlaybackEffect.FindNext(target, requestId),
    )
}

internal fun PlaybackState.nextChosen(event: PlaybackEvent.NextChosen): PlaybackTransition {
    val another = event.target.mediaType == target.mediaType && event.target.fileId != target.fileId
    return if (content is PlaybackContent.Ready && another) {
        resolveNext(event.target, startMillis = 0L.takeIf { event.target.mediaType == PlaybackMediaType.AUDIO })
    } else {
        PlaybackTransition(this, consumed = false)
    }
}

/** What the folder's next video means for this state: found, chosen, none, or a failed lookup. */
internal fun PlaybackState.nextVideo(event: PlaybackEvent): PlaybackTransition =
    when (event) {
        is PlaybackEvent.NextChosen -> nextChosen(event)
        is PlaybackEvent.NextFound -> nextFound(event)
        is PlaybackEvent.NextEnded -> nextEnded(event)
        is PlaybackEvent.NextFailed -> nextFailed(event)
        else -> PlaybackTransition(this, consumed = false)
    }

internal fun PlaybackState.nextFound(event: PlaybackEvent.NextFound): PlaybackTransition =
    when {
        !isFindingNext(event.requestId) -> PlaybackTransition(this, consumed = false)
        event.target.fileId in visitedFileIds -> PlaybackTransition(copy(content = PlaybackContent.Ended))
        else -> resolveNext(event.target)
    }

internal fun PlaybackState.nextEnded(event: PlaybackEvent.NextEnded): PlaybackTransition {
    if (!isFindingNext(event.requestId)) {
        return PlaybackTransition(this, consumed = false)
    }
    return PlaybackTransition(copy(content = PlaybackContent.Ended))
}

internal fun PlaybackState.nextFailed(event: PlaybackEvent.NextFailed): PlaybackTransition {
    if (!isFindingNext(event.requestId)) {
        return PlaybackTransition(this, consumed = false)
    }
    return PlaybackTransition(copy(content = PlaybackContent.NextFailed(event.failure)))
}

private fun PlaybackState.resolveNext(nextTarget: PlaybackTarget, startMillis: Long? = null): PlaybackTransition {
    val resolveRequestId = PlaybackRequestId(nextRequestValue)
    return PlaybackTransition(
        state =
            copy(
                target = nextTarget,
                content = PlaybackContent.Loading(resolveRequestId),
                nextRequestValue = nextRequestValue + 1,
                resumePositionMillis = startMillis,
                visitedFileIds = visitedFileIds + nextTarget.fileId,
                conversionStatusRead = false,
            ),
        effect = PlaybackEffect.Resolve(nextTarget, resolveRequestId),
    )
}

internal fun PlaybackState.isFindingNext(requestId: PlaybackRequestId): Boolean =
    (content as? PlaybackContent.FindingNext)?.requestId == requestId
