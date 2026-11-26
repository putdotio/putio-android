package io.putdotio.android.widgets

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.auth.MobileAuthSessionId
import io.putdotio.android.auth.MobileAuthState
import io.putdotio.android.auth.MobileOAuthRuntime
import io.putdotio.android.auth.MobileSessionKey
import io.putdotio.android.auth.sessionKey
import io.putdotio.android.holdBroadcast
import io.putdotio.android.transfers.TransferItem
import io.putdotio.android.transfers.SdkTransfersRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MobileTransfersWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MobileWidgets.transfers(context).refresh(holdBroadcast()::release)
    }
}

internal class MobileWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) return
        MobileWidgets.transfers(context).refresh(holdBroadcast()::release)
    }

    internal companion object {
        const val ACTION_REFRESH = "io.putdotio.android.widgets.action.REFRESH"

        fun refresh(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_REFRESH).setClass(context, MobileWidgetActionReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

internal class TransfersWidgetSource(
    val authState: StateFlow<MobileAuthState>,
    val settledSession: suspend () -> MobileAuthState?,
    val load: suspend () -> PutioResult<List<TransferItem>>,
    val rejectSession: (MobileAuthSessionId) -> Unit,
)

internal class TransfersWidgetUpdater(
    context: Context,
    private val source: TransfersWidgetSource,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeout: Duration = REFRESH_TIMEOUT,
) {
    // Only the application context is retained; lint cannot see that the process-wide holder keeps nothing else.
    @SuppressLint("StaticFieldLeak")
    private val appContext: Context = context.applicationContext
    private val lock = Any()
    private var refreshing: Job? = null

    private var shown: Pair<MobileSessionKey?, TransfersWidgetContent>? = null

    private val manager: AppWidgetManager? get() = AppWidgetManager.getInstance(appContext)
    private val widgetIds: IntArray
        get() = manager?.getAppWidgetIds(ComponentName(appContext, MobileTransfersWidgetProvider::class.java))
            ?: IntArray(0)

    fun refresh(onDone: () -> Unit = {}) {
        if (widgetIds.isEmpty()) {
            onDone()
            return
        }
        synchronized(lock) {
            refreshing?.cancel()
            refreshing = scope.launch {
                try {
                    withTimeoutOrNull(timeout) { refreshNow() } ?: pushIfUnknown(TransfersWidgetContent.Unavailable)
                } finally {
                    onDone()
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            refreshing?.cancel()
            push(null, TransfersWidgetContent.SignedOut)
        }
    }

    fun show(key: MobileSessionKey, items: List<TransferItem>) {
        if (widgetIds.isNotEmpty()) publish(key, items.toTransfersWidgetContent(clock()))
    }

    private suspend fun refreshNow() {
        when (val state = source.settledSession()) {
            is MobileAuthState.SignedIn -> refreshSignedIn(state)
            null, is MobileAuthState.ValidationUnavailable -> pushIfUnknown(TransfersWidgetContent.Unavailable)
            else -> synchronized(lock) {
                if (source.authState.value !is MobileAuthState.SignedIn) push(null, TransfersWidgetContent.SignedOut)
            }
        }
    }

    private suspend fun refreshSignedIn(state: MobileAuthState.SignedIn) {
        val key = MobileSessionKey(state.account.userId, state.sessionId)
        val current = synchronized(lock) { shown }
        if (current != null && current.first != key) publish(key, TransfersWidgetContent.Loading)
        when (val result = source.load()) {
            is PutioResult.Success -> publish(key, result.value.toTransfersWidgetContent(clock()))
            is PutioResult.Failure -> when (result.failure) {
                is PutioFailure.AuthenticationRequired -> source.rejectSession(state.sessionId)
                else -> synchronized(lock) {
                    if (shown?.let { it.first == key && it.second is TransfersWidgetContent.Ready } != true) {
                        publishLocked(key, TransfersWidgetContent.Unavailable)
                    }
                }
            }
        }
    }

    private fun publish(key: MobileSessionKey, content: TransfersWidgetContent) {
        synchronized(lock) { publishLocked(key, content) }
    }

    private fun publishLocked(key: MobileSessionKey, content: TransfersWidgetContent) {
        if (source.authState.value.sessionKey() == key) push(key, content)
    }

    private fun pushIfUnknown(content: TransfersWidgetContent) {
        synchronized(lock) {
            val current = shown
            if (current == null || current.second !is TransfersWidgetContent.Ready) push(current?.first, content)
        }
    }

    private fun push(key: MobileSessionKey?, content: TransfersWidgetContent) {
        val ids = widgetIds
        if (ids.isNotEmpty()) manager?.updateAppWidget(ids, transfersWidgetViews(appContext, content))
        shown = key to content
    }

    private companion object {
        val REFRESH_TIMEOUT = 25.seconds
    }
}

internal object MobileWidgets {
    @Volatile
    private var transfers: TransfersWidgetUpdater? = null

    @Volatile
    internal var transfersForTest: TransfersWidgetUpdater? = null

    fun transfers(context: Context): TransfersWidgetUpdater =
        transfersForTest ?: transfers ?: synchronized(this) {
            transfers ?: createTransfers(context.applicationContext).also { transfers = it }
        }

    fun sessionStarted(context: Context) = transfers(context).refresh()

    fun sessionLeft(context: Context) = transfers(context).clear()

    private val SESSION_TIMEOUT = 15.seconds

    private fun createTransfers(context: Context): TransfersWidgetUpdater {
        val runtime = MobileOAuthRuntime.get(context)
        val repository = SdkTransfersRepository(runtime.putioClient, runtime.uploadClient)
        return TransfersWidgetUpdater(
            context = context,
            source = TransfersWidgetSource(
                authState = runtime.authController.state,
                settledSession = { runtime.awaitSettledSession(SESSION_TIMEOUT) },
                load = {
                    when (val page = repository.load()) {
                        is PutioResult.Success -> PutioResult.Success(page.value.items)
                        is PutioResult.Failure -> page
                    }
                },
                rejectSession = runtime::rejectSession,
            ),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
}
