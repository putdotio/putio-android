package io.putdotio.android.tv

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.putdotio.android.R
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.tv.auth.TvLinkFailure
import io.putdotio.android.tv.auth.TvLinkPhase
import io.putdotio.android.tv.auth.TvLinkStop

@Composable
internal fun TvLinkScreen(
    phase: TvLinkPhase,
    sessionExpired: Boolean,
    onRequestNewCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TvWordmark(Modifier.padding(top = TV_VERTICAL_MARGIN))
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = STAGE_TOP),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TvWelcomeStage()
            Text(
                text = stringResource(if (sessionExpired) R.string.tv_link_back_title else R.string.tv_link_title),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp, lineHeight = 30.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = TITLE_TOP).semantics { heading() },
            )
        }
        if (sessionExpired) {
            Text(
                text = stringResource(R.string.tv_link_session_expired),
                style = MaterialTheme.typography.bodyMedium.withFullLineBox(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = SESSION_EXPIRED_TOP),
            )
        }
        LinkColumns(
            phase = phase,
            onRequestNewCode = onRequestNewCode,
            modifier = Modifier.padding(
                start = TV_SIDE_MARGIN,
                end = TV_SIDE_MARGIN,
                top = if (sessionExpired) COLUMNS_TOP_EXPIRED else COLUMNS_TOP,
                bottom = COLUMNS_BOTTOM,
            ),
        )
    }
}

@Composable
private fun LinkColumns(
    phase: TvLinkPhase,
    onRequestNewCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val code = phase.shownCode()
    val dimmed = phase is TvLinkPhase.Stopped || code == null
    Row(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        ) {
            ColumnTitle(stringResource(R.string.tv_link_phone_title))
            LinkQr(code = code, dimmed = dimmed)
            Text(
                text = stringResource(R.string.tv_link_phone_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(DIVIDER_GAP))
        OrDivider()
        Spacer(Modifier.width(DIVIDER_GAP))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        ) {
            ColumnTitle(stringResource(R.string.tv_link_browser_title))
            BrowserInstruction()
            phase.statusText()?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = STATUS_LINE).withFullLineBox(),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            CodeTiles(code = code, dimmed = dimmed)
            NewCodeButton(enabled = phase !is TvLinkPhase.Validating, onClick = onRequestNewCode)
        }
    }
}

@Composable
private fun ColumnTitle(text: String) {
    Text(
        text = text,
        style = PutioTypeScale.bodyMd.copy(fontWeight = FontWeight.Medium, lineHeight = COLUMN_TITLE_LINE)
            .withFullLineBox(),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun BrowserInstruction() {
    val url = stringResource(R.string.tv_link_url)
    val sentence = stringResource(R.string.tv_link_browser_instruction, url)
    val start = sentence.indexOf(url)
    Text(
        text = buildAnnotatedString {
            append(sentence)
            if (start >= 0) {
                addStyle(
                    SpanStyle(color = PutioDesignTokens.yellowSolid, fontWeight = FontWeight.Medium),
                    start,
                    start + url.length,
                )
            }
        },
        style = MaterialTheme.typography.bodyMedium.withFullLineBox(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LinkQr(
    code: String?,
    dimmed: Boolean,
) {
    val shape = RoundedCornerShape(PutioRadius.largeIncreased)
    val light = MaterialTheme.colorScheme.inverseSurface
    Box(
        modifier = Modifier
            .size(QR_SIZE)
            .alpha(if (dimmed) DIMMED_ALPHA else 1f)
            .clip(shape)
            .background(light)
            .padding(QR_PADDING),
    ) {
        if (code != null) {
            TvQrCode(
                text = tvLinkPageUrl(code),
                description = stringResource(R.string.tv_link_qr_description),
                dark = MaterialTheme.colorScheme.background,
                light = light,
                modifier = Modifier.fillMaxSize().testTag(TV_LINK_QR_TAG),
            )
        }
    }
}

internal fun tvLinkPageUrl(code: String): String = "https://app.put.io/link?code=${Uri.encode(code)}"

@Composable
private fun CodeTiles(
    code: String?,
    dimmed: Boolean,
) {
    val characters = code?.map(Char::toString) ?: List(PLACEHOLDER_TILES) { "" }
    val description = code?.let { stringResource(R.string.tv_link_code_description, it) }
    val textColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) {
            if (description != null) contentDescription = description
        },
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        characters.forEach { character ->
            Box(
                modifier = Modifier
                    .size(width = TILE_WIDTH, height = TILE_HEIGHT)
                    .background(PutioDesignTokens.componentBgActive, RoundedCornerShape(PutioRadius.medium)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = character,
                    style = PutioTypeScale.tvMono.copy(fontSize = 30.sp, lineHeight = 36.sp),
                    color = textColor,
                )
            }
        }
    }
}

@Composable
private fun NewCodeButton(enabled: Boolean, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        withFrameNanos {}
        focus.requestFocus()
    }
    TvButton(
        onClick = onClick,
        enabled = enabled,
        style = TvButtonStyle.Text,
        size = TvButtonSize.Compact,
        modifier = Modifier.focusRequester(focus),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ph_arrows_clockwise),
            contentDescription = null,
            modifier = Modifier.size(TvButtonSize.Compact.icon),
        )
        Text(stringResource(R.string.tv_link_new_code), style = PutioTypeScale.tvLabel)
    }
}

@Composable
private fun OrDivider() {
    Box(
        modifier = Modifier
            .width(DIVIDER_WIDTH)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.borderVariant)
            .wrapContentWidth(unbounded = true),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.tv_link_or),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 6.dp),
        )
    }
}

private fun TvLinkPhase.shownCode(): String? =
    when (this) {
        TvLinkPhase.RequestingCode -> null
        is TvLinkPhase.AwaitingLink -> code
        is TvLinkPhase.Validating -> code
        is TvLinkPhase.Stopped -> code
    }

@Composable
private fun TvLinkPhase.statusText(): String? =
    when (this) {
        TvLinkPhase.RequestingCode -> stringResource(R.string.tv_link_requesting)
        is TvLinkPhase.AwaitingLink -> null
        is TvLinkPhase.Validating -> stringResource(R.string.tv_link_validating)
        is TvLinkPhase.Stopped -> stringResource(reason.message())
    }

private fun TvLinkStop.message(): Int =
    when (this) {
        TvLinkStop.CodeExpired -> R.string.tv_link_expired
        TvLinkStop.StorageUnavailable -> R.string.tv_link_failed_storage
        is TvLinkStop.Failed ->
            when (failure) {
                TvLinkFailure.NETWORK -> R.string.tv_link_failed_network
                TvLinkFailure.MISCONFIGURED -> R.string.tv_link_failed_misconfigured
                TvLinkFailure.SERVER -> R.string.tv_link_failed_server
            }
    }

internal const val TV_LINK_QR_TAG = "tv-link-qr"

private val STAGE_TOP = 75.dp
private val TITLE_TOP = 20.dp
private val COLUMNS_TOP = 235.dp
private val COLUMNS_TOP_EXPIRED = 245.dp
private val COLUMNS_BOTTOM = 27.dp
private val COLUMN_GAP = 12.dp
private val DIVIDER_GAP = 40.dp
private val DIVIDER_WIDTH = 1.dp
private val QR_SIZE: Dp = 170.dp
private val QR_PADDING = 12.dp
private val COLUMN_TITLE_LINE = 22.sp
private val STATUS_LINE = 17.sp
private val SESSION_EXPIRED_TOP = 202.dp
private val TILE_WIDTH = 48.dp
private val TILE_HEIGHT = 60.dp
private val TILE_GAP = 4.dp
private const val PLACEHOLDER_TILES = 6
private const val DIMMED_ALPHA = 0.35f
