package io.putdotio.android.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.putdotio.android.AccountStorage
import io.putdotio.android.account.InactiveAccountNotice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The account put.io last confirmed for the stored token. A restore put.io cannot answer opens the
 * shell offline with it, as a running app keeps the account it validated when the network goes.
 * It is written only after put.io confirms the token, and cleared before any other token is stored.
 */
internal interface ValidatedAccountStore {
    /** Null when there is none or the record cannot be read. */
    suspend fun read(): MobileAccount?

    /** Best effort: a failed write only costs the next offline start its shell. */
    suspend fun write(account: MobileAccount)

    /** False when the record could not be removed. */
    suspend fun clear(): Boolean
}

internal object NoValidatedAccounts : ValidatedAccountStore {
    override suspend fun read(): MobileAccount? = null

    override suspend fun write(account: MobileAccount) = Unit

    override suspend fun clear(): Boolean = true
}

// commit() is intentional: a token is stored only once the previous token's account is gone.
@SuppressLint("ApplySharedPref", "UseKtx")
internal class SharedPreferencesValidatedAccountStore internal constructor(
    private val preferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher,
) : ValidatedAccountStore {
    constructor(context: Context) : this(
        preferences = context.getSharedPreferences(VALIDATED_ACCOUNT_PREFERENCES_NAME, Context.MODE_PRIVATE),
        ioDispatcher = Dispatchers.IO,
    )

    override suspend fun read(): MobileAccount? = withContext(ioDispatcher) {
        runCatching { preferences.readAccount() }.getOrNull()
    }

    override suspend fun write(account: MobileAccount) {
        withContext(ioDispatcher) {
            runCatching { preferences.edit().clear().putAccount(account).commit() }
        }
    }

    override suspend fun clear(): Boolean = withContext(ioDispatcher) {
        runCatching { preferences.edit().clear().commit() }.getOrDefault(false)
    }
}

private fun SharedPreferences.readAccount(): MobileAccount? {
    val userId = getLong(KEY_USER_ID, 0L).takeIf { getInt(KEY_VERSION, 0) == RECORD_VERSION && it > 0L }
    val username = getString(KEY_USERNAME, null)
    val email = getString(KEY_EMAIL, null)
    if (userId == null || username == null || email == null) return null
    return MobileAccount(
        userId = userId,
        username = username,
        email = email,
        historyEnabled = getBoolean(KEY_HISTORY_ENABLED, false),
        avatarUrl = getString(KEY_AVATAR_URL, null),
        storage = AccountStorage(
            availableBytes = getLong(KEY_STORAGE_AVAILABLE, 0L),
            sizeBytes = getLong(KEY_STORAGE_SIZE, 0L),
            usedBytes = getLong(KEY_STORAGE_USED, 0L),
            showOptimisticUsage = getBoolean(KEY_STORAGE_OPTIMISTIC, false),
        ),
        inactiveNotice = readInactiveNotice(),
        diagnosticsEnabled = getBoolean(KEY_DIAGNOSTICS_ENABLED, false),
    )
}

private fun SharedPreferences.readInactiveNotice(): InactiveAccountNotice? =
    when (getString(KEY_INACTIVE, null)) {
        INACTIVE_DEACTIVATED -> InactiveAccountNotice.Deactivated(
            filesDeletedAt = if (contains(KEY_FILES_DELETED_AT)) {
                Instant.ofEpochMilli(getLong(KEY_FILES_DELETED_AT, 0L))
            } else {
                null
            },
        )
        INACTIVE_FAMILY_PLAN_EXPIRED -> InactiveAccountNotice.FamilyPlanExpired
        else -> null
    }

private fun SharedPreferences.Editor.putAccount(account: MobileAccount): SharedPreferences.Editor {
    putInt(KEY_VERSION, RECORD_VERSION)
    putLong(KEY_USER_ID, account.userId)
    putString(KEY_USERNAME, account.username)
    putString(KEY_EMAIL, account.email)
    putBoolean(KEY_HISTORY_ENABLED, account.historyEnabled)
    account.avatarUrl?.let { putString(KEY_AVATAR_URL, it) }
    putLong(KEY_STORAGE_AVAILABLE, account.storage.availableBytes)
    putLong(KEY_STORAGE_SIZE, account.storage.sizeBytes)
    putLong(KEY_STORAGE_USED, account.storage.usedBytes)
    putBoolean(KEY_STORAGE_OPTIMISTIC, account.storage.showOptimisticUsage)
    when (val notice = account.inactiveNotice) {
        is InactiveAccountNotice.Deactivated -> {
            putString(KEY_INACTIVE, INACTIVE_DEACTIVATED)
            notice.filesDeletedAt?.let { putLong(KEY_FILES_DELETED_AT, it.toEpochMilli()) }
        }
        InactiveAccountNotice.FamilyPlanExpired -> putString(KEY_INACTIVE, INACTIVE_FAMILY_PLAN_EXPIRED)
        null -> Unit
    }
    putBoolean(KEY_DIAGNOSTICS_ENABLED, account.diagnosticsEnabled)
    return this
}

private const val VALIDATED_ACCOUNT_PREFERENCES_NAME = "putio_validated_account"
private const val RECORD_VERSION = 1
private const val KEY_VERSION = "version"
private const val KEY_USER_ID = "user_id"
private const val KEY_USERNAME = "username"
private const val KEY_EMAIL = "email"
private const val KEY_HISTORY_ENABLED = "history_enabled"
private const val KEY_AVATAR_URL = "avatar_url"
private const val KEY_STORAGE_AVAILABLE = "storage_available"
private const val KEY_STORAGE_SIZE = "storage_size"
private const val KEY_STORAGE_USED = "storage_used"
private const val KEY_STORAGE_OPTIMISTIC = "storage_optimistic"
private const val KEY_INACTIVE = "inactive"
private const val KEY_FILES_DELETED_AT = "files_deleted_at"
private const val KEY_DIAGNOSTICS_ENABLED = "diagnostics_enabled"
private const val INACTIVE_DEACTIVATED = "deactivated"
private const val INACTIVE_FAMILY_PLAN_EXPIRED = "family_plan_expired"
