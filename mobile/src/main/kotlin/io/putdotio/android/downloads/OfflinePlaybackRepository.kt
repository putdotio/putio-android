package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackCastMedia
import io.putdotio.android.playback.PlaybackMediaType
import io.putdotio.android.playback.PlaybackNeighbours
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.android.settings.confirmedResumePlayback
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackStreamFormat
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.flow.StateFlow

/** Where offline playback resumes, from settings and positions this device kept. */
internal interface OfflineResume {
    /** The confirmed `use_start_from`, else the one this device last confirmed, else off. */
    fun enabled(): Boolean

    suspend fun position(entry: DownloadEntry): Double

    companion object {
        val Off: OfflineResume = object : OfflineResume {
            override fun enabled(): Boolean = false

            override suspend fun position(entry: DownloadEntry): Double = 0.0
        }
    }
}

/** The app's [OfflineResume]: this session's confirmed setting first, then what the device kept. */
internal class OfflinePositionsResume(
    private val positions: OfflinePlaybackPositions,
    private val userId: Long,
    private val settings: StateFlow<AccountSettingsState>,
) : OfflineResume {
    override fun enabled(): Boolean =
        settings.value.confirmedResumePlayback() ?: positions.store(userId).resumeSetting ?: false

    override suspend fun position(entry: DownloadEntry): Double = positions.resumePosition(userId, entry)
}

internal data class OfflineRequest(
    val url: String,
    val subtitles: List<DownloadSubtitle> = emptyList(),
)

private fun DownloadEntry.servesNoConvertedStreamFor(target: PlaybackTarget): Boolean =
    target.mediaType == PlaybackMediaType.VIDEO &&
        (artifact.isOriginal || target.method == StreamingMethod.Hls && !artifact.isHls)

private fun DownloadEntry.servesAnotherMethodThan(target: PlaybackTarget): Boolean =
    target.mediaType == PlaybackMediaType.VIDEO && target.method != null && target.method != artifact.method

/**
 * Serves a completed download without the network. The source URL is the same
 * token-free API URL the download used, read back through [requested], so the
 * cache data source resolves every playlist, segment and file from disk; a cache miss
 * still reaches the network with the session header. A Direct play download offers the
 * subtitle files [requested] finds on disk as sidecars, under the same token-free URLs.
 * A row whose bytes turn out to be gone is marked missing by [localCopyAvailable] and streams instead.
 */
internal class OfflinePlaybackRepository(
    private val downloads: StateFlow<DownloadsState>,
    private val delegate: PlaybackRepository,
    private val credentialUrl: (String) -> PutioCredentialUrl,
    private val localCopyAvailable: suspend (FilesItemId) -> Boolean = { true },
    private val resume: OfflineResume = OfflineResume.Off,
    private val requested: suspend (FilesItemId) -> OfflineRequest? = { null },
) : PlaybackRepository {
    override suspend fun resolve(
        target: PlaybackTarget,
        convertedOnly: Boolean,
    ): PlaybackRepositoryResult<PlaybackResolution> =
        downloadFor(target, convertedOnly)?.let { serve(it) } ?: delegate.resolve(target, convertedOnly)

    private suspend fun downloadFor(target: PlaybackTarget, convertedOnly: Boolean): DownloadEntry? {
        val state = downloads.value
        return state.entry(target.fileId)
            ?.takeIf { state.isAvailableOffline(target.fileId) && localCopyAvailable(it.fileId) }
            ?.takeUnless {
                if (convertedOnly) it.servesNoConvertedStreamFor(target) else it.servesAnotherMethodThan(target)
            }
    }

    private suspend fun serve(entry: DownloadEntry): PlaybackRepositoryResult<PlaybackResolution> {
        val hls = entry.artifact.isHls
        // A download made for a hide_subtitles account holds no subtitle rendition, and a cold
        // offline start cannot read the setting, so the row's own copy of it hides the picker.
        val subtitlesHidden = entry.subtitlesHidden == true
        val resumeEnabled = resume.enabled()
        val request = requested(entry.fileId)
        val sidecars = if (hls || subtitlesHidden) emptyList() else request?.subtitles.orEmpty()
        val source = PlaybackSource(
            fileId = entry.fileId.value,
            kind = entry.artifact.sourceKind,
            url = credentialUrl(request?.url ?: entry.artifact.apiUrl(entry.fileId, subtitlesHidden)),
            startFromSeconds = if (resumeEnabled) resume.position(entry) else 0.0,
            // HLS subtitle renditions are inside the downloaded playlist set.
            subtitles = when {
                hls && !subtitlesHidden -> PlaybackSubtitles.Embedded
                sidecars.isEmpty() -> PlaybackSubtitles.None
                else -> PlaybackSubtitles.Sidecar(sidecars.map { it.toPlaybackSubtitle(credentialUrl) })
            },
            format = if (hls) PlaybackStreamFormat.HLS else PlaybackStreamFormat.PROGRESSIVE,
        )
        return PlaybackRepositoryResult.Success(
            PlaybackResolution.Ready(
                source = source,
                useStartFrom = resumeEnabled,
                subtitlesHidden = subtitlesHidden,
                durationSeconds = entry.durationSeconds,
                offline = true,
            ),
        )
    }

    override suspend fun startConversion(
        target: PlaybackTarget,
        convertedOnly: Boolean,
    ): PlaybackRepositoryResult<PlaybackResolution> = delegate.startConversion(target, convertedOnly)

    // A receiver streams a downloaded file from put.io: the token-free API URL gets the download token.
    override suspend fun castMedia(
        target: PlaybackTarget,
        source: PlaybackSource,
        activeSubtitleKey: String?,
    ): PlaybackRepositoryResult<PlaybackCastMedia> = delegate.castMedia(target, source, activeSubtitleKey)

    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = delegate.findNextVideo(target)

    override fun rememberSwitchedToConverted(target: PlaybackTarget): Unit =
        delegate.rememberSwitchedToConverted(target)

    override fun forgetSwitchedToConverted(target: PlaybackTarget): Unit =
        delegate.forgetSwitchedToConverted(target)

    override suspend fun findNeighbours(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackNeighbours> =
        delegate.findNeighbours(target)
}

private val DownloadArtifact.isOriginal: Boolean
    get() = this == DownloadArtifact.ORIGINAL || this == DownloadArtifact.ORIGINAL_HLS

private val DownloadArtifact.sourceKind: PlaybackSourceKind
    get() = when (this) {
        DownloadArtifact.HLS -> PlaybackSourceKind.HLS
        DownloadArtifact.MP4 -> PlaybackSourceKind.MP4
        DownloadArtifact.ORIGINAL, DownloadArtifact.ORIGINAL_HLS -> PlaybackSourceKind.ORIGINAL
    }
