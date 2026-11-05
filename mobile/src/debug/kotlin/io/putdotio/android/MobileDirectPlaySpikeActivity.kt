package io.putdotio.android

import androidx.media3.common.util.UnstableApi
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.playback.spike.DirectPlaySpikeActivity
import io.putdotio.sdk.PutioClient

@UnstableApi
internal class MobileDirectPlaySpikeActivity : DirectPlaySpikeActivity() {
    override suspend fun signedInClient(): PutioClient {
        val runtime = MobileOAuthRuntime.get(this)
        runtime.authController.restoreSession()
        return runtime.putioClient
    }
}
