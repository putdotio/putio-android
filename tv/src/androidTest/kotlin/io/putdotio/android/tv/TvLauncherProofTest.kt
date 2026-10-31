package io.putdotio.android.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.tv.watchnext.TvProviderWatchNextStore
import io.putdotio.android.tv.watchnext.TvWatchNextOwner
import io.putdotio.android.tv.watchnext.TvWatchNextProgram
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvLauncherProofTest {
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun playNextShowsBothArtKinds() = runBlocking {
        assumeTrue("TV launcher proof requires opt-in", arguments.getString("putio.tv.launcher.enabled") == "true")
        val posterUrl = requireNotNull(arguments.getString("putio.tv.launcher.posterUrl"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TvProviderWatchNextStore(context)
        store.programs().forEach { store.delete(it.rowId) }

        val cards = listOf(
            TvWatchNextProgram(1L, "Big Buck Bunny.mp4", posterUrl, positionMillis = 179_000L, durationMillis = 596_000L),
            TvWatchNextProgram(
                2L,
                "Blue.Lights.S04E01.1080p.HDTV.H264-ORGANiC.mkv",
                posterUrl = null,
                positionMillis = 1_740_000L,
                durationMillis = 2_820_000L,
            ),
        )
        val now = System.currentTimeMillis()
        cards.forEachIndexed { index, card ->
            store.insert(TvWatchNextOwner(PROOF_USER_ID, card.fileId), card, engagedAtMillis = now - index)
        }

        assertEquals(cards, store.programs().sortedBy { it.program.fileId }.map { it.program })
    }
}

private const val PROOF_USER_ID = 1L
