package io.putdotio.android.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.putdotio.android.BuildConfig
import io.putdotio.android.playback.DataStoreSwitchedToConvertedFiles
import io.putdotio.android.playback.DirectPlayDownload
import io.putdotio.android.playback.DirectPlayDownloadSource
import io.putdotio.android.playback.DirectPlayDownloads
import io.putdotio.android.playback.MediaCodecDecoderInventory
import io.putdotio.android.playback.OriginalVideoPolicy
import io.putdotio.android.settings.StreamingMethod
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.files.PutioFileType
import java.io.Closeable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow

internal fun mobileDirectPlayDownloads(context: Context, client: PutioClient): DirectPlayDownloads =
    DirectPlayDownloads(
        client,
        originalVideoPolicy = { OriginalVideoPolicy.valueOf(BuildConfig.PUTIO_ORIGINAL_VIDEO) },
        decoders = MediaCodecDecoderInventory(context),
        switchedToConverted = DataStoreSwitchedToConvertedFiles.get(context),
    )

internal val DirectPlayDownload.artifact: DownloadArtifact?
    get() = when (source) {
        DirectPlayDownloadSource.Original -> DownloadArtifact.ORIGINAL
        DirectPlayDownloadSource.Converted -> DownloadArtifact.MP4
        DirectPlayDownloadSource.Unavailable -> null
    }

internal val DirectPlayDownload?.unavailable: Boolean
    get() = this?.source == DirectPlayDownloadSource.Unavailable

/**
 * Standard video is the converted HLS rendition; Direct play video starts as the converted MP4 until its lookup
 * answers, and stays so when the lookup fails. Audio has only the original.
 */
internal fun PutioFileType.downloadArtifact(method: StreamingMethod): DownloadArtifact? =
    when (this) {
        PutioFileType.VIDEO -> if (method == StreamingMethod.File) DownloadArtifact.MP4 else DownloadArtifact.HLS
        PutioFileType.AUDIO -> DownloadArtifact.ORIGINAL
        else -> null
    }

internal val DownloadEntry.keepsArtifact: Boolean
    get() = holdsBytes && !(artifact == DownloadArtifact.ORIGINAL_HLS && status == DownloadStatus.Missing)

internal val DownloadEntry.resolvesDirectPlay: Boolean
    get() = type == PutioFileType.VIDEO && artifact.method == StreamingMethod.File

internal val DownloadEntry.awaitsDirectPlay: Boolean
    get() = status == DownloadStatus.Queued && !accepted && !removing && type == PutioFileType.VIDEO &&
        artifact == DownloadArtifact.MP4

internal fun DownloadEntry.redecidesDirectPlay(failure: DownloadStatus? = status): Boolean =
    directPlayUnanswered && !removing && artifact == DownloadArtifact.MP4 &&
        failure == DIRECT_PLAY_REFUSAL

internal val DIRECT_PLAY_REFUSAL = DownloadStatus.Failed(DownloadFailureReason.UNAVAILABLE, 0L)

internal val DownloadEntry.redecidesOnline: Boolean
    get() = directPlayUnanswered && !removing && artifact == DownloadArtifact.MP4 &&
        status == DownloadStatus.Failed(DownloadFailureReason.NETWORK, 0L)

internal fun validatedNetwork(context: Context): Flow<Boolean> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyFlow()
    return callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

internal class DirectPlayRedeciders {
    private val holders = mutableMapOf<Long, Int>()

    fun hold(userId: Long): Closeable {
        synchronized(holders) { holders[userId] = (holders[userId] ?: 0) + 1 }
        var released = false
        return Closeable {
            synchronized(holders) {
                if (released) return@Closeable
                released = true
                val left = holders.getValue(userId) - 1
                if (left == 0) holders.remove(userId) else holders[userId] = left
            }
        }
    }

    fun holds(userId: Long): Boolean = synchronized(holders) { userId in holders }

    companion object {
        val shared = DirectPlayRedeciders()
    }
}

internal class DownloadStart(val entry: DownloadEntry, val decides: Boolean) {
    val asksDirectPlay: Boolean get() = decides && entry.resolvesDirectPlay
}

internal fun DownloadEntry.retryStart(): DownloadStart =
    if (keepsArtifact) {
        DownloadStart(this, decides = false)
    } else {
        DownloadStart(type.downloadArtifact(artifact.method)?.let { copy(artifact = it) } ?: this, decides = true)
    }

internal fun DownloadEntry.decidedBy(directPlay: DirectPlayDownload?): DownloadEntry {
    val artifact = directPlay?.artifact
    return when {
        directPlay == null -> copy(directPlayUnanswered = true)
        artifact == null -> copy(
            status = DownloadStatus.Failed(DownloadFailureReason.UNAVAILABLE, 0L),
            directPlayUnanswered = false,
        )
        else -> copy(
            artifact = artifact,
            subtitles = directPlay.subtitles.mapNotNull { it.toDownloadSubtitle() },
            directPlayUnanswered = false,
        )
    }
}
