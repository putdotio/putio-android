package io.putdotio.android

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileShareIntentsTest {
    @Test
    fun exactUrlsAndMagnetsPreserveTheirCredentialsQueryAndFragment() {
        for (input in listOf(
            "https://example.invalid/file?download_token=secret&signature=a%2Bb#section",
            "HTTP://example.invalid/a",
            "https://example.invalid/file?token=abc.",
            "magnet:?xt=urn:btih:12345&dn=Episode%20one&tr=https%3A%2F%2Ftracker.invalid",
        )) {
            val parsed = parseMobileSharedTransfer("  $input \n")
            assertEquals(input, parsed.input)
            assertNull(parsed.validation)
            assertFalse(parsed.toString().contains(input))
            assertFalse(parsed.toString().contains("secret"))
        }
    }

    @Test
    fun oneLinkInSurroundingTextIsExtractedWithoutLosingBalancedUrlCharacters() {
        for ((text, expected) in listOf(
            "Episode title\nhttps://example.invalid/episode\nSent from a browser" to "https://example.invalid/episode",
            "Download https://example.invalid/file?token=abc'def now" to "https://example.invalid/file?token=abc'def",
            "Watch <https://example.invalid/episode>" to "https://example.invalid/episode",
            "Download magnet:?xt=urn:btih:12345&dn=hello%20world now" to "magnet:?xt=urn:btih:12345&dn=hello%20world",
            "https://example.invalid/file\nAgain: https://example.invalid/file" to "https://example.invalid/file",
        )) {
            val parsed = parseMobileSharedTransfer(text)
            assertEquals(expected, parsed.input)
            assertNull(parsed.validation)
        }
    }

    @Test
    fun multipleLinksAndUnsupportedTextRemainEditableWithoutAnArbitrarySelection() {
        val mixed = "One https://example.invalid/a and two magnet:?dn=missing-hash"
        assertEquals(mixed, parseMobileSharedTransfer(mixed).input)
        assertEquals(MobileShareValidation.MultipleLinks, parseMobileSharedTransfer(mixed).validation)
        val multiple = "One https://example.invalid/a and two magnet:?xt=urn:btih:12345"
        val parsed = parseMobileSharedTransfer(multiple)
        assertEquals(multiple, parsed.input)
        assertEquals(MobileShareValidation.MultipleLinks, parsed.validation)
        for (text in listOf(
            "no link 東京 été", "", "ftp://example.invalid/file", "https://", "magnet:?dn=missing-hash",
        )) {
            val invalid = parseMobileSharedTransfer(text)
            assertEquals(text, invalid.input)
            assertEquals(MobileShareValidation.InvalidLink, invalid.validation)
        }
    }

    @Test
    fun ambiguousSentencePunctuationKeepsTheOriginalEditableText() {
        for (ending in listOf(".", ",", ";", ":", "!", "?", "'", ")", "]", "}")) {
            val text = "Download https://example.invalid/file?token=abc$ending"
            val parsed = parseMobileSharedTransfer(text)
            assertEquals(text, parsed.input)
            assertEquals(MobileShareValidation.InvalidLink, parsed.validation)
        }
    }

    @Test
    fun surroundingWrappersNeverCauseUrlCharactersToBeStripped() {
        for (text in listOf(
            "Watch (https://example.invalid/episode)",
            "Watch [(https://example.invalid/episode)]",
            "Watch {[(https://example.invalid/episode_(part_1))]}",
            "Download https://example.invalid/file?signature=abc) now",
        )) {
            val parsed = parseMobileSharedTransfer(text)
            assertEquals(text, parsed.input)
            assertEquals(MobileShareValidation.InvalidLink, parsed.validation)
        }
        val exact = "https://example.invalid/file?signature=abc)"
        assertEquals(exact, parseMobileSharedTransfer(exact).input)
        assertNull(parseMobileSharedTransfer(exact).validation)
    }

    @Test
    fun excessiveTextIsRejectedWithoutRetainingOrTruncatingItIntoALink() {
        val parsed = parseMobileSharedTransfer("https://example.invalid/" + "a".repeat(MOBILE_TRANSFER_INPUT_LIMIT))
        assertEquals("", parsed.input)
        assertEquals(MobileShareValidation.TooLong, parsed.validation)
    }

    @Test
    fun unicodeLimitCountsUtf8BytesAtTheShareBoundary() {
        val text = "https://example.invalid/" + "東".repeat(MOBILE_TRANSFER_INPUT_LIMIT / 2)
        assertTrue(text.length < MOBILE_TRANSFER_INPUT_LIMIT)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        for (result in listOf(parseMobileSharedTransfer(text), requireNotNull(intent.consumeMobileSharedTransfer()))) {
            assertEquals("", result.input)
            assertEquals(MobileShareValidation.TooLong, result.validation)
        }
        assertTrue("a".repeat(MOBILE_TRANSFER_INPUT_LIMIT).fitsMobileTransferInputLimit())
        assertTrue("東".repeat(MOBILE_TRANSFER_INPUT_LIMIT / 3).fitsMobileTransferInputLimit())
    }

    @Test
    fun consumingAShareStripsAllPayloadExtrasAndClipDataFromTheRetainedIntent() {
        val raw = "https://example.invalid/file?token=private-marker"
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, raw)
            .putExtra(Intent.EXTRA_HTML_TEXT, "<a href='$raw'>file</a>")
            .putExtra(Intent.EXTRA_SUBJECT, raw)
        intent.clipData = ClipData.newPlainText("shared", raw)
        intent.setDataAndType(android.net.Uri.parse(raw), "text/plain")
        intent.selector = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(raw))
        val consumed = requireNotNull(intent.consumeMobileSharedTransfer())
        assertEquals(raw, consumed.input)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertTrue(intent.extras?.isEmpty ?: true)
        assertNull(intent.clipData)
        assertNull(intent.data)
        assertNull(intent.selector)
        assertFalse(intent.toUri(Intent.URI_INTENT_SCHEME).contains("private-marker"))
    }

    @Test
    fun missingAndIncorrectlyTypedTextProduceValidationInsteadOfAnException() {
        val missing = Intent(Intent.ACTION_SEND).setType("text/plain")
        val wrongType = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Bundle())
        for (intent in listOf(missing, wrongType)) {
            val result = requireNotNull(intent.consumeMobileSharedTransfer())
            assertEquals("", result.input)
            assertEquals(MobileShareValidation.InvalidLink, result.validation)
            assertTrue(intent.extras?.isEmpty ?: true)
        }
    }

    @Test
    fun otherActionsAndMimeTypesDoNotEnterTheTextShareFlow() {
        for (intent in listOf(
            Intent(Intent.ACTION_VIEW).setType("text/plain"),
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain"),
            Intent(Intent.ACTION_SEND).setType("image/png"),
            Intent(MobilePlaybackService.ACTION_OPEN_NOW_PLAYING),
        )) {
            intent.putExtra("existing", "preserved")
            assertNull(intent.consumeMobileSharedTransfer())
            assertEquals("preserved", intent.getStringExtra("existing"))
        }
    }
}
