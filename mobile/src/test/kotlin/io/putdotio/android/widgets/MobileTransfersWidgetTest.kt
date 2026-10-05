package io.putdotio.android.widgets

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.MainActivity
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.R
import io.putdotio.android.auth.MobileAccount
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileSessionKey
import io.putdotio.android.transfers.AppTransferStatus
import io.putdotio.android.transfers.TransferId
import io.putdotio.android.transfers.TransferItem
import io.putdotio.sdk.errors.PutioConfigurationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Transfers widget per session state, against Robolectric's widget host, which reapplies views like a launcher. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS")
class MobileTransfersWidgetTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val widgets = shadowOf(AppWidgetManager.getInstance(context))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val auth = MutableStateFlow<MobileAuthState>(MobileAuthState.SignedOut())
    private val loads = mutableListOf<MobileAuthState>()
    private val rejected = mutableListOf<MobileAuthSessionId>()
    private var next: suspend () -> PutioResult<List<TransferItem>> = { PutioResult.Success(emptyList()) }
    private val updater = TransfersWidgetUpdater(
        context = context,
        source = TransfersWidgetSource(
            authState = auth,
            settledSession = { auth.value },
            load = {
                loads += auth.value
                next()
            },
            rejectSession = { rejected += it },
        ),
        scope = scope,
        clock = { NOW },
    )

    @After
    fun tearDown() {
        MobileWidgets.transfersForTest = null
        scope.cancel()
    }

    @Test
    fun signedInTheWidgetListsActiveTransfersWithProgressAndOpensTransfers() {
        auth.value = signedIn(USER, session = 1L)
        next = {
            PutioResult.Success(
                listOf(
                    transfer(1L, "ubuntu.iso", AppTransferStatus.Downloading, percent = 42.4),
                    transfer(2L, "done.mkv", AppTransferStatus.Completed, percent = 100.0),
                    transfer(3L, "debian.iso", AppTransferStatus.Queued, percent = null),
                    transfer(4L, "arch.iso", AppTransferStatus.Seeding, percent = 100.0),
                    transfer(5L, "fedora.iso", AppTransferStatus.Waiting, percent = 0.0),
                    transfer(6L, "broken.iso", AppTransferStatus.Failed, percent = 10.0),
                ),
            )
        }
        val widget = place()

        assertEquals(listOf("ubuntu.iso", "debian.iso", "arch.iso"), names(widget))
        assertEquals("Downloading · 42%", text(widget, R.id.widget_transfers_row_0_status))
        assertEquals(42, widget.findViewById<ProgressBar>(R.id.widget_transfers_row_0_progress).progress)
        assertEquals("Queued", text(widget, R.id.widget_transfers_row_1_status))
        assertTrue(widget.findViewById<ProgressBar>(R.id.widget_transfers_row_1_progress).isIndeterminate)
        assertTrue(text(widget, R.id.widget_transfers_footer).endsWith(" · 1 more"))
        assertEquals(View.VISIBLE, widget.findViewById<View>(R.id.widget_transfers_refresh).visibility)
        assertEquals(View.GONE, widget.findViewById<View>(R.id.widget_transfers_message).visibility)

        widget.findViewById<View>(android.R.id.background).performClick()
        val opened = shadowOf(context).nextStartedActivity
        assertEquals(ComponentName(context, MainActivity::class.java), opened.component)
        assertEquals(Intent.ACTION_VIEW, opened.action)
        assertEquals("putio://transfers".toUri(), opened.data)
    }

    @Test
    fun signedOutTheWidgetAsksToSignInAndReadsNothing() {
        val widget = place()

        assertEquals("Sign in to put.io to see your transfers.", text(widget, R.id.widget_transfers_message))
        assertTrue(names(widget).isEmpty())
        assertEquals(View.GONE, widget.findViewById<View>(R.id.widget_transfers_refresh).visibility)
        assertTrue(loads.isEmpty())
    }

    @Test
    fun signOutClearsEveryNameAtOnce() {
        auth.value = signedIn(USER, session = 1L)
        next = { PutioResult.Success(listOf(transfer(1L, "private-name.iso", AppTransferStatus.Downloading, 5.0))) }
        val widget = place()
        assertEquals(listOf("private-name.iso"), names(widget))

        auth.value = MobileAuthState.SignedOut()
        updater.clear()

        assertEquals("Sign in to put.io to see your transfers.", text(widget, R.id.widget_transfers_message))
        // The host reapplied onto the same views: hidden rows must not keep the old text either.
        assertEquals(listOf("", "", ""), rowTexts(widget))
        assertEquals("", text(widget, R.id.widget_transfers_footer))
    }

    @Test
    fun aReadThatOutlivesItsSessionNeverShowsThatAccountsNames() {
        auth.value = signedIn(USER, session = 1L)
        val gate = CompletableDeferred<Unit>()
        next = {
            gate.await()
            PutioResult.Success(listOf(transfer(1L, "first-account.iso", AppTransferStatus.Downloading, 5.0)))
        }
        val widget = place()
        assertEquals(1, loads.size)

        // Account switch while the first account's read is still out.
        auth.value = signedIn(OTHER, session = 2L)
        gate.complete(Unit)
        idle()

        assertFalse(rowTexts(widget).contains("first-account.iso"))
    }

    @Test
    fun anAccountSwitchShowsOnlyTheNextAccountsTransfers() {
        auth.value = signedIn(USER, session = 1L)
        next = { PutioResult.Success(listOf(transfer(1L, "first.iso", AppTransferStatus.Downloading, 5.0))) }
        val widget = place()

        auth.value = MobileAuthState.SignedOut()
        updater.clear()
        auth.value = signedIn(OTHER, session = 2L)
        next = { PutioResult.Success(listOf(transfer(9L, "second.iso", AppTransferStatus.Queued, null))) }
        updater.refresh()
        idle()

        assertEquals(listOf("second.iso"), names(widget))
        assertFalse(rowTexts(widget).contains("first.iso"))
    }

    @Test
    fun aRejectedSessionIsSignedOutAndAFailedReadKeepsTheRowsShown() {
        auth.value = signedIn(USER, session = 1L)
        next = { PutioResult.Success(listOf(transfer(1L, "kept.iso", AppTransferStatus.Downloading, 5.0))) }
        val widget = place()

        next = { PutioResult.Failure(PutioFailure.NetworkUnavailable(IllegalStateException("offline"))) }
        updater.refresh()
        idle()
        assertEquals(listOf("kept.iso"), names(widget))

        next = { PutioResult.Failure(PutioFailure.AuthenticationRequired(PutioConfigurationException("rejected"))) }
        updater.refresh()
        idle()
        assertEquals(listOf(MobileAuthSessionId(1L)), rejected)
    }

    @Test
    fun withoutAPlacedWidgetNothingIsRead() {
        auth.value = signedIn(USER, session = 1L)

        updater.refresh()
        updater.show(MobileSessionKey(USER, MobileAuthSessionId(1L)), emptyList())
        idle()

        assertTrue(loads.isEmpty())
    }

    @Test
    fun theTransfersScreensRowsReachTheWidgetOnlyForTheSignedInSession() {
        auth.value = signedIn(USER, session = 1L)
        val widget = place()
        assertEquals("No active transfers", text(widget, R.id.widget_transfers_message))

        val stale = listOf(transfer(1L, "stale.iso", AppTransferStatus.Downloading, 1.0))
        updater.show(MobileSessionKey(OTHER, MobileAuthSessionId(2L)), stale)
        assertTrue(names(widget).isEmpty())

        val live = listOf(transfer(2L, "live.iso", AppTransferStatus.Downloading, 1.0))
        updater.show(MobileSessionKey(USER, MobileAuthSessionId(1L)), live)
        assertEquals(listOf("live.iso"), names(widget))
    }

    @Test
    fun theRefreshButtonIsAnExplicitImmutableBroadcastToTheAppsOwnReceiver() {
        val refresh = shadowOf(MobileWidgetActionReceiver.refresh(context))

        assertTrue(refresh.isBroadcast && refresh.isImmutable)
        assertEquals(ComponentName(context, MobileWidgetActionReceiver::class.java), refresh.savedIntent.component)
        assertEquals(MobileWidgetActionReceiver.ACTION_REFRESH, refresh.savedIntent.action)
    }

    @Test
    fun renderedViewsCarryNoNameOutsideTheReadyState() {
        for (content in listOf(
            TransfersWidgetContent.SignedOut,
            TransfersWidgetContent.Loading,
            TransfersWidgetContent.Unavailable,
        )) {
            val view = transfersWidgetViews(context, content).apply(context, FrameLayout(context))
            assertEquals(listOf("", "", ""), rowTexts(view))
        }
    }

    /** Places one widget; the host sends the update a launcher sends, which runs a refresh. */
    private fun place(): View {
        MobileWidgets.transfersForTest = updater
        val id = widgets.createWidget(MobileTransfersWidgetProvider::class.java, R.layout.widget_transfers)
        idle()
        return widgets.getViewFor(id)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun text(view: View, id: Int): String = view.findViewById<TextView>(id).text.toString()

    private fun rowTexts(view: View): List<String> = ROW_NAMES.map { text(view, it) }

    private fun names(view: View): List<String> =
        ROW_NAMES.zip(ROWS).filter { (_, row) -> view.findViewById<View>(row).visibility == View.VISIBLE }
            .map { (name, _) -> text(view, name) }

    private companion object {
        const val USER = 7L
        const val OTHER = 8L
        const val NOW = 1_700_000_000_000L
        val ROWS = listOf(R.id.widget_transfers_row_0, R.id.widget_transfers_row_1, R.id.widget_transfers_row_2)
        val ROW_NAMES = listOf(
            R.id.widget_transfers_row_0_name,
            R.id.widget_transfers_row_1_name,
            R.id.widget_transfers_row_2_name,
        )

        fun signedIn(userId: Long, session: Long) = MobileAuthState.SignedIn(
            account = MobileAccount(userId = userId, username = "user$userId", email = "user$userId@example.com"),
            sessionId = MobileAuthSessionId(session),
        )

        fun transfer(id: Long, name: String, status: AppTransferStatus, percent: Double?) = TransferItem(
            id = TransferId(id),
            name = name,
            status = status,
            fileId = null,
            sizeBytes = null,
            percentDone = percent,
            downloadSpeedBytesPerSecond = null,
            uploadSpeedBytesPerSecond = null,
            estimatedSecondsRemaining = null,
            availability = null,
            errorMessage = null,
            createdAt = "2026-10-04T00:00:00",
            userFileExists = null,
        )
    }
}
