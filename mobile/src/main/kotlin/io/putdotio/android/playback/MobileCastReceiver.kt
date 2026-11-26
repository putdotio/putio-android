package io.putdotio.android.playback

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal const val PUTIO_RECEIVER_APPLICATION_ID = "E3A4ADFA"

internal const val PUTIO_CAST_NAMESPACE = "urn:x-cast:io.put.cast"

internal const val PUTIO_GET_STATUS_MESSAGE = """{"type":"GET_STATUS"}"""

internal fun receiverApplicationId(putio: Boolean): String =
    if (putio) PUTIO_RECEIVER_APPLICATION_ID else CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

internal class CastReceiverSelection(applied: String) {
    var applied: String = applied
        private set

    var wanted: String = applied
        private set

    fun want(applicationId: String, sessionLive: Boolean): String? {
        wanted = applicationId
        return if (sessionLive) null else apply()
    }

    fun sessionGone(): String? = apply()

    fun refused(applicationId: String, previous: String) {
        if (applied == applicationId) applied = previous
    }

    private fun apply(): String? {
        if (wanted == applied) return null
        applied = wanted
        return applied
    }
}

internal fun MobileCast.useReceiver(context: Context, putio: Boolean) {
    (controller(context)?.gateway as? GmsCastGateway)?.receivers?.use(putio)
}

internal class CastReceiverSwitch(
    private val context: Context,
    private val reselect: (MediaRouteSelector?) -> Unit,
) {
    private var cast: CastContextHandle? = null
    private var selection: CastReceiverSelection? = null
    private var wanted: String? = null
    private var drainingPendingSignOut = false

    fun drainPendingSignOut() {
        drainingPendingSignOut = true
        wanted = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }

    fun bind(cast: CastContextHandle) {
        this.cast = cast
        selection = CastReceiverSelection(cast.receiverApplicationId)
        if (drainingPendingSignOut) {
            selection?.want(wanted ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID, sessionLive = true)
        } else {
            wanted?.let(::want)
        }
    }

    fun use(putio: Boolean) {
        want(receiverApplicationId(putio))
    }

    fun signOut() {
        val defaultId = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        wanted = defaultId
        val handle = cast
        if (handle == null) {
            if (drainingPendingSignOut || CastReceiverStore.pendingSignOutEnd(context)) {
                drainingPendingSignOut = true
            } else {
                CastReceiverStore.save(context, CastReceiverStoredState.Default)
            }
            return
        }
        if (handle.isSessionLive()) {
            selection?.want(defaultId, sessionLive = true)
            val pending = handle.isPutioSession()
            drainingPendingSignOut = pending
            CastReceiverStore.save(
                context,
                if (pending) CastReceiverStoredState.PendingSignOutEnd else CastReceiverStoredState.Default,
            )
            return
        }
        drainingPendingSignOut = false
        selection?.want(defaultId, sessionLive = false)?.let(::apply)
        CastReceiverStore.save(context, CastReceiverStoredState.Default)
    }

    fun markLivePutioSignOutPending() {
        val target = wanted ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
        drainingPendingSignOut = true
        wanted = target
        selection?.want(target, sessionLive = true)
        CastReceiverStore.save(context, CastReceiverStoredState.PendingSignOutEnd)
    }

    fun retryIfIdle() {
        val handle = cast ?: return
        if (drainingPendingSignOut || handle.isSessionLive()) return
        selection?.sessionGone()?.let(::apply)
    }

    fun sessionStarted() {
        if (!drainingPendingSignOut) return
        drainingPendingSignOut = false
        val wantPutio = wanted == PUTIO_RECEIVER_APPLICATION_ID
        val appliedPutio = cast?.receiverApplicationId == PUTIO_RECEIVER_APPLICATION_ID
        CastReceiverStore.save(
            context,
            if (wantPutio && appliedPutio) CastReceiverStoredState.Putio else CastReceiverStoredState.Default,
        )
    }

    fun sessionGone() {
        val wasDraining = drainingPendingSignOut
        drainingPendingSignOut = false
        selection?.sessionGone()?.let(::apply)
        if (wasDraining) {
            val wantPutio = wanted == PUTIO_RECEIVER_APPLICATION_ID
            val appliedPutio = cast?.receiverApplicationId == PUTIO_RECEIVER_APPLICATION_ID
            CastReceiverStore.save(
                context,
                if (wantPutio && appliedPutio) CastReceiverStoredState.Putio else CastReceiverStoredState.Default,
            )
        }
    }

    private fun want(applicationId: String) {
        wanted = applicationId
        val live = drainingPendingSignOut || cast?.isSessionLive() == true
        selection?.want(applicationId, live)?.let(::apply)
    }

    private fun apply(applicationId: String) {
        val cast = cast ?: return
        val previous = cast.receiverApplicationId
        runCatching { cast.setReceiverApplicationId(applicationId) }
            .onFailure {
                selection?.refused(applicationId, previous)
                return
            }
        runCatching {
            CastReceiverStore.save(context, applicationId == PUTIO_RECEIVER_APPLICATION_ID)
            reselect(cast.mergedSelector)
        }
    }
}

internal fun CastContextHandle.isSessionLive(): Boolean =
    castState == CastState.CONNECTING || castState == CastState.CONNECTED || currentCastSession != null

internal fun CastContextHandle.isPutioSession(): Boolean =
    receiverApplicationId == PUTIO_RECEIVER_APPLICATION_ID ||
        currentCastSession?.applicationMetadata?.applicationId == PUTIO_RECEIVER_APPLICATION_ID

internal class PutioReceiverMessages(private val heard: (PutioReceiverState) -> Unit) {
    private var session: CastSession? = null

    fun listen(next: CastSession?) {
        session?.let { previous -> runCatching { previous.removeMessageReceivedCallbacks(PUTIO_CAST_NAMESPACE) } }
        session = null
        next ?: return
        runCatching {
            next.setMessageReceivedCallbacks(PUTIO_CAST_NAMESPACE) { _, _, message ->
                if (session === next) parsePutioReceiverStatus(message)?.let(heard)
            }
            session = next
            next.sendMessage(PUTIO_CAST_NAMESPACE, PUTIO_GET_STATUS_MESSAGE)
        }
    }
}

internal enum class CastReceiverStoredState {
    Default,
    Putio,
    PendingSignOutEnd,
}

internal object CastReceiverStore {
    fun state(context: Context): CastReceiverStoredState {
        val prefs = preferences(context)
        return when {
            prefs.getBoolean(PUTIO_KEY, false) -> CastReceiverStoredState.Putio
            prefs.getBoolean(PENDING_SIGN_OUT_END_KEY, false) -> CastReceiverStoredState.PendingSignOutEnd
            else -> CastReceiverStoredState.Default
        }
    }

    fun putio(context: Context): Boolean = state(context) == CastReceiverStoredState.Putio

    fun bootWithPutio(context: Context): Boolean = state(context) != CastReceiverStoredState.Default

    fun pendingSignOutEnd(context: Context): Boolean = state(context) == CastReceiverStoredState.PendingSignOutEnd

    fun save(context: Context, putio: Boolean) =
        save(context, if (putio) CastReceiverStoredState.Putio else CastReceiverStoredState.Default)

    // Off the main thread, commit() persists sign-out state synchronously before process exit.
    @SuppressLint("ApplySharedPref")
    fun save(
        context: Context,
        state: CastReceiverStoredState,
        commit: Boolean = Looper.myLooper() != Looper.getMainLooper(),
    ) {
        val editor = preferences(context).edit()
            .putBoolean(PUTIO_KEY, state == CastReceiverStoredState.Putio)
            .putBoolean(PENDING_SIGN_OUT_END_KEY, state == CastReceiverStoredState.PendingSignOutEnd)
        if (commit) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    fun clearPutio(context: Context) {
        if (state(context) == CastReceiverStoredState.Putio) {
            save(context, CastReceiverStoredState.Default)
        }
    }

    fun markSignOutPendingIfPutio(context: Context, livePutioSession: Boolean) {
        if (state(context) == CastReceiverStoredState.Putio) {
            val next = if (livePutioSession) {
                CastReceiverStoredState.PendingSignOutEnd
            } else {
                CastReceiverStoredState.Default
            }
            save(context, next)
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "cast_receiver"
    private const val PUTIO_KEY = "putio"
    private const val PENDING_SIGN_OUT_END_KEY = "pending_sign_out_end"
}

internal sealed interface PutioReceiverState {
    data object Idle : PutioReceiverState

    data class Loading(val fileId: Long?) : PutioReceiverState

    data class Loaded(val fileId: Long?) : PutioReceiverState

    data class Failed(val fileId: Long?, val reason: String) : PutioReceiverState
}

internal fun parsePutioReceiverStatus(message: String): PutioReceiverState? {
    val root = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject
    val state = root?.takeIf { it.string("type") == "STATUS" }?.get("state") as? JsonObject ?: return null
    val fileId = (state["fileId"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0L }
    return when (state.string("status")) {
        "idle" -> PutioReceiverState.Idle
        "loading" -> PutioReceiverState.Loading(fileId)
        "loaded" -> PutioReceiverState.Loaded(fileId)
        "failed" -> state.string("reason")?.let { PutioReceiverState.Failed(fileId, it) }
        else -> null
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
