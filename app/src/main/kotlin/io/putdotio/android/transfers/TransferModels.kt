package io.putdotio.android.transfers

import java.net.IDN
import java.net.URI

private const val MAX_PORT = 65_535

@JvmInline
value class TransferId(val value: Long)

@JvmInline
value class TransferFileId(val value: Long)

@JvmInline
value class TransferCursor(val value: String) {
    init {
        require(value.isNotBlank()) { "A transfer cursor cannot be blank" }
    }
}

sealed interface AppTransferStatus {
    val isTerminal: Boolean

    data object Waiting : AppTransferStatus { override val isTerminal = false }
    data object PreparingDownload : AppTransferStatus { override val isTerminal = false }
    data object Queued : AppTransferStatus { override val isTerminal = false }
    data object Downloading : AppTransferStatus { override val isTerminal = false }
    data object WaitingForCompleteQueue : AppTransferStatus { override val isTerminal = false }
    data object WaitingForDownloader : AppTransferStatus { override val isTerminal = false }
    data object Completing : AppTransferStatus { override val isTerminal = false }
    data object Stopping : AppTransferStatus { override val isTerminal = false }
    data object Seeding : AppTransferStatus { override val isTerminal = false }
    data object PreparingSeed : AppTransferStatus { override val isTerminal = false }
    data object Completed : AppTransferStatus { override val isTerminal = true }
    data object Failed : AppTransferStatus { override val isTerminal = true }
    data class Unknown(val value: String) : AppTransferStatus { override val isTerminal = false }
}

internal val AppTransferStatus.canCancel: Boolean
    get() =
        when (this) {
            AppTransferStatus.Completed,
            AppTransferStatus.Failed,
            is AppTransferStatus.Unknown,
            -> false
            AppTransferStatus.Waiting,
            AppTransferStatus.PreparingDownload,
            AppTransferStatus.Queued,
            AppTransferStatus.Downloading,
            AppTransferStatus.WaitingForCompleteQueue,
            AppTransferStatus.WaitingForDownloader,
            AppTransferStatus.Completing,
            AppTransferStatus.Stopping,
            AppTransferStatus.Seeding,
            AppTransferStatus.PreparingSeed,
            -> true
        }

internal val TransferItem.canOpen: Boolean
    get() =
        fileId != null &&
            userFileExists != false &&
            status in setOf(
                AppTransferStatus.Completed,
                AppTransferStatus.Seeding,
                AppTransferStatus.PreparingSeed,
            )

data class TransferItem(
    val id: TransferId,
    val name: String,
    val status: AppTransferStatus,
    val fileId: TransferFileId?,
    val sizeBytes: Double?,
    val percentDone: Double?,
    val downloadSpeedBytesPerSecond: Double?,
    val uploadSpeedBytesPerSecond: Double?,
    val estimatedSecondsRemaining: Double?,
    val availability: Double?,
    val hasError: Boolean,
    val createdAt: String,
    val userFileExists: Boolean?,
)

data class TransfersPage(
    val items: List<TransferItem>,
    val nextCursor: TransferCursor?,
)

data class TransfersRowRefresh(
    val items: List<TransferItem>,
    val missingIds: Set<TransferId>,
)

@JvmInline
value class TransferSubmission private constructor(val value: String) {
    companion object {
        fun parse(input: String): TransferSubmission? {
            val value = input.trim()
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val valid =
                when (uri.scheme?.lowercase()) {
                    "http", "https" -> uri.hasHttpHost()
                    "magnet" ->
                        uri.rawSchemeSpecificPart.startsWith("?") &&
                            uri.rawSchemeSpecificPart
                                .removePrefix("?")
                                .split("&")
                                .any { parameter ->
                                    val parts = parameter.split("=", limit = 2)
                                    parts.first() == "xt" && parts.getOrElse(1) { "" }.isNotBlank()
                                }
                    else -> false
                }
            return value.takeIf { valid }?.let(::TransferSubmission)
        }
    }
}

private fun URI.hasHttpHost(): Boolean {
    val serverAuthority =
        rawAuthority
            ?.serverAuthority()
    val hostFromAuthority = serverAuthority?.httpHost()
    return when {
        hostFromAuthority == null -> false
        serverAuthority.startsWith('[') -> host?.removeSurrounding("[", "]") == hostFromAuthority
        else -> hostFromAuthority.isValidDnsHost()
    }
}

private fun String.serverAuthority(): String? =
    takeIf { count { character -> character == '@' } <= 1 }
        ?.substringAfterLast('@')
        ?.takeIf(String::isNotBlank)

private fun String.isValidDnsHost(): Boolean =
    runCatching { IDN.toASCII(this, IDN.USE_STD3_ASCII_RULES) }
        .getOrNull()
        ?.removeSuffix(".")
        ?.let { host ->
            host.isNotBlank() &&
                host.length <= MAX_DNS_HOST_LENGTH &&
                host.split('.').all { label -> label.isNotEmpty() && label.length <= MAX_DNS_LABEL_LENGTH }
        } == true

private fun String.httpHost(): String? =
    if (startsWith("[")) {
        val closingBracket = indexOf(']')
        takeIf {
            closingBracket >= 2 && substring(closingBracket + 1).hasValidPortSuffix()
        }?.substring(1, closingBracket)
    } else {
        if (count { it == ':' } > 1) return null
        val host = substringBeforeLast(':', this)
        host.takeIf { it.isNotBlank() && removePrefix(host).hasValidPortSuffix() }
    }

private fun String.hasValidPortSuffix(): Boolean =
    isEmpty() ||
        (startsWith(':') &&
            drop(1).isNotEmpty() &&
            drop(1).all { it in '0'..'9' } &&
            drop(1).toIntOrNull()?.let { it in 0..MAX_PORT } == true)

private const val MAX_DNS_HOST_LENGTH = 253
private const val MAX_DNS_LABEL_LENGTH = 63
