package io.putdotio.android.transfers

/**
 * The Add transfer field, checked one line at a time: a line is good when every link on it parses,
 * and only good lines go. [badLines] are 1-based line numbers; [badRanges] are their characters in
 * the field, for dimming them in place.
 */
internal class MobileTransferLines(
    val links: List<TransferSubmission>,
    val badLines: List<Int>,
    val badRanges: List<IntRange>,
    val badText: List<String>,
) {
    override fun toString(): String = "MobileTransferLines(links=${links.size}, badLines=$badLines)"
}

internal fun checkMobileTransferLines(input: String): MobileTransferLines {
    val links = mutableListOf<TransferSubmission>()
    val badLines = mutableListOf<Int>()
    val badRanges = mutableListOf<IntRange>()
    val badText = mutableListOf<String>()
    var start = 0
    input.split('\n').forEachIndexed { index, line ->
        val tokens = line.split(SharedWhitespace).filter(String::isNotEmpty)
        val parsed = tokens.map(TransferSubmission::parse)
        if (parsed.any { it == null }) {
            badLines += index + 1
            badRanges += start until start + line.length
            badText += line.trim()
        } else {
            links += parsed.filterNotNull()
        }
        start += line.length + 1
    }
    return MobileTransferLines(links.distinct(), badLines, badRanges, badText)
}
