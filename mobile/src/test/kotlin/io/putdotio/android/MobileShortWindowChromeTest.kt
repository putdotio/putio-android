package io.putdotio.android

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.design.PutioTheme
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.playback.MobileNowPlayingBar
import io.putdotio.android.playback.NowPlaying
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w320dp-h400dp-port")
/** A short, phone-narrow window, such as a split-screen slice, at 2x text. */
class MobileShortWindowChromeTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        private var previousFontScale = 1f
        override fun before() {
            previousFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
            RuntimeEnvironment.setFontScale(2f)
        }

        override fun after() = RuntimeEnvironment.setFontScale(previousFontScale)
    }).around(compose)

    @Test
    fun compactHeightTitleGivesWayToItsActionsInANarrowWindow() {
        compose.setContent {
            PutioTheme {
                MobileTabTitle(
                    title = "Downloads kept on this device",
                    summary = AnnotatedString("0 B on this device · 8.6 GB free"),
                    hasActions = true,
                ) {
                    listOf("Menu", "More options").forEach { label ->
                        IconButton(onClick = {}) {
                            Icon(painterResource(R.drawable.ic_ph_x), contentDescription = label)
                        }
                    }
                }
            }
        }

        listOf("Menu", "More options").forEach { label ->
            compose.onNodeWithContentDescription(label).assertIsDisplayed().assertWidthIsAtLeast(40.dp)
        }
    }

    @Test
    fun narrowShortMiniPlayerKeepsTheNameItsWidth() {
        compose.setContent {
            PutioTheme {
                MobileNowPlayingBar(
                    nowPlaying = NowPlaying(
                        fileId = FilesItemId(147),
                        title = "Rehearsal audio.m4a",
                        isPlaying = true,
                        progress = 0.1f,
                        positionMillis = 754_000L,
                        durationMillis = 2_871_000L,
                    ),
                    onOpen = {},
                    onToggle = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNode(SemanticsMatcher.keyIsDefined(MiddleCutShown), useUnmergedTree = true)
            .assertWidthIsAtLeast(96.dp)
    }
}
