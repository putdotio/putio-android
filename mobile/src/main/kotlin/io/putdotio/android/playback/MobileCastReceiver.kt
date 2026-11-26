package io.putdotio.android.playback

import android.content.Context
import androidx.core.content.edit
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.CastState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The put.io Cast receiver, which picks the stream from the LOAD's `customData`. */
internal const val PUTIO_RECEIVER_APPLICATION_ID = "E3A4ADFA"

/** The put.io receiver's own messages: its load state, and the request for it. */
internal const val PUTIO_CAST_NAMESPACE = "urn:x-cast:io.put.cast"

internal const val PUTIO_GET_STATUS_MESSAGE = """{"type":"GET_STATUS"}"""

internal fun receiverApplicationId(putio: Boolean): String =
    if (putio) PUTIO_RECEIVER_APPLICATION_ID else CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

/**
 * Which receiver the next Cast session launches. Cast chooses it as a session starts, so a change
 * made while one is live waits for that session to end.
 */
internal class CastReceiverSelection(applied: String) {
    var applied: String = applied
        private set

    var wanted: String = applied
        private set

    /** Asks for [applicationId]; returns the app ID to apply now, or null to apply nothing. */
    fun want(applicationId: String, sessionLive: Boolean): String? {
        wanted = applicationId
        return if (sessionLive) null else apply()
    }

    /** The session ended or never started: a waiting change applies now. */
    fun sessionGone(): String? = apply()

    /** The Cast context refused [applicationId]: it still runs [previous], and a later ask retries. */
    fun refused(applicationId: String, previous: String) {
        if (applied == applicationId) applied = previous
    }

    private fun apply(): String? {
        if (wanted == applied) return null
        applied = wanted
        return applied
    }
}

/** The signed-in account's receiver choice; the next Cast session launches it. */
internal fun MobileCast.useReceiver(context: Context, putio: Boolean) {
    (controller(context)?.gateway as? GmsCastGateway)?.receivers?.use(putio)
}

/**
 * Switches the Cast framework's receiver between sessions, and hands the search the selector that
 * comes with it. A Cast context that refuses the switch keeps the receiver it has.
 */
internal class CastReceiverSwitch(
    private val context: Context,
    private val reselect: (MediaRouteSelector?) -> Unit,
) {
    private var cast: CastContextHandle? = null
    private var selection: CastReceiverSelection? = null
    private var wanted: String? = null

    fun bind(cast: CastContextHandle) {
        this.cast = cast
        selection = CastReceiverSelection(cast.receiverApplicationId)
        wanted?.let(::want)
    }

    /** Uses the put.io receiver for the next session, or Google's when [putio] is false. */
    fun use(putio: Boolean) {
        want(receiverApplicationId(putio))
    }

    /** The session ended or never started: a choice made during it applies now. */
    fun sessionGone() {
        selection?.sessionGone()?.let(::apply)
    }

    private fun want(applicationId: String) {
        wanted = applicationId
        val live = cast?.castState.let { it == CastState.CONNECTING || it == CastState.CONNECTED }
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
            // Stored once applied, so a fresh process resumes the session with the receiver it runs.
            CastReceiverStore.save(context, applicationId == PUTIO_RECEIVER_APPLICATION_ID)
            reselect(cast.mergedSelector)
        }
    }
}

/**
 * Hears the put.io receiver's own word on its load, from the session that carries it. A session
 * that went away mid-call has nothing more to say, so a failed call is dropped.
 */
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

/**
 * The receiver the last signed-in account chose, for the Cast options of a fresh process: a session
 * Cast resumes then is one of that receiver.
 */
internal object CastReceiverStore {
    fun putio(context: Context): Boolean = preferences(context).getBoolean(PUTIO_KEY, false)

    fun save(context: Context, putio: Boolean) {
        preferences(context).edit { putBoolean(PUTIO_KEY, putio) }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val PREFERENCES_NAME = "cast_receiver"
    private const val PUTIO_KEY = "putio"
}

/** What the put.io receiver said of its load; [fileId] is null when it could not tell. */
internal sealed interface PutioReceiverState {
    data object Idle : PutioReceiverState

    data class Loading(val fileId: Long?) : PutioReceiverState

    data class Loaded(val fileId: Long?) : PutioReceiverState

    data class Failed(val fileId: Long?, val reason: String) : PutioReceiverState
}

/** A receiver STATUS message, or null for any other message. */
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
