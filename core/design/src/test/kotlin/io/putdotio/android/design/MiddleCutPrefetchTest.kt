package io.putdotio.android.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MiddleCutPrefetchTest {
    private val fitted = mutableListOf<String>()
    private val cuts = PutioMiddleCuts(CoroutineScope(Job()), Dispatchers.Unconfined) { { name -> fitted += name } }
    private val room = MiddleCutRoom(
        maxWidth = 400,
        style = TextStyle.Default,
        maxLines = 1,
        scale = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        fontFamilyResolver = createFontFamilyResolver(RuntimeEnvironment.getApplication()),
    )
    private val names = List(500) { "Episode $it.mkv" }

    @Test
    fun aListThatGrowsPageByPageWarmsOnlyItsFirstTwoHundredNames() {
        cuts.names(names.take(150))
        cuts.seen(room)
        cuts.names(names.take(300))
        cuts.names(names.take(500))

        assertEquals(names.take(200), fitted)
    }
}
