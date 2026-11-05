package io.putdotio.android.tv

import androidx.media3.common.util.UnstableApi
import io.putdotio.android.playback.spike.DirectPlaySpikeActivity
import io.putdotio.android.tv.auth.TvAuthRuntime
import io.putdotio.sdk.PutioClient

@UnstableApi
internal class TvDirectPlaySpikeActivity : DirectPlaySpikeActivity() {
    override suspend fun signedInClient(): PutioClient {
        val runtime = TvAuthRuntime.get(this)
        runtime.authController.restoreSession(interactive = false)
        return runtime.putioClient
    }
}
