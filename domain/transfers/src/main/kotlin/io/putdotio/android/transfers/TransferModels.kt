package io.putdotio.android.transfers

import java.net.IDN
import java.net.URI

private const val MAX_PORT = 65_535

@JvmInline
public value class TransferId(public val value: Long)

@JvmInline
public value class TransferFileId(public val value: Long)

@JvmInline
public value class TransferCursor(internal val value: String) {
    init {
        require(value.isNotBlank()) { "A transfer cursor cannot be blank" }
    }
}

public sealed interface AppTransferStatus {
    public val isTerminal: Boolean

    public data object Waiting : AppTransferStatus { override val isTerminal: Boolean = false }
    public data object PreparingDownload : AppTransferStatus { override val isTerminal: Boolean = false }
    public data object Queued : AppTransferStatus { override val isTerminal: Boolean = false }
    public data object Downloading : AppTransferStatus { override val isTerminal: Boolean = false }
    public data object WaitingForCompleteQueue : AppTransferStatus { override val isTerminal: Boolean = false }
    public data object WaitingForDownloader : AppTransferStatus { override val isTerminal: Boolean = false }
    public data object Completing : AppTransferStatus { override val isTerminal: Boolean = false }
    public data object Stopping : AppTransferStatus { override val isTerminal: Boolean = false }
    public data object Seeding : AppTransferStatus { override val isTerminal: Boolean = false }
    public data object PreparingSeed : AppTransferStatus { override val isTerminal: Boolean = false }
    public data object Completed : AppTransferStatus { override val isTerminal: Boolean = true }
    public data object Failed : AppTransferStatus { override val isTerminal: Boolean = true }
    public data class Unknown(val value: String) : AppTransferStatus { override val isTerminal: Boolean = false }
}

public val AppTransferStatus.canCancel: Boolean
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

public val TransferItem.canOpen: Boolean
    get() =
        fileId != null &&
            userFileExists != false &&
            status in setOf(
                AppTransferStatus.Completed,
                AppTransferStatus.Seeding,
                AppTransferStatus.PreparingSeed,
            )

public data class TransferItem(
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
    /** put.io's own failure reason, trimmed; null when the API sends none. */
    val errorMessage: String?,
    val createdAt: String,
    val userFileExists: Boolean?,
)

public data class TransfersPage(
    val items: List<TransferItem>,
    val nextCursor: TransferCursor?,
)

public data class TransfersRowRefresh(
    val items: List<TransferItem>,
    val missingIds: Set<TransferId>,
)

@JvmInline
public value class TransferSubmission private constructor(public val value: String) {
    public companion object {
        public fun parse(input: String): TransferSubmission? {
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

        /** Every whitespace-separated link, or null when any is invalid, none is given, or there are too many. */
        public fun parseAll(input: String): List<TransferSubmission>? {
            val links = input.split(LinkSeparator)
                .filter(String::isNotEmpty)
                .map { parse(it) ?: return null }
                .distinct()
            return links.takeIf { it.isNotEmpty() && it.size <= MAX_TRANSFER_LINKS }
        }
    }
}

/** put.io's `TRANSFER_MULTI_ADD_LIMIT` for one `/transfers/add-multi` call. */
public const val MAX_TRANSFER_LINKS: Int = 100

private val LinkSeparator = Regex("[\\s\\p{Z}\\u0085]+")

/** A `.torrent` the user picked or shared; its bytes stay in memory and never reach logs. */
public class TorrentUpload(public val fileName: String, public val content: ByteArray) {
    init {
        require(fileName.endsWith(TORRENT_EXTENSION, ignoreCase = true)) { "A torrent upload needs a .torrent name" }
    }

    override fun toString(): String = "TorrentUpload(<redacted>, size=${content.size})"

    public companion object {
        public const val TORRENT_EXTENSION: String = ".torrent"
    }
}

/** One confirmed submission, saved to [saveParentId] or, when null, the account's default download folder. */
public sealed interface TransferAddRequest {
    public val saveParentId: Long?

    public data class Links(
        val links: List<TransferSubmission>,
        override val saveParentId: Long? = null,
    ) : TransferAddRequest {
        init {
            require(links.isNotEmpty() && links.size <= MAX_TRANSFER_LINKS) {
                "Add between 1 and $MAX_TRANSFER_LINKS links"
            }
        }

        override fun toString(): String = "Links(<redacted> x${links.size}, saveParentId=$saveParentId)"
    }

    public data class Torrent(
        val file: TorrentUpload,
        override val saveParentId: Long? = null,
    ) : TransferAddRequest
}

/** put.io adds each link on its own; [rejectedLinks] are the ones it refused. */
public data class TransferAddOutcome(
    val added: List<TransferItem>,
    val rejectedLinks: List<String> = emptyList(),
) {
    override fun toString(): String = "TransferAddOutcome(added=${added.size}, rejected=${rejectedLinks.size})"
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
