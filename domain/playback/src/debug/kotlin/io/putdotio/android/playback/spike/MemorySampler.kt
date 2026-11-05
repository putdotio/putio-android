package io.putdotio.android.playback.spike

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.os.Debug
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal class MemorySampler(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var peakNativeHeapKb = 0L
    private var peakJavaHeapKb = 0L
    private var peakTotalPssKb = 0L
    private var peakGraphicsKb = 0L
    private var peakNativeAllocatedKb = 0L
    private var samples = 0

    fun start() {
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                sample()
                delay(SAMPLE_MILLIS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        sample()
    }

    @Synchronized
    private fun sample() {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        peakNativeHeapKb = maxOf(peakNativeHeapKb, info.stat("summary.native-heap"))
        peakJavaHeapKb = maxOf(peakJavaHeapKb, info.stat("summary.java-heap"))
        peakTotalPssKb = maxOf(peakTotalPssKb, info.stat("summary.total-pss"))
        peakGraphicsKb = maxOf(peakGraphicsKb, info.stat("summary.graphics"))
        peakNativeAllocatedKb = maxOf(peakNativeAllocatedKb, Debug.getNativeHeapAllocatedSize() / KIB)
        samples++
    }

    @Synchronized
    fun report(into: JSONObject) {
        into.put(
            "memoryPeakKb",
            JSONObject()
                .put("nativeHeap", peakNativeHeapKb)
                .put("nativeAllocated", peakNativeAllocatedKb)
                .put("javaHeap", peakJavaHeapKb)
                .put("graphics", peakGraphicsKb)
                .put("totalPss", peakTotalPssKb)
                .put("samples", samples),
        )
    }

    private fun Debug.MemoryInfo.stat(name: String): Long = getMemoryStat(name)?.toLongOrNull() ?: 0L
}

internal fun appStorageBytes(context: Context): Long =
    listOfNotNull(File(context.applicationInfo.dataDir), context.externalCacheDir, context.getExternalFilesDir(null))
        .sumOf { root -> root.walkTopDown().filter(File::isFile).sumOf(File::length) }

@UnstableApi
internal fun deviceCapabilities(context: Context): JSONObject {
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val audio = AudioCapabilities.getCapabilities(context, androidx.media3.common.AudioAttributes.DEFAULT, null)
    val passthrough = JSONObject()
    PASSTHROUGH_ENCODINGS.forEach { (name, encoding) -> passthrough.put(name, audio.supportsEncoding(encoding)) }
    val decoders = JSONObject()
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { !it.isEncoder }
        .forEach { info ->
            info.supportedTypes.filter { it in WATCHED_MIME_TYPES }.forEach { type ->
                val caps = info.getCapabilitiesForType(type)
                val entry = JSONObject()
                    .put("hw", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else null)
                    .put("profiles", JSONArray(caps.profileLevels.map { "${it.profile}/${it.level}" }.distinct()))
                caps.videoCapabilities?.let { video ->
                    entry.put("max", "${video.supportedWidths.upper}x${video.supportedHeights.upper}")
                    entry.put("4k30", video.areSizeAndRateSupported(UHD_WIDTH, UHD_HEIGHT, UHD_RATE))
                }
                val list = decoders.optJSONArray(type) ?: JSONArray().also { decoders.put(type, it) }
                list.put(JSONObject().put(info.name, entry))
            }
        }
    return JSONObject()
        .put("model", Build.MODEL)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("hdrTypes", JSONArray(display.supportedHdrTypes()))
        .put("mode", "${display.mode.physicalWidth}x${display.mode.physicalHeight}@${display.mode.refreshRate}")
        .put("passthrough", passthrough)
        .put("decoders", decoders)
}

@Suppress("DEPRECATION")
private fun Display.supportedHdrTypes(): List<Int> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        mode.supportedHdrTypes.toList()
    } else {
        hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()
    }

private val PASSTHROUGH_ENCODINGS = listOf(
    "ac3" to C.ENCODING_AC3,
    "eac3" to C.ENCODING_E_AC3,
    "eac3joc" to C.ENCODING_E_AC3_JOC,
    "dts" to C.ENCODING_DTS,
    "dtshd" to C.ENCODING_DTS_HD,
    "truehd" to C.ENCODING_DOLBY_TRUEHD,
)

private val WATCHED_MIME_TYPES = setOf(
    "video/hevc",
    "video/av01",
    "video/x-vnd.on2.vp9",
    "video/dolby-vision",
    "video/avc",
    "audio/vnd.dts",
    "audio/vnd.dts.hd",
    "audio/eac3",
    "audio/eac3-joc",
    "audio/ac3",
    "audio/true-hd",
)

private const val SAMPLE_MILLIS = 1_000L
private const val KIB = 1_024L
private const val UHD_WIDTH = 3_840
private const val UHD_HEIGHT = 2_160
private const val UHD_RATE = 30.0
