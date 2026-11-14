package io.putdotio.android

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.putdotio.android.playback.MOBILE_CAST_BUTTON_TAG
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.SessionManagerListener
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.playback.CastAccount
import io.putdotio.android.playback.CastAvailability
import io.putdotio.android.playback.CastPrepared
import io.putdotio.android.playback.CastRemoteState
import io.putdotio.android.playback.CastTransfer
import io.putdotio.android.playback.casting
import io.putdotio.android.playback.MobileCast
import io.putdotio.android.playback.MobileCastController
import io.putdotio.android.playback.PlaybackTarget
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * The real app, signed in as devs-auto, casting to a real receiver: the live API resolves every file
 * the receiver plays. Reads only; the Cast session it starts is stopped at the end.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MobileCastRaceProofTest {
    @get:Rule
    val optIn = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Cast race proof requires opt-in", arguments.getString("$LANE.enabled") == "true")
                UUID.fromString(requireNotNull(arguments.getString("$LANE.runId")) { "Missing $LANE.runId" })
                base.evaluate()
            }
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val log = Collections.synchronizedList(mutableListOf<String>())
    private val started = SystemClock.elapsedRealtime()

    @Test
    fun anOverlappingSessionAudioHandoffLeavesOnlyTheNewerFileOnTheReceiver() = proof("session-audio-handoff") {
        val older = argument("olderAudio").toLong()
        val newer = argument("newerAudio").toLong()
        ActivityScenario.launch(MainActivity::class.java).use {
            val session = signedIn()
            val cast = awaitCast()
            val real = checkNotNull(onMain { cast.autoplay.account }) { "No signed-in Cast account" }
            val gates = mapOf(older to CompletableDeferred<Unit>(), newer to CompletableDeferred())
            val asked = mapOf(older to CompletableDeferred<Unit>(), newer to CompletableDeferred())
            val seen = Collections.synchronizedSet(mutableSetOf<Long>())
            var local: ExoPlayer? = null
            var started: CastSession? = null
            val scope = MainScope()
            var failure: Throwable? = null
            try {
                started = connect(cast)
                val loadsBefore = onMain { cast.loadsSent }
                onMain {
                    cast.autoplay.follow(session.sessionId, OrderedLookups(real, gates, asked))
                    scope.launch { cast.status.collect { status -> status?.fileId?.let(seen::add) } }
                }
                val transfer = CastTransfer(
                    toReceiver = { item, handover -> cast.castFromSession(item, handover, checkNotNull(local), scope) },
                    toLocal = {},
                )
                local = onMain { ExoPlayer.Builder(context).build() }
                playLocally(checkNotNull(local), older)
                onMain { transfer.transferState(checkNotNull(local), cast.remotePlayer) }
                await("older lookup asked") { asked.getValue(older).isCompleted }
                playLocally(checkNotNull(local), newer)
                onMain { transfer.transferState(checkNotNull(local), cast.remotePlayer) }
                await("newer lookup asked") { asked.getValue(newer).isCompleted }
                note("both handoffs wait on their lookups; the older one answers first")
                gates.getValue(older).complete(Unit)
                awaitOrElapse(OLDER_LOAD_WINDOW_MILLIS) { older in seen }
                note("receiver after the older answer: ${onMain { cast.status.value }?.describe()}")
                gates.getValue(newer).complete(Unit)
                awaitOrElapse(RECEIVER_MILLIS) {
                    onMain {
                        cast.status.value?.let { it.fileId == newer && it.state == CastRemoteState.Playing } == true
                    }
                }
                awaitOrElapse(RECEIVER_MILLIS) { onMain { checkNotNull(local).mediaItemCount == 0 } }
                val status = onMain { cast.status.value }
                val loads = onMain { cast.loadsSent } - loadsBefore
                note("receiver at the end: ${status?.describe()}; files seen there: $seen; loads sent: $loads")
                note("phone player: items=${onMain { local?.mediaItemCount }} playing=${onMain { local?.isPlaying }}")
                screenshot("01-phone-after-handoffs")
                assertFalse("The receiver loaded the superseded file", older in seen)
                assertEquals("Loads sent to the receiver", 1L, loads)
                assertEquals(newer, status?.fileId)
                assertEquals(CastRemoteState.Playing, status?.state)
                assertEquals("The phone kept the handed-over file", 0, onMain { checkNotNull(local).mediaItemCount })
                assertFalse("The phone still plays", onMain { checkNotNull(local).isPlaying })
                assertTrue(
                    "The session is stuck",
                    onMain { cast.availability.value is CastAvailability.Connected && !cast.holds(older) },
                )
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                cleanUp(
                    failure,
                    { onMain { cast.autoplay.follow(session.sessionId, real) } },
                    { onMain { local?.release() } },
                    { scope.cancel() },
                    { started?.let { disconnect(cast, it) } },
                )
            }
        }
    }

    @Test
    fun aLocalNetworkAnswerAfterTheControlsHideOpensTheCastPickerWithTheReceiver() = proof("local-network-answer") {
        val video = argument("video").toLong()
        val receiver = argument("receiver")
        shell(
            "am start --windowingMode $MULTI_WINDOW -a android.intent.action.VIEW -d putio://files/$video " +
                "-n ${context.packageName}/${MainActivity::class.java.name}",
        )
        try {
            signedIn()
            val cast = awaitCast()
            await("Android to ask for the local network") {
                onMain { cast.availability.value } == CastAvailability.NeedsLocalNetwork
            }
            await("the player") {
                device.findObject(By.text("Start over"))?.click()
                device.hasObject(By.pkg(context.packageName).desc("Back"))
            }
            check(onMain { mainActivity().isInMultiWindowMode }) {
                "Precondition: the player shows in a multi-window task"
            }
            await("the player's Cast button", WAIT_MILLIS) {
                castButton() != null || false.also {
                    val startOver = device.findObject(By.text("Start over"))
                    if (startOver != null) {
                        startOver.click()
                    } else {
                        device.click(device.displayWidth / 2, device.displayHeight / 2)
                    }
                    device.wait(Until.hasObject(CAST_BUTTON), REVEAL_MILLIS)
                }
            }
            screenshot("01-player-controls")
            check(playerShowsCast()) { "Precondition: the player window's Cast button is readable" }
            checkNotNull(castButton()) { "The controls hid before the tap" }.click()
            val allow = checkNotNull(device.wait(Until.findObject(ALLOW), PROMPT_MILLIS)) { "No local network prompt" }
            note("local network prompt shows over a player that keeps playing in its window")
            screenshot("02-local-network-prompt")
            check(playerShowsCast()) { "Precondition: the controls still show right after the prompt" }
            await("the controls to hide under the prompt", CONTROLS_HIDE_WAIT_MILLIS) { !playerShowsCast() }
            check(device.hasObject(ALLOW)) { "The prompt closed before the controls hid" }
            note("the player window lost its Cast button under the prompt; answering Allow")
            screenshot("03-controls-hidden")
            allow.click()
            val pickerOpened = device.wait(Until.hasObject(PICKER), PICKER_MILLIS) == true
            note("picker opened=$pickerOpened availability=${onMain { cast.availability.value }}")
            screenshot("04-after-allow")
            val listed = pickerOpened &&
                device.wait(Until.hasObject(By.textContains(receiver)), PICKER_LIST_MILLIS) == true
            note("picker lists $receiver=$listed")
            screenshot("05-picker")
            assertTrue("The Cast picker never opened", pickerOpened)
            assertTrue("The Cast picker never listed $receiver", listed)
        } finally {
            onMain { mainActivityOrNull()?.finish() }
        }
    }

    private fun mainActivity(): MainActivity = checkNotNull(mainActivityOrNull()) { "No MainActivity" }

    private fun mainActivityOrNull(): MainActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().let { monitor ->
            Stage.entries.flatMap { monitor.getActivitiesInStage(it) }.filterIsInstance<MainActivity>().firstOrNull()
        }

    private fun shell(command: String) {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private class OrderedLookups(
        private val real: CastAccount,
        private val gates: Map<Long, CompletableDeferred<Unit>>,
        private val asked: Map<Long, CompletableDeferred<Unit>>,
    ) : CastAccount {
        override val autoplay: Boolean get() = real.autoplay

        override suspend fun next(target: PlaybackTarget): PlaybackTarget? = real.next(target)

        override suspend fun prepare(target: PlaybackTarget): CastPrepared? {
            asked[target.fileId.value]?.complete(Unit)
            gates[target.fileId.value]?.await()
            return real.prepare(target)
        }
    }

    private fun connect(cast: MobileCastController): CastSession {
        val receiver = argument("receiver")
        val sessions = onMain { checkNotNull(CastContext.getSharedInstance()) { "Cast never started" }.sessionManager }
        val events = SessionEvents()
        onMain {
            sessions.addSessionManagerListener(events, CastSession::class.java)
            cast.gateway.startDiscovery()
        }
        try {
            await("$receiver in reach") { onMain { cast.gateway.devices.value.any { receiver in it.name } } }
            await("Cast to see a receiver") { onMain { castState() != CastState.NO_DEVICES_AVAILABLE } }
            instrumentation.waitForIdleSync()
            val target = onMain {
                val idle = castState() == CastState.NOT_CONNECTED && sessions.currentCastSession == null
                check(idle && events.resumed == null) { "A Cast session already runs; leaving it alone" }
                cast.gateway.devices.value.first { receiver in it.name }.also { cast.gateway.select(it.id) }
            }
            note("no session resumed; connecting to ${target.name} (${target.model})")
            val started = awaitValue("Cast to start a session on $receiver") {
                onMain {
                    check(events.resumed == null) { "A Cast session resumed while connecting; leaving it alone" }
                    events.started
                }
            }
            try {
                val joined = awaitValue("a session on $receiver") {
                    onMain { cast.availability.value as? CastAvailability.Connected }
                }
                check(receiver in joined.deviceName) {
                    "Connected to \"${joined.deviceName}\", not the selected ${target.name}"
                }
            } catch (error: Throwable) {
                cleanUp(error, { disconnect(cast, started) })
                throw error
            }
            return started
        } finally {
            onMain {
                sessions.removeSessionManagerListener(events, CastSession::class.java)
                cast.gateway.stopDiscovery()
            }
        }
    }

    private fun castState(): Int = checkNotNull(CastContext.getSharedInstance()).castState

    private class SessionEvents : SessionManagerListener<CastSession> {
        var started: CastSession? = null
        var resumed: CastSession? = null

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            started = session
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            resumed = session
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            resumed = session
        }

        override fun onSessionStarting(session: CastSession) = Unit

        override fun onSessionStartFailed(session: CastSession, error: Int) = Unit

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) = Unit

        override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit

        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    private fun disconnect(cast: MobileCastController, started: CastSession) {
        val stopping = onMain {
            (CastContext.getSharedInstance()?.sessionManager?.currentCastSession === started).also { ours ->
                if (ours) cast.gateway.stopCasting()
            }
        }
        if (stopping) {
            awaitOrElapse(RECEIVER_MILLIS) { onMain { !cast.availability.value.casting } }
            note("session ended: ${onMain { !cast.availability.value.casting }}")
        } else {
            note("the session this test started already ended")
        }
    }

    private fun cleanUp(failure: Throwable?, vararg steps: () -> Unit) {
        var first = failure
        for (step in steps) {
            runCatching(step).onFailure { error ->
                val primary = first
                if (primary == null) first = error else primary.addSuppressed(error)
            }
        }
        if (failure == null) first?.let { throw it }
    }

    private fun playLocally(player: ExoPlayer, fileId: Long) {
        val tone = File(context.cacheDir, "cast-race-$fileId.wav").also { if (!it.exists()) writeTone(it, fileId) }
        onMain {
            player.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(fileId.toString())
                    .setUri(Uri.fromFile(tone))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("cast-race-$fileId").build())
                    .build(),
            )
            player.prepare()
            player.play()
        }
        await("local playback of $fileId") { onMain { player.isPlaying } }
    }

    private fun writeTone(file: File, seed: Long) {
        val samples = TONE_SECONDS * SAMPLE_RATE
        val frequency = BASE_TONE_HZ + (seed % TONE_SPREAD_HZ)
        RandomAccessFile(file, "rw").use { out ->
            val data = ByteArray(samples * 2)
            for (i in 0 until samples) {
                val value = (sin(2 * PI * frequency * i / SAMPLE_RATE) * TONE_AMPLITUDE).toInt()
                data[i * 2] = value.toByte()
                data[i * 2 + 1] = (value shr 8).toByte()
            }
            out.write("RIFF".toByteArray())
            out.write(le(36 + data.size, 4))
            out.write("WAVEfmt ".toByteArray())
            out.write(le(16, 4) + le(1, 2) + le(1, 2) + le(SAMPLE_RATE, 4))
            out.write(le(SAMPLE_RATE * 2, 4) + le(2, 2) + le(16, 2))
            out.write("data".toByteArray())
            out.write(le(data.size, 4))
            out.write(data)
        }
    }

    private fun le(value: Int, bytes: Int) = ByteArray(bytes) { (value shr (8 * it)).toByte() }

    private fun signedIn(): MobileAuthState.SignedIn {
        val state = runBlocking { MobileOAuthRuntime.get(context).awaitSettledSession(WAIT_MILLIS.milliseconds) }
        val session = checkNotNull(checkNotNull(state) { "The session never settled" } as? MobileAuthState.SignedIn) {
            "Proof needs a signed-in session"
        }
        check(session.account.userId == argument("accountId").toLong()) { "Not the devs-auto account" }
        return session
    }

    private fun awaitCast(): MobileCastController {
        val cast = checkNotNull(onMain { MobileCast.controller(context) }) { "No Google Play services" }
        await("Cast to start") { onMain { cast.availability.value != CastAvailability.Unsupported } }
        await("the signed-in Cast account") { onMain { cast.autoplay.account != null } }
        return cast
    }

    private fun castButton() = device.findObject(CAST_BUTTON)

    private fun playerShowsCast(): Boolean = onMain {
        mainActivity().window.decorView.composeRoots().any { root ->
            root.semanticsOwner.unmergedRootSemanticsNode.contains { node ->
                node.config.getOrNull(SemanticsProperties.TestTag) == MOBILE_CAST_BUTTON_TAG
            }
        }
    }

    private fun View.composeRoots(): List<ViewRootForTest> = when {
        this is ViewRootForTest -> listOf(this)
        this is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).composeRoots() }
        else -> emptyList()
    }

    private fun SemanticsNode.contains(match: (SemanticsNode) -> Boolean): Boolean =
        match(this) || children.any { it.contains(match) }

    private fun await(what: String, timeoutMillis: Long = WAIT_MILLIS, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for $what" }
            SystemClock.sleep(POLL_MILLIS)
        }
        note("ready: $what")
    }

    private fun <T : Any> awaitValue(what: String, timeoutMillis: Long = WAIT_MILLIS, value: () -> T?): T {
        var found: T? = null
        await(what, timeoutMillis) { value()?.also { found = it } != null }
        return checkNotNull(found)
    }

    private fun awaitOrElapse(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(POLL_MILLIS)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun note(line: String) {
        log += "+${(SystemClock.elapsedRealtime() - started) / MILLIS_PER_SECOND_DOUBLE}s $line"
    }

    private var directory: File? = null

    private fun screenshot(label: String) {
        instrumentation.waitForIdleSync()
        saveProofScreenshot(checkNotNull(directory), "${testName}-$label")
    }

    private var testName = ""

    private fun proof(name: String, block: () -> Unit) {
        testName = name
        directory = proofDirectory("cast-race-proof-${arguments.getString("$LANE.runId")}")
        try {
            block()
            note("PASS")
        } catch (error: Throwable) {
            note("FAIL ${error.javaClass.simpleName}: ${error.message}")
            runCatching { screenshot("failure") }
            throw error
        } finally {
            File(checkNotNull(directory), "$name.txt").writeText(log.joinToString("\n", postfix = "\n"))
        }
    }

    private fun io.putdotio.android.playback.CastRemoteStatus.describe(): String = "file=$fileId state=$state"

    private val arguments get() = InstrumentationRegistry.getArguments()

    private fun argument(name: String): String =
        requireNotNull(arguments.getString("$LANE.$name")) { "Missing $LANE.$name" }

    private companion object {
        const val LANE = "putio.castrace"
        val ALLOW: BySelector = By.res("com.android.permissioncontroller", "permission_allow_button")
        val CAST_BUTTON: BySelector = By.desc("Cast")
        val PICKER: BySelector = By.text("Cast to")
        const val WAIT_MILLIS = 60_000L
        const val PROMPT_MILLIS = 15_000L
        const val PICKER_MILLIS = 10_000L
        const val CONTROLS_HIDE_WAIT_MILLIS = 15_000L
        const val PICKER_LIST_MILLIS = 5_000L
        const val REVEAL_MILLIS = 1_500L
        const val MULTI_WINDOW = 6
        const val RECEIVER_MILLIS = 45_000L
        const val OLDER_LOAD_WINDOW_MILLIS = 10_000L
        const val POLL_MILLIS = 200L
        const val TONE_SECONDS = 120
        const val SAMPLE_RATE = 22_050
        const val BASE_TONE_HZ = 300L
        const val TONE_SPREAD_HZ = 400L
        const val TONE_AMPLITUDE = 6_000.0
        const val MILLIS_PER_SECOND_DOUBLE = 1_000.0
    }
}
