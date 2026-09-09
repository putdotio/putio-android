package io.putdotio.android.downloads

import io.putdotio.android.playback.PlaybackNextResult
import io.putdotio.android.playback.PlaybackRepository
import io.putdotio.android.playback.PlaybackRepositoryResult
import io.putdotio.android.playback.PlaybackResolution
import io.putdotio.android.playback.PlaybackTarget
import io.putdotio.sdk.files.PlaybackSource
import io.putdotio.sdk.files.PlaybackSourceKind
import io.putdotio.sdk.files.PlaybackSubtitles
import io.putdotio.sdk.files.PutioCredentialUrl
import kotlinx.coroutines.flow.StateFlow

/**
 * Serves a completed download without the network. The source URL is the same
 * token-free API URL the download used, so the cache data source resolves every
 * playlist and segment from disk; a cache miss still reaches the network with
 * the session header.
 */
internal class OfflinePlaybackRepository(
    private val downloads: StateFlow<DownloadsState>,
    private val delegate: PlaybackRepository,
    private val credentialUrl: (String) -> PutioCredentialUrl,
) : PlaybackRepository {
    override suspend fun resolve(target: PlaybackTarget): PlaybackRepositoryResult<PlaybackResolution> {
        val entry = downloads.value.entry(target.fileId)?.takeIf { it.isCompleted }
            ?: return delegate.resolve(target)
        val source = PlaybackSource(
            fileId = entry.fileId.value,
            kind = if (entry.artifact == DownloadArtifact.HLS) PlaybackSourceKind.HLS else PlaybackSourceKind.ORIGINAL,
            url = credentialUrl(entry.artifact.apiUrl(entry.fileId)),
            startFromSeconds = 0.0,
            // HLS subtitle renditions are inside the downloaded playlist set.
            subtitles = if (entry.artifact == DownloadArtifact.HLS) {
                PlaybackSubtitles.Embedded
            } else {
                PlaybackSubtitles.None
            },
        )
        return PlaybackRepositoryResult.Success(PlaybackResolution.Ready(source, useStartFrom = false))
    }

    override suspend fun findNextVideo(target: PlaybackTarget): PlaybackNextResult = delegate.findNextVideo(target)
}
