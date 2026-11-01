package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemScale
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioEmptyGlyph
import io.putdotio.android.design.PutioTvTheme
import io.putdotio.android.design.fileTypeIconRes
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesItemId
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesPlaybackProgress
import io.putdotio.android.tv.auth.TvAccount
import io.putdotio.android.tv.files.TvFilesRow
import io.putdotio.sdk.files.PutioFileType
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class TvFoundationsProofTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val enabled = arguments.getString("putio.tv.foundations.enabled") == "true"
                assumeTrue("TV foundations proof requires opt-in", enabled)
                runId()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(optIn).around(compose)

    @Before
    fun leaveTouchMode() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @Test
    fun foundations() {
        compose.setContent { PutioTvTheme { FoundationsSheet() } }
        compose.waitForIdle()
        screenshot("androidtv-rde2-13-foundations")
    }

    @Test
    fun watchedRowFocus() {
        compose.setContent { PutioTvTheme { FocusAccentsSheet(choices = false) } }
        compose.waitForIdle()
        screenshot("androidtv-st-focus-watched-row")
    }

    @Test
    fun choiceFocus() {
        compose.setContent { PutioTvTheme { FocusAccentsSheet(choices = true) } }
        waitForText("Sort by")
        screenshot("androidtv-st-focus-choice")
    }

    @Test
    fun safeArea() {
        compose.setContent {
            PutioTvTheme {
                Box(Modifier.fillMaxSize().drawWithContent { drawContent(); outlineLayoutMargins() }) {
                    TvShell(account = TvAccount(userId = 1, username = "proof", email = PROOF_EMAIL), onSignOut = {})
                }
            }
        }
        compose.waitForIdle()
        screenshot("androidtv-st-l2-safe-area")
    }

    @Test
    fun signOut() {
        mount(listings = rootListing())
        compose.openTvTab("Account")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sign out").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Sign out")[0].performSemanticsAction(SemanticsActions.OnClick)
        waitForText("Sign out of put.io?")
        screenshot("androidtv-st-d3-sign-out")
    }

    @Test
    fun moved() {
        val snackbar = TvSnackbarState()
        mount(listings = rootListing()) {
            TvSnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomStart).padding(start = TV_SIDE_MARGIN, bottom = TV_VERTICAL_MARGIN),
            )
        }
        compose.runOnUiThread { snackbar.show(TvSnackbarMessage("Moved to Trash", actionLabel = "Undo")) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Moved to Trash").fetchSemanticsNodes().isNotEmpty() }
        screenshot("androidtv-st-t3-moved")
    }

    @Test
    fun error() {
        mount(listings = emptyMap())
        waitForText("Couldn't load your files.")
        compose.onNode(hasText("Try again") and hasClickAction()).assertIsFocused()
        screenshot("androidtv-st-s3-error")
    }

    private fun rootListing() = mapOf(
        FilesFolder.Root.id to FilesPage(
            listOf(
                proofItem(10, "Big Buck Bunny", PutioFileType.FOLDER, FilesFolder.Root.id),
                proofItem(11, "Series", PutioFileType.FOLDER, FilesFolder.Root.id),
                proofItem(12, "Big_Buck_Bunny_1080_10s_30MB.mp4", PutioFileType.VIDEO, FilesFolder.Root.id),
                proofItem(13, "Sintel.2010.1080p.mkv", PutioFileType.VIDEO, FilesFolder.Root.id),
                proofItem(14, "FFTT Tree Rings October 23, 2026.mp3", PutioFileType.AUDIO, FilesFolder.Root.id),
            ),
            null,
        ),
    )

    private fun mount(
        listings: Map<FilesItemId, FilesPage>,
        overlay: @Composable BoxScope.() -> Unit = {},
    ) {
        val dependencies = tvProofDependencies(
            listings = listings,
            searchResults = emptyList(),
            recentSearchStore = { ProofRecentSearchStore() },
        )
        compose.mountTvProofSession(dependencies, overlay = overlay)
        compose.waitForIdle()
    }

    private fun DrawScope.outlineLayoutMargins() {
        val x = maxOf(TV_SIDE_MARGIN.toPx(), size.width * PutioDesignTokens.tvOverscanX)
        val y = maxOf(TV_VERTICAL_MARGIN.toPx(), size.height * PutioDesignTokens.tvOverscanY)
        drawRect(
            color = PutioDesignTokens.yellowSolid,
            topLeft = Offset(x, y),
            size = Size(size.width - 2 * x, size.height - 2 * y),
            style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
        )
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun press(keyCode: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(keyCode)
        compose.waitForIdle()
        Thread.sleep(STEP_MILLIS)
    }

    private fun screenshot(label: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(STEP_MILLIS)
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-foundations-proof-${runId()}")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$label.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.foundations.runId")))

    private companion object {
        const val STEP_MILLIS = 400L
        const val PROOF_EMAIL = "proof@example.invalid"
    }
}

@Composable
private fun FoundationsSheet() {
    val realFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        realFocus.requestFocus()
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .tvSafeAreaPadding(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpecLabel("File rows")
            ListItem(
                selected = false,
                onClick = {},
                headlineContent = { Text("Rest row") },
                supportingContent = { Text("276 MB · Aug 31") },
                leadingContent = { RowGlyph(fileTypeIconRes(PutioFileType.VIDEO), focused = false) },
                scale = ListItemScale.None,
            )
            ListItem(
                selected = false,
                onClick = {},
                headlineContent = { Text("Focused row") },
                supportingContent = { Text("Aug 31") },
                leadingContent = { RowGlyph(fileTypeIconRes(PutioFileType.FOLDER), focused = true) },
                trailingContent = { Icon(painterResource(R.drawable.ic_ph_caret_right), null, Modifier.size(20.dp)) },
                scale = ListItemScale.None,
                interactionSource = shownFocused(),
            )
            SpecLabel("Buttons")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(onClick = {}, style = TvButtonStyle.Text) { Text("Start over") }
                TvButton(onClick = {}, style = TvButtonStyle.Text, interactions = shownFocused()) { Text("Focused") }
                TvButton(onClick = {}, style = TvButtonStyle.Filled) { Text("Resume") }
                TvButton(onClick = {}, style = TvButtonStyle.Filled, modifier = Modifier.focusRequester(realFocus)) {
                    Text("Focused")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton(onClick = {}) { Text("Tonal") }
                TvButton(onClick = {}, interactions = shownFocused()) { Text("Focused") }
                TvButton(onClick = {}, style = TvButtonStyle.Destructive) { Text("Sign out") }
                TvButton(onClick = {}, style = TvButtonStyle.Destructive, interactions = shownFocused()) {
                    Text("Focused")
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpecLabel("Settings rows")
            SwitchRow("Rest", checked = true, focused = false)
            SwitchRow("Focused", checked = false, focused = true)
            SwitchRow("Focused, on", checked = true, focused = true)
            SpecLabel("Empty state")
            Box(Modifier.fillMaxWidth().weight(1f)) {
                TvEmptyState(
                    glyph = PutioEmptyGlyph.CouldNotLoad,
                    headline = "Couldn't load your files.",
                    message = "put.io is temporarily unavailable. Try again.",
                    action = TvEmptyStateAction("Try again", onClick = {}, icon = R.drawable.ic_ph_arrow_clockwise),
                    claimFocus = false,
                )
            }
        }
    }
}

@Composable
private fun FocusAccentsSheet(choices: Boolean) {
    val rowFocus = remember { FocusRequester() }
    val watched = { id: Long, name: String ->
        proofItem(id, name, PutioFileType.VIDEO, FilesFolder.Root.id)
            .copy(playback = FilesPlaybackProgress(startFromSeconds = 312.0, durationSeconds = 888.0))
    }
    LaunchedEffect(choices) {
        if (choices) return@LaunchedEffect
        withFrameNanos {}
        rowFocus.requestFocus()
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .tvSafeAreaPadding(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpecLabel("Watched rows")
            TvFilesRow(item = watched(20, "Sintel.2010.1080p.mkv"), onClick = {})
            TvFilesRow(
                item = watched(21, "Big_Buck_Bunny_1080_10s_30MB.mp4"),
                onClick = {},
                modifier = Modifier.focusRequester(rowFocus),
            )
        }
        Box(Modifier.weight(1f))
    }
    if (choices) {
        TvChoiceDialog(
            title = "Sort by",
            choices = listOf(TvChoice(1, "Name"), TvChoice(2, "Date added"), TvChoice(3, "Size")),
            selected = 2,
            onSelect = {},
            onDismiss = {},
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, focused: Boolean) {
    ListItem(
        selected = false,
        onClick = {},
        headlineContent = { Text(title) },
        leadingContent = { Icon(painterResource(R.drawable.ic_ph_subtitles), null, Modifier.size(20.dp)) },
        trailingContent = { TvSwitch(checked = checked, focused = focused) },
        scale = ListItemScale.None,
        interactionSource = if (focused) shownFocused() else remember { MutableInteractionSource() },
    )
}

@Composable
private fun RowGlyph(icon: Int, focused: Boolean) {
    Icon(painterResource(icon), contentDescription = null, tint = tvAccent(focused), modifier = Modifier.size(24.dp))
}

@Composable
private fun ColumnScope.SpecLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = PutioDesignTokens.yellowSolid,
        modifier = Modifier.padding(top = 12.dp, start = 12.dp),
    )
}

@Composable
private fun shownFocused(): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    LaunchedEffect(source) {
        withFrameNanos {}
        source.emit(FocusInteraction.Focus())
    }
    return source
}
