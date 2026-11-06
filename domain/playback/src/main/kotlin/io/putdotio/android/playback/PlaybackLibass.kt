package io.putdotio.android.playback

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.AssTrack
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.parser.AssSegmentSubtitleParser
import io.github.peerless2012.ass.media.type.AssRenderType

public class LibassSubtitles internal constructor(internal val cacheMegabytes: Int)

public fun libassSubtitles(
    context: Context,
    mediaType: PlaybackMediaType,
    cacheMegabytes: Int,
): LibassSubtitles? =
    LibassSubtitles(cacheMegabytes).takeIf { context.affordsLibass() && mediaType == PlaybackMediaType.VIDEO }

private fun Context.affordsLibass(): Boolean {
    val activityManager = getSystemService(ActivityManager::class.java)
    return rendersAssWithLibass(
        is64Bit = Process.is64Bit(),
        isLowRam = activityManager.isLowRamDevice,
        memoryClassMb = activityManager.memoryClass,
    )
}

internal fun rendersAssWithLibass(
    is64Bit: Boolean,
    isLowRam: Boolean,
    memoryClassMb: Int,
): Boolean = is64Bit && !isLowRam && memoryClassMb >= LIBASS_MIN_HEAP_MB

internal fun Context.drawsAssWithLibass(): Boolean = drawsAssWithLibass(SubtitleAppearanceStore.storedTextSize(this))

private fun Context.drawsAssWithLibass(size: SubtitleTextSize): Boolean = size.scale * captionFontScale() == 1f

public fun Player.redrawAss(context: Context, from: SubtitleTextSize, to: SubtitleTextSize) {
    val libassChoiceChanged = context.drawsAssWithLibass(from) != context.drawsAssWithLibass(to)
    if (!libassChoiceChanged || !playsEmbeddedAss() || !context.affordsLibass()) return
    stop()
    prepare()
}

private fun Player.playsEmbeddedAss(): Boolean =
    currentTracks.groups.any { group ->
        group.type == C.TRACK_TYPE_TEXT && group.isSelected && (0 until group.length).any { index ->
            val format = group.getTrackFormat(index)
            format.containerMimeType == MimeTypes.VIDEO_MATROSKA &&
                (format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA)
        }
    }

@UnstableApi
public fun playbackExoPlayer(
    context: Context,
    libass: LibassSubtitles?,
    dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context),
    configure: ExoPlayer.Builder.() -> ExoPlayer.Builder = { this },
): ExoPlayer {
    val builder = ExoPlayer.Builder(context).configure()
    if (libass == null) {
        return builder
            .setRenderersFactory(playbackRenderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }
    val handler = AssHandler(AssRenderType.CUES, AssHandlerConfig(cacheSize = libass.cacheMegabytes))
    val appContext = context.applicationContext
    val parsers = MatroskaAssSubtitleParserFactory(handler, appContext::drawsAssWithLibass)
    val extractors = AssExtractorsFactory(parsers, handler, appContext::drawsAssWithLibass)
    val player = builder
        .setRenderersFactory(AssRenderersFactory(handler, playbackRenderersFactory(context)))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory, extractors)
                .setSubtitleParserFactory(parsers),
        )
        .build()
    player.addListener(
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                handler.render?.release()
                if (parsers.releaseTracks()) handler.ass.clearFont()
            }
        },
    )
    handler.init(player)
    player.addAnalyticsListener(
        object : AnalyticsListener {
            override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
                parsers.releaseTracks()
                handler.release()
            }
        },
    )
    return player
}

@UnstableApi
private class AssExtractorsFactory(
    private val parsers: SubtitleParser.Factory,
    private val handler: AssHandler,
    private val drawsWithLibass: () -> Boolean,
) : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> = withAss(defaults.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        withAss(defaults.createExtractors(uri, responseHeaders))

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        defaults.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    private fun withAss(extractors: Array<Extractor>): Array<Extractor> =
        extractors.map {
            if (it is MatroskaExtractor) AssFontsMatroskaExtractor(parsers, handler, drawsWithLibass) else it
        }.toTypedArray()
}

@UnstableApi
private class AssFontsMatroskaExtractor(
    parsers: SubtitleParser.Factory,
    handler: AssHandler,
    private val drawsWithLibass: () -> Boolean,
) : AssMatroskaExtractor(parsers, handler, 0) {
    private var hasAssTrack = false
    private var attachmentName: String? = null
    private var attachmentMime: String? = null

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        if (id == ID_ATTACHED_FILE) forgetAttachment()
        super.startMasterElement(id, contentPosition, contentSize)
    }

    override fun endMasterElement(id: Int) {
        if (id == ID_ATTACHED_FILE) forgetAttachment()
        super.endMasterElement(id)
    }

    override fun stringElement(id: Int, value: String) {
        when (id) {
            ID_CODEC_ID -> hasAssTrack = hasAssTrack || value in ASS_CODEC_IDS
            ID_FILE_NAME -> attachmentName = value
            ID_FILE_MIME_TYPE -> attachmentMime = value
        }
        super.stringElement(id, value)
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        val usableFont = attachmentName != null && attachmentMime in fontMimeTypes && hasAssTrack &&
            drawsWithLibass()
        if (id == ID_FILE_DATA && !usableFont) {
            input.skipFully(contentSize)
        } else {
            super.binaryElement(id, contentSize, input)
        }
    }

    private fun forgetAttachment() {
        attachmentName = null
        attachmentMime = null
    }
}

@UnstableApi
internal class MatroskaAssSubtitleParserFactory(
    private val handler: AssHandler,
    private val drawsWithLibass: () -> Boolean,
) : SubtitleParser.Factory {
    private val media3 = DefaultSubtitleParserFactory()
    private val tracks = mutableListOf<AssTrack>()

    override fun supportsFormat(format: Format): Boolean = media3.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = media3.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val embeddedAss = format.containerMimeType == MimeTypes.VIDEO_MATROSKA &&
            format.sampleMimeType == MimeTypes.TEXT_SSA
        if (!embeddedAss || !drawsWithLibass()) return media3.create(format)
        val track = handler.createTrack(format)
        synchronized(tracks) { tracks += track }
        return AssSegmentSubtitleParser(handler, track)
    }

    fun releaseTracks(): Boolean {
        val released = synchronized(tracks) { tracks.toList().also { tracks.clear() } }
        released.forEach(AssTrack::release)
        return released.isNotEmpty()
    }
}

private const val LIBASS_MIN_HEAP_MB = 256
private const val ID_CODEC_ID = 0x86
private val ASS_CODEC_IDS = setOf("S_TEXT/ASS", "S_TEXT/SSA")
