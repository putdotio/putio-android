package io.putdotio.android.files

import io.putdotio.android.PutioFailure
import io.putdotio.android.PutioResult
import io.putdotio.android.findPutioApiException
import io.putdotio.android.putioRequest
import io.putdotio.android.toPutioFailure
import io.putdotio.sdk.PutioClient
import io.putdotio.sdk.config.AppConfig
import io.putdotio.sdk.config.AppConfigUpdate
import io.putdotio.sdk.errors.PutioException
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

public interface PinnedFiles : Closeable {
    public val pins: StateFlow<List<FilesItemId>?>

    public val failure: StateFlow<PutioFailure?>

    public val editFailure: StateFlow<PutioFailure?>

    public fun pin(id: FilesItemId)

    public fun unpin(id: FilesItemId)

    public fun reload()

    public fun dismissEditFailure()
}

public class AppConfigPinnedFiles(
    private val loadConfig: suspend () -> AppConfig,
    private val saveConfig: suspend (AppConfigUpdate) -> Unit,
    parentScope: CoroutineScope,
) : PinnedFiles {
    public constructor(client: PutioClient, parentScope: CoroutineScope) : this(
        loadConfig = client.appConfig::get,
        saveConfig = { update -> client.appConfig.save(update) },
        parentScope = parentScope,
    )

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val commands = Channel<PinCommand>(Channel.UNLIMITED)
    private val mutablePins = MutableStateFlow<List<FilesItemId>?>(null)
    private val mutableFailure = MutableStateFlow<PutioFailure?>(null)
    private val mutableEditFailure = MutableStateFlow<PutioFailure?>(null)

    override val pins: StateFlow<List<FilesItemId>?> = mutablePins.asStateFlow()
    override val failure: StateFlow<PutioFailure?> = mutableFailure.asStateFlow()
    override val editFailure: StateFlow<PutioFailure?> = mutableEditFailure.asStateFlow()

    init {
        scope.launch {
            read()
            for (command in commands) {
                when (command) {
                    PinCommand.Reload -> read()
                    is PinCommand.Edit -> edit(command)
                }
            }
        }
    }

    override fun pin(id: FilesItemId) {
        commands.trySend(PinCommand.Edit(id, pinned = true))
    }

    override fun unpin(id: FilesItemId) {
        commands.trySend(PinCommand.Edit(id, pinned = false))
    }

    override fun reload() {
        commands.trySend(PinCommand.Reload)
    }

    override fun dismissEditFailure() {
        mutableEditFailure.value = null
    }

    override fun close() {
        commands.close()
        scope.cancel()
    }

    private suspend fun read(): List<FilesItemId>? =
        when (val result = putioRequest(PutioException::toConfigFailure) { loadConfig().pinnedFiles() }) {
            is PutioResult.Success -> result.value.also {
                mutablePins.value = it
                mutableFailure.value = null
            }
            is PutioResult.Failure -> {
                mutableFailure.value = result.failure
                null
            }
        }

    private suspend fun edit(edit: PinCommand.Edit) {
        val current = read() ?: run {
            mutableEditFailure.value = mutableFailure.value
            return
        }
        val next = if (edit.pinned) {
            (listOf(edit.id) + (current - edit.id)).take(MAX_PINNED_FILES)
        } else {
            current - edit.id
        }
        if (next == current) return
        mutablePins.value = next
        when (val result = putioRequest(PutioException::toConfigFailure) { saveConfig(pinnedFilesUpdate(next)) }) {
            is PutioResult.Success -> mutableFailure.value = null
            is PutioResult.Failure -> {
                mutablePins.value = current
                mutableFailure.value = result.failure
                mutableEditFailure.value = result.failure
            }
        }
    }
}

private sealed interface PinCommand {
    data class Edit(val id: FilesItemId, val pinned: Boolean) : PinCommand

    data object Reload : PinCommand
}

internal fun AppConfig.pinnedFiles(): List<FilesItemId> =
    (this[PINNED_FILES_KEY] as? JsonArray)
        ?.mapNotNull { value ->
            (value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull?.takeIf { it > 0L }
        }
        ?.distinct()
        ?.take(MAX_PINNED_FILES)
        ?.map(::FilesItemId)
        .orEmpty()

internal fun pinnedFilesUpdate(ids: List<FilesItemId>): AppConfigUpdate =
    AppConfigUpdate(key = PINNED_FILES_KEY, value = JsonArray(ids.map { JsonPrimitive(it.value) }))

private fun PutioException.toConfigFailure(): PutioFailure =
    if (findPutioApiException()?.errorType == INVALID_SCOPE_ERROR_TYPE) {
        PutioFailure.AccessDenied(this)
    } else {
        toPutioFailure()
    }

public const val PINNED_FILES_KEY: String = "pinned_files"
internal const val MAX_PINNED_FILES = 40
private const val INVALID_SCOPE_ERROR_TYPE = "invalid_scope"
