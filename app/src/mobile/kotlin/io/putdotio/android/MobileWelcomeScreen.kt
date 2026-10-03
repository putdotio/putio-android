package io.putdotio.android

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.putdotio.android.auth.MobileSignedOutReason

internal enum class MobileWelcomeNoticeTone { Info, Error }

internal data class MobileWelcomeNotice(
    @StringRes val message: Int,
    val tone: MobileWelcomeNoticeTone,
)

internal enum class MobileWelcomeActionStyle { Primary, Secondary }

internal data class MobileWelcomeAction(
    @StringRes val label: Int,
    val style: MobileWelcomeActionStyle,
)

/** The copy and controls of one signed-out moment; layout stays in [MobileWelcomeScreen]. */
internal data class MobileWelcomeContent(
    @StringRes val title: Int,
    @StringRes val lede: Int? = null,
    val notice: MobileWelcomeNotice? = null,
    val showsProgress: Boolean = false,
    val action: MobileWelcomeAction? = null,
)

private val SignIn = MobileWelcomeAction(R.string.mobile_auth_sign_in, MobileWelcomeActionStyle.Primary)

internal fun mobileWelcomeContent(
    reason: MobileSignedOutReason?,
    canSignIn: Boolean,
): MobileWelcomeContent =
    when (if (canSignIn) reason else MobileSignedOutReason.OAuthNotConfigured) {
        null -> MobileWelcomeContent(
            title = R.string.mobile_welcome_title,
            lede = R.string.mobile_welcome_lede,
            action = SignIn,
        )

        MobileSignedOutReason.SessionExpired -> MobileWelcomeContent(
            title = R.string.mobile_welcome_back_title,
            notice = MobileWelcomeNotice(R.string.mobile_welcome_expired, MobileWelcomeNoticeTone.Info),
            action = SignIn,
        )

        MobileSignedOutReason.SignInFailed -> MobileWelcomeContent(
            title = R.string.mobile_welcome_title,
            notice = MobileWelcomeNotice(R.string.mobile_welcome_failed, MobileWelcomeNoticeTone.Error),
            action = MobileWelcomeAction(R.string.mobile_action_retry, MobileWelcomeActionStyle.Primary),
        )

        MobileSignedOutReason.SecureStorageUnavailable -> MobileWelcomeContent(
            title = R.string.mobile_welcome_title,
            notice = MobileWelcomeNotice(R.string.mobile_auth_storage_message, MobileWelcomeNoticeTone.Error),
            action = MobileWelcomeAction(R.string.mobile_auth_storage_reset, MobileWelcomeActionStyle.Primary),
        )

        // Without an OAuth client there is nothing to offer: no button.
        MobileSignedOutReason.OAuthNotConfigured -> MobileWelcomeContent(
            title = R.string.mobile_auth_not_configured_title,
            lede = R.string.mobile_auth_not_configured_message,
        )
    }

internal val MobileBrowserWelcomeContent = MobileWelcomeContent(
    title = R.string.mobile_welcome_browser_title,
    lede = R.string.mobile_welcome_browser_lede,
    showsProgress = true,
    action = MobileWelcomeAction(R.string.mobile_auth_cancel, MobileWelcomeActionStyle.Secondary),
)

@Composable
internal fun MobileWelcomeScreen(
    content: MobileWelcomeContent,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.putio_wordmark),
                contentDescription = stringResource(R.string.mobile_welcome_wordmark),
                modifier = Modifier.width(88.dp).aspectRatio(WORDMARK_ASPECT_RATIO),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(128.dp))
            WelcomeHeading(title = stringResource(content.title))
            content.lede?.let { lede ->
                Text(
                    text = stringResource(lede),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.5.em, letterSpacing = 0.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp).widthIn(max = 296.dp),
                )
            }
            content.notice?.let { notice ->
                WelcomeNotice(notice = notice, modifier = Modifier.padding(top = 32.dp))
            }
            if (content.showsProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 32.dp).size(24.dp).clearAndSetSemantics {},
                    strokeWidth = 2.5.dp,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        content.action?.let { action ->
            WelcomeAction(
                action = action,
                onClick = onAction,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .fillMaxWidth()
                    .height(56.dp),
            )
        }
    }
}

@Composable
private fun WelcomeHeading(title: String) {
    val style = MaterialTheme.typography.headlineLarge
    // The kaomoji and title are one lockup at one size; the kaomoji is set
    // heavier because its glyphs draw as hairlines.
    Text(
        text = stringResource(R.string.mobile_welcome_kaomoji),
        style = style.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.02.em,
            lineHeight = 1.em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        ),
        maxLines = 1,
        modifier = Modifier.clearAndSetSemantics {},
    )
    Text(
        text = title,
        style = style.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em, lineHeight = 1.1.em),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 32.dp).semantics { heading() },
    )
}

@Composable
private fun WelcomeNotice(
    notice: MobileWelcomeNotice,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 15.sp,
        lineHeight = 1.3.em,
        letterSpacing = 0.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    // The icon sits in a box one text line tall so it centres on the first
    // line however many lines the message wraps to.
    val firstLineHeight = with(LocalDensity.current) { (textStyle.fontSize * 1.3f).toDp() }
    @DrawableRes val icon: Int
    val tint = when (notice.tone) {
        MobileWelcomeNoticeTone.Info -> {
            icon = R.drawable.ic_ph_clock_countdown
            MaterialTheme.colorScheme.primary
        }

        MobileWelcomeNoticeTone.Error -> {
            icon = R.drawable.ic_ph_warning_circle
            MaterialTheme.colorScheme.error
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.height(firstLineHeight), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(text = stringResource(notice.message), style = textStyle)
        }
    }
}

@Composable
private fun WelcomeAction(
    action: MobileWelcomeAction,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val label: @Composable () -> Unit = {
        Text(text = stringResource(action.label), style = MaterialTheme.typography.titleMedium)
    }
    when (action.style) {
        MobileWelcomeActionStyle.Primary -> Button(onClick = onClick, modifier = modifier) { label() }
        MobileWelcomeActionStyle.Secondary -> OutlinedButton(onClick = onClick, modifier = modifier) { label() }
    }
}

private const val WORDMARK_ASPECT_RATIO = 376f / 112f
