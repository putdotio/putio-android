package io.putdotio.android.playback

import io.putdotio.android.PutioFailure
import io.putdotio.android.apiReason
import io.putdotio.android.toPutioFailure
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.loadMediaAccount
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.account.AccountInfo
import io.putdotio.sdk.errors.PutioApiException
import io.putdotio.sdk.errors.PutioConfigurationException
import io.putdotio.sdk.errors.PutioException
import io.putdotio.sdk.errors.PutioOperationErrorReason
import io.putdotio.sdk.errors.PutioOperationException
import io.putdotio.sdk.errors.PutioSerializationException
import io.putdotio.sdk.errors.PutioTransportException
import io.putdotio.sdk.files.FileDetailsQuery
import io.putdotio.sdk.files.FileMp4ConversionStatus
import io.putdotio.sdk.files.FilesContinueQuery
import io.putdotio.sdk.files.FilesListQuery
import io.putdotio.sdk.files.FilesListResponse
import io.putdotio.sdk.files.PutioFile
import io.putdotio.sdk.files.PutioFileType
import io.putdotio.sdk.files.PlaybackConversionState
import io.putdotio.sdk.files.PlaybackMediaCredential
import io.putdotio.sdk.files.PlaybackPreference
import io.putdotio.sdk.files.PlaybackRequest
import io.putdotio.sdk.files.PlaybackSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

public sealed interface PlaybackRepositoryResult<out T> {
    public data class Success<T>(
        val value: T,
    ) : PlaybackRepositoryResult<T>

    public data class Failure(
        val failure: PlaybackFailure,
    ) : PlaybackRepositoryResult<Nothing>
}

public sealed interface PlaybackFailure {
    public val cause: Throwable

    /** A failure in the shared taxonomy: from a put.io request ([toPlaybackFailure]) or from the player. */
    public data class Putio(
        val failure: PutioFailure,
    ) : PlaybackFailure {
        override val cause: Throwable
            get() = failure.cause
    }

    public data class MediaCredentialUnavailable(
        override val cause: Throwable,
    ) : PlaybackFailure

    /** The device cannot decode or parse this media; resolving it again plays nothing. */
    public data class MediaUnsupported(
        override val cause: Throwable,
    ) : PlaybackFailure
}

/** The put.io failure behind this one; null for a media failure only playback explains. */
public val PlaybackFailure.putioFailure: PutioFailure?
    get() = (this as? PlaybackFailure.Putio)?.failure

/** put.io's own reason for a refused request; the surface's copy applies when it is null. */
public val PlaybackFailure.apiReason: String?
    get() = putioFailure?.apiReason

/**
 * Whether trying again can succeed: a network, rate-limit, server, request-timeout or
 * expired-link failure can, a rejected session, a refused or rejected request, or media the
 * device cannot play cannot.
 */
public val PlaybackFailure.retryable: Boolean
    get() = when (this) {
        is PlaybackFailure.MediaCredentialUnavailable -> true
        is PlaybackFailure.MediaUnsupported -> false
        is PlaybackFailure.Putio -> when (failure) {
            is PutioFailure.NetworkUnavailable,
            is PutioFailure.RateLimited,
            is PutioFailure.ServerUnavailable,
            is PutioFailure.InvalidResponse,
            is PutioFailure.Unexpected,
            -> true

            is PutioFailure.ApiRejected -> failure.statusCode == HTTP_REQUEST_TIMEOUT

            is PutioFailure.AuthenticationRequired,
            is PutioFailure.AccessDenied,
            is PutioFailure.Misconfigured,
            -> false
        }
    }

public interface PlaybackRepository {
    public suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution>

    /** Starts converting [target] to MP4, then resolves it again. */
    public suspend fun startConversion(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        PlaybackRepositoryResult.Failure(
            PlaybackFailure.Putio(
                PutioFailure.Unexpected(UnsupportedOperationException("This source cannot start a conversion")),
            ),
        )

    public suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult

    /**
     * The files of [target]'s media type on either side of it in its folder, in name order, and
     * the folder's name. The API has no queue, so audio's skip buttons and Up next read this.
     */
    public suspend fun findNeighbours(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackNeighbours> =
        PlaybackRepositoryResult.Success(PlaybackNeighbours())
}

/** What sits around a playing file in its folder; null where there is nothing, or nothing known. */
public data class PlaybackNeighbours(
    val previous: PlaybackTarget? = null,
    val next: PlaybackTarget? = null,
    val folderName: String? = null,
)

public sealed interface PlaybackNextResult {
    public data class Found(
        val target: PlaybackTarget,
    ) : PlaybackNextResult

    public data object Ended : PlaybackNextResult

    public data class Failure(
        val failure: PlaybackFailure,
    ) : PlaybackNextResult
}

internal class SdkPlaybackRepository internal constructor(
    private val playbackPreference: () -> PlaybackPreference,
    private val loadAccount: suspend () -> AccountInfo,
    private val resolvePlayback: suspend (PlaybackRequest) -> io.putdotio.sdk.files.PlaybackResolution,
    private val loadFile: suspend (Long) -> PutioFile = { error("File lookup is not configured") },
    private val listFolder: suspend (Long, FilesListQuery) -> FilesListResponse = { _, _ ->
        error("Folder listing is not configured")
    },
    private val continueListing: suspend (String, FilesContinueQuery) -> FilesListResponse = { _, _ ->
        error("Folder pagination is not configured")
    },
) : PlaybackRepository {
    constructor(
        client: PutioClient,
        playbackPreference: () -> PlaybackPreference,
    ) : this(
        playbackPreference = playbackPreference,
        loadAccount = client::loadMediaAccount,
        resolvePlayback = client.files::resolvePlayback,
        loadFile = { fileId ->
            client.files.get(
                fileId,
                FileDetailsQuery(mp4Size = false, startFrom = false, streamUrl = false, mp4StreamUrl = false),
            )
        },
        listFolder = client.files::list,
        continueListing = client.files::continueList,
    )

    @Suppress("TooGenericExceptionCaught")
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        try {
            val preference = playbackPreference()
            val account = loadAccount()
            val downloadToken = account.downloadToken
                ?: return PlaybackRepositoryResult.Failure(
                    PlaybackFailure.MediaCredentialUnavailable(MissingPlaybackCredentialException()),
                )
            val hideSubtitles = account.settings.hideSubtitles
            val resolution = resolvePlayback(
                PlaybackRequest(
                    fileId = target.fileId.value,
                    mediaCredential = PlaybackMediaCredential.downloadToken(downloadToken),
                    preference = preference,
                    useStartFrom = account.settings.useStartFrom,
                    // hide_subtitles asks for no subtitles at all, as every reference player does.
                    includeSidecarSubtitles = !hideSubtitles,
                    maxSubtitleCount = if (hideSubtitles) 0 else null,
                ),
            )
            val ready = resolution.toAppResolution(account.settings.useStartFrom, hideSubtitles)
            PlaybackRepositoryResult.Success(
                if (ready is PlaybackResolution.Ready && target.needsDuration()) {
                    ready.copy(durationSeconds = videoDuration(target.fileId.value))
                } else {
                    ready
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            PlaybackRepositoryResult.Failure(error.toPlaybackFailure())
        } catch (unexpected: Exception) {
            PlaybackRepositoryResult.Failure(PlaybackFailure.Putio(PutioFailure.Unexpected(unexpected)))
        }

    // The near-end rule needs a video's duration, and so does Continue watching, even for a video never started.
    private fun PlaybackTarget.needsDuration(): Boolean =
        durationSeconds == null && mediaType == PlaybackMediaType.VIDEO

    /**
     * Single-file reads omit `video_metadata`; listing the file returns it as the parent. A
     * failed lookup leaves the duration unknown, so the saved position is offered as before;
     * an optional read must not fail the playback it only refines.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun videoDuration(fileId: Long): Double? =
        try {
            listFolder(fileId, FilesListQuery(perPage = 1)).parent
                ?.takeIf { it.id == fileId }
                ?.videoMetadata?.duration?.takeIf { it.isFinite() && it > 0.0 }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult =
        try {
            val current = currentFile(target)
            if (current == null) {
                PlaybackNextResult.Ended
            } else {
                findNextInFolder(target, requireNotNull(current.parentId))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            PlaybackNextResult.Failure(error.toPlaybackFailure())
        } catch (unexpected: Exception) {
            PlaybackNextResult.Failure(PlaybackFailure.Putio(PutioFailure.Unexpected(unexpected)))
        }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun findNeighbours(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackNeighbours> =
        try {
            val parentId = currentFile(target)?.parentId
            PlaybackRepositoryResult.Success(parentId?.let { neighboursInFolder(target, it) } ?: PlaybackNeighbours())
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            PlaybackRepositoryResult.Failure(error.toPlaybackFailure())
        } catch (unexpected: Exception) {
            PlaybackRepositoryResult.Failure(PlaybackFailure.Putio(PutioFailure.Unexpected(unexpected)))
        }

    /** The playing file as put.io holds it now; null once it is gone. */
    private suspend fun currentFile(target: PlaybackTarget): PutioFile? =
        try {
            loadFile(target.fileId.value)
        } catch (error: PutioException) {
            if (!error.hasHttpStatusCode(HTTP_NOT_FOUND)) throw error
            null
        }

    private suspend fun findNextInFolder(target: PlaybackTarget, parentId: Long): PlaybackNextResult {
        var foundCurrent = false
        var next: PutioFile? = null
        walkFolder(parentId, PlaybackMediaType.VIDEO) { file ->
            if (file.id == target.fileId.value) {
                foundCurrent = true
            } else if (foundCurrent) {
                next = file
            }
            next != null
        }
        return next?.let { PlaybackNextResult.Found(it.toPlaybackTarget(PlaybackMediaType.VIDEO)) }
            ?: PlaybackNextResult.Ended
    }

    private suspend fun neighboursInFolder(target: PlaybackTarget, parentId: Long): PlaybackNeighbours {
        var previous: PutioFile? = null
        var foundCurrent = false
        var next: PutioFile? = null
        val folder = walkFolder(parentId, target.mediaType) { file ->
            when {
                file.id == target.fileId.value -> foundCurrent = true
                foundCurrent -> next = file
                else -> previous = file
            }
            next != null
        }
        // A file the listing no longer holds has no place in it, so nothing sits on either side.
        return PlaybackNeighbours(
            previous = previous?.takeIf { foundCurrent }?.toPlaybackTarget(target.mediaType),
            next = next?.toPlaybackTarget(target.mediaType),
            folderName = folder?.name,
        )
    }

    /**
     * Walks [parentId]'s files of [mediaType] page by page until [visit] returns true or the
     * folder ends, and returns the folder as its first page names it. The next-file endpoint can
     * cross folders and fall back to a random account video, so this walks the server's explicit
     * name ordering instead, stopping at this folder's end.
     */
    private suspend fun walkFolder(
        parentId: Long,
        mediaType: PlaybackMediaType,
        visit: (PutioFile) -> Boolean,
    ): PutioFile? {
        val fileType = mediaType.fileType()
        var page = listFolder(
            parentId,
            // Video metadata carries the duration TV needs to offer resume for the next video.
            FilesListQuery(
                perPage = AUTOPLAY_PAGE_SIZE,
                fileType = fileType,
                sortBy = "NAME_ASC",
                videoMetadata = mediaType == PlaybackMediaType.VIDEO,
            ),
        )
        val folder = page.parent?.takeIf { it.id == parentId }
        val cursors = mutableSetOf<String>()
        val seenFileIds = mutableSetOf<Long>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val cursor = page.cursor?.takeIf(String::isNotBlank)
            check(cursor == null || cursors.add(cursor)) { "Folder listing repeated its cursor" }
            for (file in page.files) {
                if (file.fileType != fileType ||
                    file.parentId != parentId ||
                    !seenFileIds.add(file.id)
                ) continue
                if (visit(file)) return folder
            }
            if (cursor == null) break
            page = continueListing(cursor, FilesContinueQuery(perPage = AUTOPLAY_PAGE_SIZE))
        }
        return folder
    }
}

private fun PlaybackMediaType.fileType(): PutioFileType =
    when (this) {
        PlaybackMediaType.VIDEO -> PutioFileType.VIDEO
        PlaybackMediaType.AUDIO -> PutioFileType.AUDIO
    }

private fun PutioFile.toPlaybackTarget(mediaType: PlaybackMediaType): PlaybackTarget =
    PlaybackTarget(
        fileId = FilesItemId(id),
        name = name,
        mediaType = mediaType,
        durationSeconds = videoMetadata?.duration?.takeIf { it.isFinite() && it > 0.0 },
        posterUrl = screenshot?.takeIf(String::isNotBlank),
    )

/**
 * [delegate] plus the SDK's MP4 conversion start. The player starts one when a video it opens
 * reads not available, and when the viewer converts again after a failure (see
 * [PlaybackContent.Conversion.starting]); the resolver itself never does (putio-sdk-kotlin
 * `docs/ARCHITECTURE.md`, conversion handling).
 */
public class ConvertingPlaybackRepository internal constructor(
    private val delegate: PlaybackRepository,
    /** Starts the conversion; true when the server accepted it (any status but not available). */
    private val startMp4Conversion: suspend (Long) -> Boolean,
) : PlaybackRepository by delegate {
    public constructor(
        client: PutioClient,
        playbackPreference: () -> PlaybackPreference,
    ) : this(
        delegate = SdkPlaybackRepository(client, playbackPreference),
        startMp4Conversion = { fileId ->
            client.files.startMp4Conversion(fileId).status != FileMp4ConversionStatus.NOT_AVAILABLE
        },
    )

    @Suppress("TooGenericExceptionCaught")
    override suspend fun startConversion(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        try {
            val accepted = startMp4Conversion(target.fileId.value)
            val resolved = delegate.resolve(target)
            val read = (resolved as? PlaybackRepositoryResult.Success)?.value as? PlaybackResolution.Conversion
            val stillNotAvailable = read?.state == PlaybackConversionState.NotAvailable
            // A status read that has not caught up with an accepted start polls once more before
            // it is final.
            if (accepted && stillNotAvailable) {
                PlaybackRepositoryResult.Success(PlaybackResolution.Conversion(PlaybackConversionState.Queued))
            } else {
                resolved
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PutioException) {
            PlaybackRepositoryResult.Failure(error.toPlaybackFailure())
        } catch (unexpected: Exception) {
            PlaybackRepositoryResult.Failure(PlaybackFailure.Putio(PutioFailure.Unexpected(unexpected)))
        }
}

private const val AUTOPLAY_PAGE_SIZE = 200

internal class MissingPlaybackCredentialException : IllegalStateException("Playback credential is unavailable")

private fun io.putdotio.sdk.files.PlaybackResolution.toAppResolution(
    useStartFrom: Boolean,
    subtitlesHidden: Boolean,
): PlaybackResolution =
    when (this) {
        is io.putdotio.sdk.files.PlaybackResolution.Ready ->
            PlaybackResolution.Ready(source, useStartFrom, subtitlesHidden)
        is io.putdotio.sdk.files.PlaybackResolution.Conversion -> PlaybackResolution.Conversion(state)
        is io.putdotio.sdk.files.PlaybackResolution.Unsupported -> PlaybackResolution.Unsupported(fileType)
    }

public sealed interface PlaybackResolution {
    public data class Ready(
        val source: PlaybackSource,
        val useStartFrom: Boolean = false,
        /** Resolved for a `hide_subtitles` account, whose settings the player may not have yet. */
        val subtitlesHidden: Boolean = false,
        /** The media's duration when the target did not carry one; see [PlaybackTarget.durationSeconds]. */
        val durationSeconds: Double? = null,
    ) : PlaybackResolution

    public data class Conversion(
        val state: PlaybackConversionState,
    ) : PlaybackResolution

    public data class Unsupported(
        val fileType: PutioFileType,
    ) : PlaybackResolution
}

/**
 * Playback's reading of a put.io error, unlike [toPutioFailure]: an API error under the SDK's
 * operation wrappers outranks a wrapper's contract status, and the HTTP status, not the
 * envelope's, classifies it.
 */
public fun PutioException.toPlaybackFailure(): PlaybackFailure {
    var current: PutioException = this
    val visited = mutableSetOf<PutioException>()
    val wrappers = mutableListOf<PutioOperationException>()
    while (current is PutioOperationException && visited.add(current)) {
        wrappers += current
        current = current.underlyingError
    }
    val failure = if (current is PutioApiException) {
        current.leafFailure(context = this)
    } else {
        wrappers.firstNotNullOfOrNull { it.reasonFailure(context = this) }
            ?: current.leafFailure(context = this)
    }
    return PlaybackFailure.Putio(failure)
}

private fun PutioException.hasHttpStatusCode(statusCode: Int): Boolean {
    var current: PutioException = this
    val visited = mutableSetOf<PutioException>()
    while (current is PutioOperationException && visited.add(current)) {
        current = current.underlyingError
    }
    return (current as? PutioApiException)?.httpStatusCode == statusCode
}

private fun PutioOperationException.reasonFailure(context: PutioException): PutioFailure? =
    when ((reason as? PutioOperationErrorReason.StatusCode)?.statusCode) {
        HTTP_UNAUTHORIZED -> PutioFailure.AuthenticationRequired(context)
        HTTP_FORBIDDEN -> PutioFailure.AccessDenied(context)
        else -> null
    }

private fun PutioException.leafFailure(context: PutioException): PutioFailure =
    when (this) {
        is PutioApiException ->
            when (httpStatusCode) {
                HTTP_UNAUTHORIZED -> PutioFailure.AuthenticationRequired(context)
                HTTP_FORBIDDEN -> PutioFailure.AccessDenied(context)
                HTTP_TOO_MANY_REQUESTS -> PutioFailure.RateLimited(context)
                in HTTP_SERVER_ERROR_RANGE -> PutioFailure.ServerUnavailable(httpStatusCode, context)
                else -> PutioFailure.ApiRejected(httpStatusCode, errorType, context)
            }

        is PutioTransportException -> PutioFailure.NetworkUnavailable(context)
        is PutioSerializationException -> PutioFailure.InvalidResponse(context)
        is PutioConfigurationException -> PutioFailure.Misconfigured(context)
        is PutioOperationException -> PutioFailure.Unexpected(context)
    }

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_SERVER_ERROR_RANGE = HTTP_SERVER_ERROR_START..HTTP_SERVER_ERROR_END
private const val HTTP_SERVER_ERROR_START = 500
private const val HTTP_SERVER_ERROR_END = 599
