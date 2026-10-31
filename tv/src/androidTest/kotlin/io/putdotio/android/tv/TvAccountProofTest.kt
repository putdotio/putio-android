package io.putdotio.android.tv

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.putdotio.android.AccountStorage
import io.putdotio.android.files.FilesFolder
import io.putdotio.android.files.FilesPage
import io.putdotio.android.files.FilesSort
import io.putdotio.android.settings.AccountSettingsPreferences
import io.putdotio.android.settings.TunnelRouteName
import io.putdotio.android.settings.TunnelRouteOption
import io.putdotio.android.tv.auth.TvAccount
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
class TvAccountProofTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val enabled = arguments.getString("putio.tv.account.enabled") == "true"
                assumeTrue("TV account proof requires opt-in", enabled)
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
    fun playbackGroup() {
        mount()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(5_000) { isFocused(RESUME) }
        screenshot("androidtv-rde2-12-account")
    }

    @Test
    fun libraryGroup() {
        mount()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(5_000) { isFocused(MANAGE_TRASH) }
        screenshot("androidtv-st-a1-library")
    }

    @Test
    fun privacyGroup() {
        mount()
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(5_000) { isFocused(DIAGNOSTICS) }
        screenshot("androidtv-st-a2-privacy")
    }

    @Test
    fun aboutGroup() {
        mount()
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(5_000) { isFocused(VERSION) }
        screenshot("androidtv-st-a3-about")
    }

    @Test
    fun proxySheet() {
        mount()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(3) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused(PROXY) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { isFocused(CDN77) }
        screenshot("androidtv-st-d2-proxy")
    }

    @Test
    fun signOutConfirm() {
        mount()
        repeat(5) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.waitUntil(5_000) { isFocused(SIGN_OUT) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(SIGN_OUT_TITLE).fetchSemanticsNodes().isNotEmpty() }
        screenshot("androidtv-st-d3-sign-out")
    }

    private fun mount() {
        val dependencies = tvProofDependencies(
            listings = mapOf(
                FilesFolder.Root.id to FilesPage(
                    listOf(proofItem(10, "Big Buck Bunny", PutioFileType.FOLDER, FilesFolder.Root.id)),
                    null,
                ),
            ),
            searchResults = emptyList(),
            recentSearchStore = { ProofRecentSearchStore() },
            trashSizeBytes = TRASH_BYTES,
            settings = AccountSettingsPreferences(
                historyEnabled = true,
                trashEnabled = true,
                showSubtitles = true,
                autoSelectSubtitles = true,
                resumePlayback = true,
                tunnelRoute = TunnelRouteName("cdn77"),
                defaultSort = FilesSort.NAME_ASCENDING,
                diagnosticsEnabled = true,
                productAnalyticsEnabled = false,
            ),
            tunnelRoutes = ROUTES,
        )
        compose.mountTvProofSession(dependencies, account = proofAccount(), tab = "Account", tunnelRoutes = ROUTES)
        compose.waitUntil(5_000) { isFocused(LIBRARY) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(MANAGE_TRASH).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun proofAccount() = TvAccount(
        userId = 1,
        username = "devs-auto",
        email = "devs+auto@put.io",
        historyEnabled = true,
        storage = AccountStorage(
            availableBytes = TOTAL_BYTES - USED_BYTES,
            sizeBytes = TOTAL_BYTES,
            usedBytes = USED_BYTES,
            showOptimisticUsage = false,
        ),
    )

    private fun isFocused(label: String) = compose.onAllNodesWithText(label).fetchSemanticsNodes()
        .any { it.config.getOrNull(SemanticsProperties.Focused) == true }

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
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "tv-account-proof-${runId()}")
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

    private fun runId(): UUID = UUID.fromString(requireNotNull(arguments.getString("putio.tv.account.runId")))

    private companion object {
        const val STEP_MILLIS = 400L
        const val LIBRARY = "Library"
        const val MANAGE_TRASH = "Manage your trash"
        const val RESUME = "Resume where you left off"
        const val DIAGNOSTICS = "Diagnostics"
        const val VERSION = "Version"
        const val PROXY = "Proxy"
        const val CDN77 = "cdn77"
        const val SIGN_OUT = "Sign out"
        const val SIGN_OUT_TITLE = "Sign out of put.io?"
        const val USED_BYTES = 2_000_000_000_000L
        const val TOTAL_BYTES = 11_000_000_000_000L
        const val TRASH_BYTES = 3_700_000L
        val ROUTES = listOf(
            TunnelRouteOption(TunnelRouteName("cdn77"), "Fastest for most places. The default."),
            TunnelRouteOption(TunnelRouteName.DEFAULT, "Amsterdam"),
            TunnelRouteOption(TunnelRouteName("Bunny"), "A second CDN, for when cdn77 is slow."),
        )
    }
}
