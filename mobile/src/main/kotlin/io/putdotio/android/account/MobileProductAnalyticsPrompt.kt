package io.putdotio.android.account

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.putdotio.android.MobileSheetHandle
import io.putdotio.android.BuildConfig
import io.putdotio.android.R
import io.putdotio.android.neutralTextButtonColors
import io.putdotio.android.design.PutioDesignTokens
import io.putdotio.android.design.PutioRadius
import io.putdotio.android.design.PutioTypeScale
import io.putdotio.android.settings.AccountSettingsEvent
import io.putdotio.android.settings.AccountSettingsState
import io.putdotio.android.settings.AndroidAppConfigEvent
import io.putdotio.android.settings.AndroidAppConfigState
import io.putdotio.android.settings.ProductAnalyticsAnswerProgress
import io.putdotio.android.settings.ProductAnalyticsAnswerStep
import io.putdotio.android.settings.productAnalyticsAnswerFailed
import io.putdotio.android.settings.productAnalyticsAnswerProgress
import io.putdotio.android.settings.productAnalyticsAnswerStep

internal const val MOBILE_PRODUCT_ANALYTICS_PROMPT_TAG = "mobile-product-analytics-prompt"
internal const val MOBILE_PRODUCT_ANALYTICS_ALLOW_TAG = "mobile-product-analytics-allow"
internal const val MOBILE_PRODUCT_ANALYTICS_DENY_TAG = "mobile-product-analytics-deny"

/** The launch extra a debug build needs to show the prompt; `adb shell am start --ez` sets it. */
internal const val EXTRA_HARNESS_ANALYTICS_PROMPT = "putio.harness.analytics_prompt"

/**
 * Whether the signed-in shell may ask. Debug builds keep the prompt out of harness, proof and test
 * launches, which share an account, unless the launch opts in; an opted-in launch asks whatever the
 * account and config hold, so the shared account never needs its answer reset.
 */
internal enum class MobileProductAnalyticsPromptMode {
    Ask,
    Suppressed,
    Forced,
    ;

    companion object {
        fun of(debug: Boolean, harnessAsks: Boolean): MobileProductAnalyticsPromptMode =
            when {
                !debug -> Ask
                harnessAsks -> Forced
                else -> Suppressed
            }
    }
}

// MainActivity provides the launch's mode; any other debug composition, such as a proof's, stays quiet.
internal val LocalMobileProductAnalyticsPromptMode =
    staticCompositionLocalOf { MobileProductAnalyticsPromptMode.of(BuildConfig.DEBUG, harnessAsks = false) }

/**
 * Asks once whether the account allows product analytics. An answer saves the account setting, then
 * records the answer in this app's config; a failed setting records nothing. Closing it without
 * answering writes nothing, so the next launch asks again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileProductAnalyticsPrompt(
    settingsState: AccountSettingsState,
    appConfigState: AndroidAppConfigState,
    onSettingsEvent: (AccountSettingsEvent) -> Unit,
    onAppConfigEvent: (AndroidAppConfigEvent) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // A new choice that saves its setting first sets aside the previous answer's failure; the next
    // answer write replaces it.
    var failureSetAside by rememberSaveable { mutableStateOf(false) }
    val answerFailed = appConfigState.productAnalyticsAnswerFailed()
    val progress = choice?.let {
        productAnalyticsAnswerProgress(it, settingsState, appConfigState, failureSetAside && answerFailed)
    }
    val saving by rememberUpdatedState(progress == ProductAnalyticsAnswerProgress.Saving)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !saving },
    )
    fun run(step: ProductAnalyticsAnswerStep) {
        when (step) {
            is ProductAnalyticsAnswerStep.SaveSetting -> onSettingsEvent(step.event)
            is ProductAnalyticsAnswerStep.MarkAnswered -> onAppConfigEvent(step.event)
            ProductAnalyticsAnswerStep.Wait -> Unit
        }
    }
    // Once put.io holds the setting, the answer follows; the shell also records it, whichever lands first.
    LaunchedEffect(choice, progress, settingsState, appConfigState) {
        val enabled = choice ?: return@LaunchedEffect
        if (progress != ProductAnalyticsAnswerProgress.Saving) return@LaunchedEffect
        productAnalyticsAnswerStep(enabled, settingsState, appConfigState)
            .takeIf { it is ProductAnalyticsAnswerStep.MarkAnswered }
            ?.let(::run)
    }
    LaunchedEffect(answerFailed) {
        if (!answerFailed) failureSetAside = false
    }
    LaunchedEffect(progress) {
        if (progress == ProductAnalyticsAnswerProgress.Answered) {
            sheetState.hide()
            onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
        dragHandle = { MobileSheetHandle() },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !saving),
        modifier = Modifier.testTag(MOBILE_PRODUCT_ANALYTICS_PROMPT_TAG),
    ) {
        MobileProductAnalyticsPromptContent(
            savingChoice = choice.takeIf { saving },
            failure = progress as? ProductAnalyticsAnswerProgress.Failed,
            onAnswer = { enabled ->
                val step = productAnalyticsAnswerStep(enabled, settingsState, appConfigState)
                choice = enabled
                failureSetAside = answerFailed && step is ProductAnalyticsAnswerStep.SaveSetting
                run(step)
            },
        )
    }
}

@Composable
private fun MobileProductAnalyticsPromptContent(
    savingChoice: Boolean?,
    failure: ProductAnalyticsAnswerProgress.Failed?,
    onAnswer: (Boolean) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // The sheet adds the navigation bar inset; over a gesture bar this makes the board's 28dp.
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.mobile_product_analytics_prompt_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.mobile_product_analytics_prompt_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        PromptLists()
        failure?.let {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    stringResource(R.string.mobile_settings_save_error),
                    style = PutioTypeScale.bodyMd,
                    color = PutioDesignTokens.redTextSecondary,
                )
                Text(
                    it.failure.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Equal heights when a large font wraps one label.
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnswerButton(
                label = R.string.mobile_product_analytics_prompt_deny,
                saving = savingChoice == false,
                enabled = savingChoice == null,
                onClick = { onAnswer(false) },
                modifier = Modifier.weight(1f).fillMaxHeight().testTag(MOBILE_PRODUCT_ANALYTICS_DENY_TAG),
            )
            AnswerButton(
                label = R.string.mobile_product_analytics_prompt_allow,
                saving = savingChoice == true,
                enabled = savingChoice == null,
                onClick = { onAnswer(true) },
                modifier = Modifier.weight(1f).fillMaxHeight().testTag(MOBILE_PRODUCT_ANALYTICS_ALLOW_TAG),
            )
        }
        TextButton(
            onClick = {
                try {
                    uriHandler.openUri(MOBILE_PRIVACY_POLICY_URL)
                } catch (ignored: IllegalArgumentException) {
                    // No app can open the link; there is nothing else to open it with.
                }
            },
            colors = neutralTextButtonColors(),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                stringResource(R.string.mobile_settings_privacy_policy),
                style = MaterialTheme.typography.bodySmall,
                textDecoration = TextDecoration.Underline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Side by side when the sheet is wide enough, as on iOS; stacked on a phone. */
@Composable
private fun PromptLists() {
    BoxWithConstraints {
        val never = @Composable { modifier: Modifier ->
            PromptList(
                title = R.string.mobile_product_analytics_prompt_never_title,
                items = listOf(
                    R.string.mobile_product_analytics_prompt_never_names,
                    R.string.mobile_product_analytics_prompt_never_urls,
                    R.string.mobile_product_analytics_prompt_never_tokens,
                ),
                icon = R.drawable.ic_ph_x_circle,
                iconTint = PutioDesignTokens.redTextSecondary,
                modifier = modifier,
            )
        }
        val send = @Composable { modifier: Modifier ->
            PromptList(
                title = R.string.mobile_product_analytics_prompt_send_title,
                items = listOf(
                    R.string.mobile_product_analytics_prompt_send_features,
                    R.string.mobile_product_analytics_prompt_send_device,
                    R.string.mobile_product_analytics_prompt_send_events,
                ),
                icon = R.drawable.ic_ph_check_circle,
                iconTint = PutioDesignTokens.greenSolid,
                modifier = modifier,
            )
        }
        if (maxWidth >= WIDE_LISTS_WIDTH) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                never(Modifier.weight(1f))
                send(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                never(Modifier.fillMaxWidth())
                send(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PromptList(
    @StringRes title: Int,
    items: List<Int>,
    @DrawableRes icon: Int,
    iconTint: Color,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                RoundedCornerShape(PutioRadius.large),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(title),
            style = PutioTypeScale.labelMd,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp).semantics { heading() },
        )
        val itemStyle = MaterialTheme.typography.bodyMedium
        // Sized in sp and centred on the first line, so a wrapped or scaled item keeps its glyph
        // beside where it starts.
        val firstLine = with(LocalDensity.current) { itemStyle.lineHeight.toDp() }
        val glyph = with(LocalDensity.current) { PROMPT_GLYPH_SIZE.toDp() }
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.height(firstLine), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(icon),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(glyph),
                    )
                }
                Text(stringResource(item), style = itemStyle, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun AnswerButton(
    @StringRes label: Int,
    saving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        if (saving) {
            CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
        }
        Text(stringResource(label), textAlign = TextAlign.Center)
    }
}

private val WIDE_LISTS_WIDTH = 500.dp
private val PROMPT_GLYPH_SIZE = 18.sp
