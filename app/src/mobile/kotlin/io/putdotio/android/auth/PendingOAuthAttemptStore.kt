package io.putdotio.android.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal data class PendingOAuthAttempt(
    val state: String,
    val createdAtEpochMillis: Long,
) {
    init {
        require(state.isNotEmpty() && state.length <= MAX_OAUTH_STATE_LENGTH) {
            "Pending OAuth state has an invalid length"
        }
        require(state.all { it.code in OAUTH_STATE_CHARACTER_RANGE }) {
            "Pending OAuth state has invalid characters"
        }
        require(createdAtEpochMillis > 0) {
            "Pending OAuth attempt has an invalid timestamp"
        }
    }
}

internal fun interface OAuthAttemptClock {
    fun nowEpochMillis(): Long
}

internal data object SystemOAuthAttemptClock : OAuthAttemptClock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}

internal interface PendingOAuthAttemptStore {
    suspend fun read(): PendingOAuthAttempt?

    suspend fun write(attempt: PendingOAuthAttempt)

    suspend fun clear()
}

internal class PendingOAuthAttemptStorageException(
    operation: String,
    cause: Throwable? = null,
) : Exception("Pending OAuth attempt storage failed during $operation", cause)

// commit() is intentional: a browser launch is not allowed until the pending
// state is durable across process death.
@SuppressLint("ApplySharedPref", "UseKtx")
internal class SharedPreferencesPendingOAuthAttemptStore internal constructor(
    private val preferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher,
) : PendingOAuthAttemptStore {
    constructor(context: Context) : this(
        preferences = context.getSharedPreferences(AUTH_PREFERENCES_NAME, Context.MODE_PRIVATE),
        ioDispatcher = Dispatchers.IO,
    )

    override suspend fun read(): PendingOAuthAttempt? = withContext(ioDispatcher) {
        pendingAttemptStorageOperation(PENDING_ATTEMPT_READ_OPERATION) {
            val state = preferences.getString(PENDING_OAUTH_STATE_KEY, null)
            val hasTimestamp = preferences.contains(PENDING_OAUTH_CREATED_AT_KEY)
            if (state == null && !hasTimestamp) {
                return@pendingAttemptStorageOperation null
            }
            require(state != null && hasTimestamp) {
                "Pending OAuth attempt is incomplete"
            }
            PendingOAuthAttempt(
                state = state,
                createdAtEpochMillis = preferences.getLong(PENDING_OAUTH_CREATED_AT_KEY, 0),
            )
        }
    }

    override suspend fun write(attempt: PendingOAuthAttempt) = withContext(ioDispatcher) {
        pendingAttemptStorageOperation(PENDING_ATTEMPT_WRITE_OPERATION) {
            val committed = preferences.edit()
                .putString(PENDING_OAUTH_STATE_KEY, attempt.state)
                .putLong(PENDING_OAUTH_CREATED_AT_KEY, attempt.createdAtEpochMillis)
                .commit()
            if (!committed) {
                throw PendingOAuthAttemptStorageException(PENDING_ATTEMPT_WRITE_OPERATION)
            }
        }
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        pendingAttemptStorageOperation(PENDING_ATTEMPT_CLEAR_OPERATION) {
            val committed = preferences.edit()
                .remove(PENDING_OAUTH_STATE_KEY)
                .remove(PENDING_OAUTH_CREATED_AT_KEY)
                .commit()
            if (!committed) {
                throw PendingOAuthAttemptStorageException(PENDING_ATTEMPT_CLEAR_OPERATION)
            }
        }
    }
}

private inline fun <T> pendingAttemptStorageOperation(
    operation: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (error: PendingOAuthAttemptStorageException) {
        throw error
    } catch (error: IOException) {
        throw PendingOAuthAttemptStorageException(operation, error)
    } catch (error: IllegalArgumentException) {
        throw PendingOAuthAttemptStorageException(operation, error)
    } catch (error: ClassCastException) {
        throw PendingOAuthAttemptStorageException(operation, error)
    }

internal const val PENDING_OAUTH_STATE_KEY = "pending_oauth_state_v1"
internal const val PENDING_OAUTH_CREATED_AT_KEY = "pending_oauth_created_at_v1"

private const val MAX_OAUTH_STATE_LENGTH = 1_024
private val OAUTH_STATE_CHARACTER_RANGE = 0x21..0x7e
private const val PENDING_ATTEMPT_READ_OPERATION = "read"
private const val PENDING_ATTEMPT_WRITE_OPERATION = "write"
private const val PENDING_ATTEMPT_CLEAR_OPERATION = "clear"
