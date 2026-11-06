package io.putdotio.android.playback

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType

public class LibassSubtitles internal constructor(internal val cacheMegabytes: Int)

public fun libassSubtitles(
    context: Context,
    mediaType: PlaybackMediaType,
    cacheMegabytes: Int,
): LibassSubtitles? {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val affordable = rendersAssWithLibass(
        is64Bit = Process.is64Bit(),
        isLowRam = activityManager.isLowRamDevice,
        memoryClassMb = activityManager.memoryClass,
    )
    return LibassSubtitles(cacheMegabytes).takeIf { affordable && mediaType == PlaybackMediaType.VIDEO }
}

internal fun rendersAssWithLibass(
    is64Bit: Boolean,
    isLowRam: Boolean,
    memoryClassMb: Int,
): Boolean = is64Bit && !isLowRam && memoryClassMb >= LIBASS_MIN_HEAP_MB

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
    val parsers = TimedSubtitleParserFactory(AssSubtitleParserFactory(handler))
    val player = builder
        .setRenderersFactory(AssRenderersFactory(handler, playbackRenderersFactory(context)))
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory, AssExtractorsFactory(parsers, handler))
                .setSubtitleParserFactory(parsers),
        )
        .build()
    player.addListener(
        object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                handler.render?.release()
            }
        },
    )
    handler.init(player)
    player.addAnalyticsListener(
        object : AnalyticsListener {
            override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) = handler.release()
        },
    )
    return player
}

@UnstableApi
internal fun ExoPlayer.usesLibass(): Boolean =
    (0 until rendererCount).any { getRenderer(it).name == ASS_RENDERER_NAME }

@UnstableApi
private class AssExtractorsFactory(
    private val parsers: SubtitleParser.Factory,
    private val handler: AssHandler,
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
        extractors.map { if (it is MatroskaExtractor) AssMatroskaExtractor(parsers, handler, 0) else it }.toTypedArray()
}

@UnstableApi
internal class TimedSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory,
) : SubtitleParser.Factory by delegate {
    override fun create(format: Format): SubtitleParser {
        val startedAt = SystemClock.elapsedRealtime()
        val parser = delegate.create(format)
        logTiming { "create ${format.codecs ?: format.sampleMimeType} ${SystemClock.elapsedRealtime() - startedAt} ms" }
        return TimedSubtitleParser(parser)
    }
}

@UnstableApi
private class TimedSubtitleParser(
    private val delegate: SubtitleParser,
) : SubtitleParser by delegate {
    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        delegate.parse(data, offset, length, outputOptions, output)
        logTiming { "parse $length B ${SystemClock.elapsedRealtime() - startedAt} ms" }
    }

    override fun reset() = delegate.reset()
}

private inline fun logTiming(message: () -> String) {
    if (Log.isLoggable(LIBASS_TIMING_TAG, Log.DEBUG)) Log.d(LIBASS_TIMING_TAG, message())
}

private const val LIBASS_TIMING_TAG = "PlaybackLibass"
private const val ASS_RENDERER_NAME = "AssRenderer"
private const val LIBASS_MIN_HEAP_MB = 256
