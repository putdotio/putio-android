package io.putdotio.android.tv.files

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesRowNameTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun aLongNameInAFilesRowKeepsItsStartAndItsExtension() {
        compose.showFiles(state = { ready(item(1, NAME, PutioFileType.VIDEO)) })

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(NAME) and hasAnyAncestor(hasTestTag(TV_FILES_ROW_TAG)), useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        val shown = NAME.filterIndexed { index, _ -> layout.getBoundingBox(index).width > 0f }

        assertTrue("The name overflows the row", layout.isLineEllipsized(0))
        assertTrue("$shown keeps the start", shown.startsWith(NAME.take(KEPT)))
        assertTrue("$shown keeps the extension", shown.endsWith(NAME.takeLast(KEPT)))
    }

    private companion object {
        const val KEPT = 8
        const val NAME =
            "Blue.Lights.S04E01.The.Longest.Night.Of.The.Year.1080p.iP.WEB-DL.AAC2.0.H.264-ORGANiC.Remastered.mkv"
    }
}
