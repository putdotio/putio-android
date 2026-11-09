package io.putdotio.android.tv.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PutioFileType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvItemTileTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val requests = mutableListOf<Pair<Any, String?>>()

    @Before
    fun recordRequests() {
        SingletonImageLoader.setUnsafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(
                        Interceptor { chain: Interceptor.Chain ->
                            requests += chain.request.data to chain.request.memoryCacheKey
                            ErrorResult(null, chain.request, IllegalStateException("no network")) as ImageResult
                        },
                    )
                }
                .build()
        }
    }

    @After
    fun resetLoader() = SingletonImageLoader.reset()

    @Test
    fun aRequestNeverPairsOneItemsImageWithAnothersCacheKey() {
        val first = video(1, "https://example.test/first.jpg")
        val second = video(2, "https://example.test/second.jpg")
        var shown by mutableStateOf(first)
        val thumbnails = TvItemThumbnails()
        compose.setTvContent { TvItemTile(shown, thumbnails) }
        compose.waitUntil(5_000) { requests.any { it.first == first.screenshotUrl } }

        compose.runOnIdle { shown = second }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) { requests.any { it.first == second.screenshotUrl } }

        assertEquals(
            setOf(first.screenshotUrl to "tv-tile-1-", second.screenshotUrl to "tv-tile-2-"),
            requests.toSet(),
        )
    }

    @Test
    fun aVideoWithoutAThumbnailKeepsItsFileTypeIcon() {
        compose.setTvContent { TvItemTile(video(3, null), TvItemThumbnails()) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val background = pixels[1, pixels.height / 2]
        val centre = (0 until pixels.width).map { pixels[it, pixels.height / 2] }

        assertTrue(requests.isEmpty())
        assertTrue(centre.any { it != background })
    }

    private fun video(id: Long, screenshot: String?) =
        item(id, "video-$id.mkv", PutioFileType.VIDEO).copy(screenshotUrl = screenshot)
}
