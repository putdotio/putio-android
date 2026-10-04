package io.putdotio.android.playback

import io.putdotio.android.files.FilesItemId

/** A playback state as the reducer would hold it, for tests outside this module. */
public fun playbackState(
    target: PlaybackTarget,
    content: PlaybackContent,
    nextRequestValue: Long,
    resumePositionMillis: Long? = null,
    visitedFileIds: Set<FilesItemId> = setOf(target.fileId),
): PlaybackState = PlaybackState(target, content, nextRequestValue, resumePositionMillis, visitedFileIds)

/** [copy] for tests outside this module; request ids keep counting from this state. */
public fun PlaybackState.copyForTest(
    target: PlaybackTarget = this.target,
    content: PlaybackContent = this.content,
    resumePositionMillis: Long? = this.resumePositionMillis,
    visitedFileIds: Set<FilesItemId> = this.visitedFileIds,
): PlaybackState =
    copy(
        target = target,
        content = content,
        resumePositionMillis = resumePositionMillis,
        visitedFileIds = visitedFileIds,
    )
