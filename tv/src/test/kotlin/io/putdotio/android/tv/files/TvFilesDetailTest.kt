package io.putdotio.android.tv.files

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.PutioResult
import io.putdotio.android.files.FilesFolderCounts
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.files.FilesSubtitleLanguages
import io.putdotio.android.files.FilesSubtitles
import io.putdotio.android.tv.TvShell
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.setTvContent
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesDetailTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun theDetailCutsAVeryLongNameToThreeLinesAboveItsActions() {
        val name = List(EPISODES) { "Deep.Sea.Creatures.S01E0$it.The.Midnight.Zone" }.joinToString(".") + ".mkv"
        compose.setTvContent {
            TvFilesScreen(
                state = ready(item(1, name, PutioFileType.VIDEO)),
                onEvent = { true },
                onPlayMedia = {},
                onPlay = { _, _ -> },
            )
        }

        val title = compose.onNode(isHeading() and hasText(name)).getUnclippedBoundsInRoot()
        assertTrue("title is ${title.height}", title.height < DETAIL_TITLE_LINE * (DETAIL_TITLE_LINES + 1))
        compose.onNodeWithTag(TV_FILES_DETAIL_PRIMARY_TAG).assertIsDisplayed()
    }

    @Test
    fun aSharedFolderNeverAsksForItsCount() {
        var reads = 0
        val counts = FilesFolderCounts { PutioResult.Success(++reads) }
        compose.setTvContent {
            TvFilesScreen(
                state = ready(item(-1, "Items shared with you", PutioFileType.FOLDER)),
                onEvent = { true },
                onPlayMedia = {},
                detailSources = remember { TvFilesDetailSources(folderCounts = counts) },
            )
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertEquals(0, reads)
    }

    private companion object {
        val DETAIL_TITLE_LINE = 22.dp
        const val DETAIL_TITLE_LINES = 3
        const val EPISODES = 9
    }
}

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-rUS-w960dp-h540dp-television")
class TvFilesDetailLargeTextTest {
    private val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        private var previousFontScale = 1f
        override fun before() {
            previousFontScale = RuntimeEnvironment.getApplication().resources.configuration.fontScale
            RuntimeEnvironment.setFontScale(APP_MAX_FONT_SCALE)
        }

        override fun after() = RuntimeEnvironment.setFontScale(previousFontScale)
    }).around(compose)

    @Test
    fun aLongDottedNameLeavesEveryFactWholeInThePaneAtTheLargestText() {
        showLongName()

        for (label in listOf("Length", "Added", "Subtitles")) {
            val fact = compose.onNodeWithText(label).fetchSemanticsNode()
            assertEquals("$label is cut", fact.size.height.toFloat(), fact.boundsInRoot.height, 0.5f)
        }
    }

    @Test
    fun theSecondaryActionsScrollIntoViewAndBackToTheFacts() {
        showLongName(secondaryActions = true)

        compose.onNodeWithTag(TV_FILES_DETAIL_PRIMARY_TAG).requestFocus().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNode(hasText("Open in VLC") and hasClickAction()).assertIsFocused().assertIsDisplayed()
            .performKeyInput { repeat(SECONDARY_ACTIONS - 1) { pressKey(Key.DirectionDown) } }
        compose.waitForIdle()
        assertTrue(factsScroll() > 0f)

        compose.onNode(hasText("Open in VLC") and hasClickAction())
            .performKeyInput { repeat(SECONDARY_ACTIONS) { pressKey(Key.DirectionUp) } }
        compose.waitForIdle()
        assertEquals(0f, factsScroll())
        compose.onNodeWithText("Added").assertIsDisplayed()
    }

    private fun showLongName(secondaryActions: Boolean = false) {
        compose.setTvContent {
            TvShell(
                account = TvAccount(userId = 1, username = "user", email = "user@example.com"),
                onSignOut = {},
                showHome = false,
                filesPane = {
                    TvFilesScreen(
                        state = ready(item(1, LONG_NAME, PutioFileType.VIDEO, playback = PLAYED)),
                        onEvent = { true },
                        onPlayMedia = {},
                        onPlay = { _, _ -> },
                        confirmedTrashEnabled = if (secondaryActions) true else null,
                        watchedToggleEnabled = secondaryActions,
                        pinnedIds = if (secondaryActions) emptyList() else null,
                        detailSources = remember {
                            TvFilesDetailSources(
                                subtitleLanguages = FilesSubtitleLanguages {
                                    PutioResult.Success(FilesSubtitles(listOf("English", "English · SDH")))
                                },
                            )
                        },
                    )
                },
            )
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("English, English · SDH").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun factsScroll(): Float =
        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
                hasAnyDescendant(hasText("Added")) and
                !hasTestTag(TV_FILES_LIST_TAG),
        ).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private companion object {
        const val APP_MAX_FONT_SCALE = 1.3f
        const val SECONDARY_ACTIONS = 4
        val PLAYED = FilesPlaybackProgress(60.0, 600.0)
        const val LONG_NAME = "Blue.Lights.S04E01.The.Longest.Night.In.Belfast.Extended.Directors.Cut." +
            "Remastered.From.The.Original.Negative.With.Commentary.By.The.Cast.And.Crew.Plus.Behind.The." +
            "Scenes.Featurette.1080p.HDTV.DDP5.1.H264-ORGANiC.mkv"
    }
}
