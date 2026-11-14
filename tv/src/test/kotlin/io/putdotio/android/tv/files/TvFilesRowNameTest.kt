package io.putdotio.android.tv.files

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.MiddleCutShown
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

        val row = hasText(NAME) and hasAnyAncestor(hasTestTag(TV_FILES_ROW_TAG))
        val node = compose.onNode(row, useUnmergedTree = true).fetchSemanticsNode()
        val shown = requireNotNull(node.config.getOrNull(MiddleCutShown)) { "The name overflows the row" }

        assertTrue("$shown keeps the start", shown.startsWith(NAME.take(KEPT)))
        assertTrue("$shown keeps the extension", shown.endsWith(NAME.takeLast(KEPT)))
    }

    private companion object {
        const val KEPT = 8
        const val NAME =
            "Blue.Lights.S04E01.The.Longest.Night.Of.The.Year.1080p.iP.WEB-DL.AAC2.0.H.264-ORGANiC.Remastered.mkv"
    }
}
