package io.putdotio.android.transfers

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.design.putioMono

internal fun mobileTransferCardTag(id: TransferId): String = "mobile-transfer-card-${id.value}"

/**
 * A transfer card: its background fills with progress, a state dot with its word leads the meta, and
 * the trailing slot holds the time left and speed while it runs, a retry button when it failed, and a
 * chevron once it finished. A finished card opens its file; any other opens Transfer actions, and a
 * long press always does.
 */
@Composable
internal fun MobileTransferCard(
    item: TransferItem,
    busy: Boolean,
    enabled: Boolean,
    nowMillis: Long,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val finished = item.section == MobileTransferSection.Completed
    val fill = item.fillColor()
    val fraction = item.fillFraction
    val actionsLabel = stringResource(R.string.mobile_transfer_actions)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .drawBehind {
                if (fraction > 0f) {
                    val width = size.width * fraction
                    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                    drawRect(fill, topLeft = Offset(left, 0f), size = Size(width, size.height))
                }
            }
            .combinedClickable(
                enabled = enabled,
                onClickLabel = if (finished) stringResource(R.string.mobile_transfers_open) else actionsLabel,
                role = Role.Button,
                onClick = if (finished) onOpen else onActions,
                onLongClickLabel = actionsLabel,
                onLongClick = onActions,
            )
            .then(
                if (item.section == MobileTransferSection.Active && item.percentDone != null) {
                    Modifier.progressSemantics(fraction)
                } else {
                    Modifier
                },
            )
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp)
            .testTag(mobileTransferCardTag(item.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = middleCut(item.name),
                style = PutioTypeScale.bodyMd.copy(lineHeight = CARD_NAME_LINE_HEIGHT),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = item.name },
            )
            Spacer(Modifier.height(4.dp))
            MobileTransferMeta(item.dot, item.cardMeta(LocalContext.current, nowMillis))
        }
        MobileTransferCardTrailing(item, busy, enabled, onRetry)
    }
}

@Composable
private fun MobileTransferCardTrailing(item: TransferItem, busy: Boolean, enabled: Boolean, onRetry: () -> Unit) {
    val context = LocalContext.current
    val timeLeft = item.timeLeftText(context)
    val speed = item.downloadSpeedBytesPerSecond?.takeIf { it > 0.0 }?.let { item.speedText(context) }
    when {
        busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        item.section == MobileTransferSection.NeedsAttention -> {
            val label = stringResource(R.string.mobile_transfers_retry_named, item.name)
            IconButton(onClick = onRetry, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_ph_arrow_clockwise), contentDescription = label)
            }
        }
        item.section == MobileTransferSection.Completed -> Icon(
            painter = painterResource(R.drawable.ic_ph_caret_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp).size(20.dp),
        )
        timeLeft != null || speed != null -> Column(horizontalAlignment = Alignment.End) {
            timeLeft?.let {
                Text(
                    it,
                    style = PutioTypeScale.mono.copy(fontSize = PutioTypeScale.bodyMd.fontSize),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            speed?.let { MobileTransferSpeed(it) }
        }
    }
}

@Composable
private fun MobileTransferSpeed(speed: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val direction = stringResource(R.string.mobile_transfer_download_direction)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$direction $speed" },
    ) {
        Text(speed, style = PutioTypeScale.mono.copy(fontSize = PutioTypeScale.labelSm.fontSize), color = color)
        Icon(
            painterResource(R.drawable.ic_ph_arrow_down),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** A state dot and its words; numbers in [meta] set in mono. */
@Composable
internal fun MobileTransferMeta(dot: MobileTransferDot, meta: List<MobileTransferPart>, maxLines: Int = 2) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MobileTransferDotMark(dot)
        Spacer(Modifier.width(6.dp))
        Text(
            text = meta.annotated(MaterialTheme.typography.bodySmall.putioMono().toSpanStyle()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun MobileTransferDotMark(dot: MobileTransferDot) {
    val color = dot.color()
    Canvas(Modifier.size(8.dp)) {
        if (dot == MobileTransferDot.Downloading) {
            drawCircle(color.copy(alpha = DOT_HALO_ALPHA), radius = size.minDimension / 2 + 3.dp.toPx())
        }
        drawCircle(color)
    }
}

@Composable
@ReadOnlyComposable
private fun MobileTransferDot.color(): Color = when (this) {
    MobileTransferDot.Downloading -> MaterialTheme.colorScheme.primary
    MobileTransferDot.Waiting -> PutioDesignTokens.solid
    MobileTransferDot.Failed -> MaterialTheme.colorScheme.error
    MobileTransferDot.Done -> PutioDesignTokens.greenSolid
}

@Composable
@ReadOnlyComposable
private fun TransferItem.fillColor(): Color =
    if (status == AppTransferStatus.Seeding) {
        PutioDesignTokens.greenSolid.copy(alpha = SEEDING_FILL_ALPHA)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = DOWNLOADING_FILL_ALPHA)
    }

internal fun List<MobileTransferPart>.annotated(mono: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        for (part in this@annotated) {
            if (part.mono) withStyle(mono) { append(part.text) } else append(part.text)
        }
    }

/** The card's meta: the state's word, then what matters for that state. */
internal fun TransferItem.cardMeta(context: Context, nowMillis: Long): List<MobileTransferPart> {
    val details: List<List<MobileTransferPart>> = when {
        status == AppTransferStatus.Failed ->
            listOf(listOf(MobileTransferPart(errorMessage ?: context.getString(R.string.mobile_transfer_error))))
        status == AppTransferStatus.Seeding -> listOfNotNull(
            ratioText()?.let { ratio ->
                val template = context.getString(R.string.mobile_transfer_ratio, RATIO_SLOT)
                listOf(
                    MobileTransferPart(template.substringBefore(RATIO_SLOT)),
                    MobileTransferPart(ratio, mono = true),
                    MobileTransferPart(template.substringAfter(RATIO_SLOT)),
                ).filter { it.text.isNotEmpty() }
            },
        )
        else -> listOfNotNull(
            sizeParts(context).takeIf { it.isNotEmpty() },
            finishedAgo(nowMillis)?.takeIf { status == AppTransferStatus.Completed }?.let {
                listOf(MobileTransferPart(it))
            },
            // put.io can warn about a transfer that is still running ("Tracker did not respond.").
            errorMessage?.let { listOf(MobileTransferPart(it)) },
        )
    }
    return buildList {
        add(MobileTransferPart(context.getString(statusLabelRes())))
        details.forEach { detail ->
            add(MobileTransferPart(META_SEPARATOR))
            addAll(detail)
        }
    }
}

internal const val META_SEPARATOR = " · "
private const val RATIO_SLOT = "\u0001"
private val CARD_NAME_LINE_HEIGHT = 21.sp
private const val DOT_HALO_ALPHA = 0.22f
private const val DOWNLOADING_FILL_ALPHA = 0.09f
private const val SEEDING_FILL_ALPHA = 0.12f
