package io.putdotio.android.downloads

import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.confirmedResumePlayback
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
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

/**
 * Serves a completed download without the network. The source URL is the same
 * token-free API URL the download used, read back through [requestedUrl], so the
 * cache data source resolves every playlist and segment from disk; a cache miss
 * still reaches the network with the session header. A row whose bytes turn out to
 * be gone is marked missing by [localCopyAvailable] and streams instead.
 */
internal class OfflinePlaybackRepository(
    private val downloads: StateFlow<DownloadsState>,
    private val delegate: PlaybackRepository,
    private val credentialUrl: (String) -> PutioCredentialUrl,
    private val localCopyAvailable: suspend (FilesItemId) -> Boolean = { true },
    private val resume: OfflineResume = OfflineResume.Off,
    private val requestedUrl: suspend (FilesItemId) -> String? = { null },
) : PlaybackRepository {
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
        val state = downloads.value
        val entry = state.entry(target.fileId)
            ?.takeIf { state.isAvailableOffline(target.fileId) && localCopyAvailable(it.fileId) }
            ?: return delegate.resolve(target)
        val hls = entry.artifact == DownloadArtifact.HLS
        // A download made for a hide_subtitles account holds no subtitle rendition, and a cold
        // offline start cannot read the setting, so the row's own copy of it hides the picker.
        val subtitlesHidden = entry.subtitlesHidden == true
        val resumeEnabled = resume.enabled()
        val source = PlaybackSource(
            fileId = entry.fileId.value,
            kind = if (hls) PlaybackSourceKind.HLS else PlaybackSourceKind.ORIGINAL,
            url = credentialUrl(
                requestedUrl(entry.fileId) ?: entry.artifact.apiUrl(entry.fileId, subtitlesHidden),
            ),
            startFromSeconds = if (resumeEnabled) resume.position(entry) else 0.0,
            // HLS subtitle renditions are inside the downloaded playlist set.
            subtitles = if (hls && !subtitlesHidden) PlaybackSubtitles.Embedded else PlaybackSubtitles.None,
        )
        return PlaybackRepositoryResult.Success(
            PlaybackResolution.Ready(
                source = source,
                useStartFrom = resumeEnabled,
                subtitlesHidden = subtitlesHidden,
                durationSeconds = entry.durationSeconds,
            ),
        )
    }

    override suspend fun startConversion(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> =
        delegate.startConversion(target)

    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = delegate.findNextVideo(target)
}
