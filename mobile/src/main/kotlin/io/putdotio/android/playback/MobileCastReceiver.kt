package io.putdotio.android.playback

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
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

    private fun apply(): String? {
        if (wanted == applied) return null
        applied = wanted
        return applied
    }
}

/**
 * The receiver the last signed-in account chose, for the Cast options of a fresh process: a session
 * Cast resumes then is one of that receiver.
 */
internal object CastReceiverStore {
    fun putio(context: Context): Boolean = preferences(context).getBoolean(PUTIO_KEY, false)

    fun save(context: Context, putio: Boolean) {
        preferences(context).edit().putBoolean(PUTIO_KEY, putio).apply()
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

/**
 * Whether this report fails the load of [fileId]: a failure for that file, or one the receiver could
 * not tie to a file while that load is still [inFlight].
 */
internal fun PutioReceiverState.failsLoad(fileId: Long, inFlight: Boolean): Boolean =
    this is PutioReceiverState.Failed && (this.fileId == fileId || (this.fileId == null && inFlight))

/** A receiver STATUS message, or null for any other message. */
internal fun parsePutioReceiverStatus(message: String): PutioReceiverState? {
    val root = runCatching { Json.parseToJsonElement(message) }.getOrNull() as? JsonObject ?: return null
    if (root.string("type") != "STATUS") return null
    val state = root["state"] as? JsonObject ?: return null
    val fileId = (state["fileId"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0L }
    return when (state.string("status")) {
        "idle" -> PutioReceiverState.Idle
        "loading" -> PutioReceiverState.Loading(fileId)
        "loaded" -> PutioReceiverState.Loaded(fileId)
        "failed" -> state.string("reason")?.let { PutioReceiverState.Failed(fileId, it) }
        else -> null
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
