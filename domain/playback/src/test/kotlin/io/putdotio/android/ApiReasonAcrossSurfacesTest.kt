package io.putdotio.android

import io.putdotio.android.playback.apiReason
import io.putdotio.android.playback.toPlaybackFailure
import io.putdotio.android.settings.AccountSettingsRepositoryResult
import io.putdotio.android.settings.SdkAccountSettingsRepository
import io.putdotio.android.settings.SdkAndroidAppConfigRepository
import io.putdotio.android.settings.apiReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiReasonAcrossSurfacesTest {
    @Test
    fun `every surface's failure carries put io's message for a refused request`() = runBlocking {
        val refusal = refusal(400, "Folder is full, empty some space first.")

        assertEquals(REASON, refusal.toPutioFailure().apiReason)
        assertEquals(REASON, refusal.toPlaybackFailure().apiReason)
        val settings = SdkAccountSettingsRepository(getSettings = { throw refusal }, saveSettings = {}).load()
        assertEquals(REASON, (settings as AccountSettingsRepositoryResult.Failure).failure.apiReason)
        val config = SdkAndroidAppConfigRepository(getConfig = { throw refusal }, saveConfig = {}).load()
        assertEquals(REASON, (config as PutioResult.Failure).failure.apiReason)
    }

    private fun refusal(status: Int, message: String) = putioRefusal(status, putioErrorBody(status, message))

    private companion object {
        const val REASON = "Folder is full, empty some space first."
    }
}
