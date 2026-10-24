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

/** Next episode plays a playing video's next one at once, even one this opening already played. */
internal fun PlaybackState.nextChosen(event: PlaybackEvent.NextChosen): PlaybackTransition =
    if (content !is PlaybackContent.Ready ||
        target.mediaType != PlaybackMediaType.VIDEO ||
        event.target.mediaType != PlaybackMediaType.VIDEO ||
        event.target.fileId == target.fileId
    ) {
        PlaybackTransition(this, consumed = false)
    } else {
        resolveNext(event.target)
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

private fun PlaybackState.resolveNext(nextTarget: PlaybackTarget): PlaybackTransition {
    val resolveRequestId = PlaybackRequestId(nextRequestValue)
    return PlaybackTransition(
        state =
            copy(
                target = nextTarget,
                content = PlaybackContent.Loading(resolveRequestId),
                nextRequestValue = nextRequestValue + 1,
                resumePositionMillis = null,
                visitedFileIds = visitedFileIds + nextTarget.fileId,
                conversionStatusRead = false,
            ),
        effect = PlaybackEffect.Resolve(nextTarget, resolveRequestId),
    )
}

internal fun PlaybackState.isFindingNext(requestId: PlaybackRequestId): Boolean =
    (content as? PlaybackContent.FindingNext)?.requestId == requestId
