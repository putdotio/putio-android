package io.putdotio.android.playback.spike

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import io.putdotio.android.files.loadMediaAccount
import io.putdotio.sdk.PutioClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@UnstableApi
public abstract class DirectPlaySpikeActivity : ComponentActivity() {
    protected abstract suspend fun signedInClient(): PutioClient

    private var engine: SpikeEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val container = FrameLayout(this)
        setContentView(container)
        val request = SpikeRequest.from(intent)
        lifecycleScope.launch {
            val result = JSONObject().put("engine", request.engine).put("runId", request.runId)
            runCatching { run(request, container, result) }
                .onFailure { result.put("harnessError", it.javaClass.simpleName + ": " + it.message) }
            Log.i(TAG, "RESULT " + result.toString())
            engine?.release()
            engine = null
            finish()
        }
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        super.onDestroy()
    }

    private suspend fun run(request: SpikeRequest, container: FrameLayout, result: JSONObject) {
        result.put("device", deviceCapabilities(this))
        val url = resolveOriginalUrl(request.fileId)
        val storageBefore = appStorageBytes(this)
        val sampler = MemorySampler(lifecycleScope)
        val created = when (request.engine) {
            ENGINE_VLC -> VlcSpikeEngine(this, container, request)
            ENGINE_LIBASS -> Media3SpikeEngine(this, container, request, libass = true)
            else -> Media3SpikeEngine(this, container, request, libass = false)
        }
        engine = created
        sampler.start()
        val startedAt = SystemClock.elapsedRealtime()
        created.start(url, startedAt)
        delay(request.playMillis)
        if (request.seekMillis >= 0) {
            result.put("seekToReadyMs", created.seekAndAwait(request.seekMillis))
            delay(SEEK_SETTLE_MILLIS)
        }
        result.put("wallMs", SystemClock.elapsedRealtime() - startedAt)
        created.report(result)
        sampler.stop()
        sampler.report(result)
        result.put("storageDeltaBytes", appStorageBytes(this) - storageBefore)
    }

    private suspend fun resolveOriginalUrl(fileId: Long): String {
        val client = signedInClient()
        val token = requireNotNull(client.loadMediaAccount().downloadToken) { "no download token" }
        return client.files.buildOriginalStreamUrl(fileId, token)
    }
}

internal data class SpikeRequest(
    val runId: String,
    val fileId: Long,
    val engine: String,
    val playMillis: Long,
    val seekMillis: Long,
    val audioTrack: Int,
    val tvBuffer: Boolean,
) {
    companion object {
        fun from(intent: Intent): SpikeRequest =
            SpikeRequest(
                runId = intent.getStringExtra("runId").orEmpty(),
                fileId = intent.getLongExtra("fileId", -1L).also { require(it > 0) { "fileId extra is required" } },
                engine = intent.getStringExtra("engine") ?: ENGINE_MEDIA3,
                playMillis = intent.getLongExtra("playMs", DEFAULT_PLAY_MILLIS),
                seekMillis = intent.getLongExtra("seekMs", -1L),
                audioTrack = intent.getIntExtra("audioTrack", -1),
                tvBuffer = intent.getBooleanExtra("tvBuffer", false),
            )
    }
}

internal interface SpikeEngine {
    fun start(url: String, startedAt: Long)

    suspend fun seekAndAwait(positionMillis: Long): Long

    fun report(into: JSONObject)

    fun release()
}

internal const val TAG = "DirectPlaySpike"
internal const val ENGINE_MEDIA3 = "media3"
internal const val ENGINE_LIBASS = "media3-libass"
internal const val ENGINE_VLC = "vlc"
private const val DEFAULT_PLAY_MILLIS = 30_000L
private const val SEEK_SETTLE_MILLIS = 3_000L
