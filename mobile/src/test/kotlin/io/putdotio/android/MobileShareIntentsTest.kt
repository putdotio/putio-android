package io.putdotio.android

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.transfers.MOBILE_TRANSFER_INPUT_LIMIT
import io.putdotio.android.transfers.MobileShareValidation
import io.putdotio.android.transfers.MAX_TORRENT_BYTES
import io.putdotio.android.transfers.MobileIncomingTransfer
import io.putdotio.android.transfers.MobileSharedTransfer
import io.putdotio.android.transfers.consumeMobileIncomingTransfer
import io.putdotio.android.transfers.parseMobileMagnetLink
import io.putdotio.android.transfers.isOwnProviderAuthority
import io.putdotio.android.transfers.readMobileTorrent
import io.putdotio.android.transfers.torrentFileName
import io.putdotio.android.transfers.fitsMobileTransferInputLimit
import io.putdotio.android.transfers.parseMobileSharedTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider

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
    fun standaloneLinkInSharedTextIsExtractedWithoutChangingItsCharacters() {
        for ((text, expected) in listOf(
            "Episode title\nhttps://example.invalid/episode\nSent from a browser" to "https://example.invalid/episode",
            "Download https://example.invalid/file?token=abc'def now" to "https://example.invalid/file?token=abc'def",
            "Download magnet:?xt=urn:btih:12345&dn=hello%20world now" to "magnet:?xt=urn:btih:12345&dn=hello%20world",
            "https://example.invalid/file\nAgain: https://example.invalid/file" to "https://example.invalid/file",
        )) {
            val parsed = parseMobileSharedTransfer(text)
            assertEquals(expected, parsed.input)
            assertNull(parsed.validation)
        }
    }

    @Test
    fun unicodeWhitespaceSeparatesCompleteSharedTokens() {
        for (separator in listOf("\u00a0", "\u202f", "\u3000", "\u0085")) {
            val expected = "https://example.invalid/file"
            val parsed = parseMobileSharedTransfer("Download$separator$expected${separator}now")
            assertEquals(expected, parsed.input)
            assertNull(parsed.validation)
        }
    }

    @Test
    fun everyCompleteLinkInSharedTextBecomesOneLinePerLink() {
        val multiple = "One https://example.invalid/a and two magnet:?xt=urn:btih:12345\nhttps://example.invalid/a"
        val parsed = parseMobileSharedTransfer(multiple)
        assertEquals("https://example.invalid/a\nmagnet:?xt=urn:btih:12345", parsed.input)
        assertNull(parsed.validation)
        val pasted =
            parseMobileSharedTransfer(" magnet:?xt=urn:btih:1\n\nmagnet:?xt=urn:btih:2 https://example.invalid/b ")
        assertEquals("magnet:?xt=urn:btih:1\nmagnet:?xt=urn:btih:2\nhttps://example.invalid/b", pasted.input)
        val tooMany = (0..100).joinToString(" ") { "see https://example.invalid/$it" }
        assertEquals(MobileShareValidation.TooManyLinks, parseMobileSharedTransfer(tooMany).validation)
        assertEquals(tooMany, parseMobileSharedTransfer(tooMany).input)
    }

    @Test
    fun anIncompleteLinkKeepsTheWholeTextEditableWithoutAnArbitrarySelection() {
        val mixed = "One https://example.invalid/a and two magnet:?dn=missing-hash"
        assertEquals(mixed, parseMobileSharedTransfer(mixed).input)
        assertEquals(MobileShareValidation.InvalidLink, parseMobileSharedTransfer(mixed).validation)
        for (text in listOf(
            "no link 東京 été", "", "ftp://example.invalid/file", "https://", "magnet:?dn=missing-hash",
        )) {
            val invalid = parseMobileSharedTransfer(text)
            assertEquals(text, invalid.input)
            assertEquals(MobileShareValidation.InvalidLink, invalid.validation)
        }
    }

    @Test
    fun wrappedMagnetCandidatesPreventChoosingAnotherSharedLink() {
        for (wrapped in listOf("(magnet:?xt=urn:btih:12345)", "[MAGNET:?xt=urn:btih:12345]")) {
            val text = "Download https://example.invalid/a or $wrapped"
            val parsed = parseMobileSharedTransfer(text)
            assertEquals(text, parsed.input)
            assertEquals(MobileShareValidation.InvalidLink, parsed.validation)
        }
    }

    @Test
    fun ambiguousSentencePunctuationKeepsTheOriginalEditableText() {
        for (ending in listOf(".", ",", ";", ":", "!", "?", "'", ")", "]", "}", "…", "。", "—", "𐄀")) {
            val text = "Download https://example.invalid/file?token=abc$ending"
            val parsed = parseMobileSharedTransfer(text)
            assertEquals(text, parsed.input)
            assertEquals(MobileShareValidation.InvalidLink, parsed.validation)
        }
    }

    @Test
    fun surroundingWrappersNeverCauseUrlCharactersToBeStripped() {
        for (text in listOf(
            "Watch <https://example.invalid/episode>",
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
    fun nestedUrlsAndInvalidCharactersNeverBecomeADifferentValidLink() {
        for (text in listOf(
            "Download https://example.invalid/file?signature=abc\"def now",
            "Download https://example.invalid/file?signature=abc<def now",
            "Download https://example.invalid/file?signature=abc>def now",
            "Download ftp://example.invalid/file?redirect=https://example.invalid/other now",
            "Download redirect=https://example.invalid/file now",
        )) {
            val parsed = parseMobileSharedTransfer(text)
            assertEquals(text, parsed.input)
            assertEquals(MobileShareValidation.InvalidLink, parsed.validation)
        }
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
        for (result in listOf(parseMobileSharedTransfer(text), requireNotNull(intent.consumeShared()))) {
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
        val consumed = requireNotNull(intent.consumeShared())
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
            val result = requireNotNull(intent.consumeShared())
            assertEquals("", result.input)
            assertEquals(MobileShareValidation.InvalidLink, result.validation)
            assertTrue(intent.extras?.isEmpty ?: true)
        }
    }

    @Test
    fun tappedMagnetLinksOpenTheDraftAndLeaveNothingInTheIntent() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Harbor%20film"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(magnet))
        val draft = intent.consumeShared()
        assertEquals(magnet, draft.input)
        assertNull(draft.validation)
        assertNull(intent.data)
        assertFalse(intent.toUri(Intent.URI_INTENT_SCHEME).contains("btih"))
    }

    @Test
    fun malformedMagnetLinksStayVisibleButCanNotBeSubmitted() {
        for (link in listOf("magnet:?dn=missing-hash", "magnet:xt=urn:btih:abc", "MAGNET:?xt=")) {
            val parsed = requireNotNull(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).consumeShared())
            assertEquals(link, parsed.input)
            assertEquals(MobileShareValidation.InvalidLink, parsed.validation)
        }
        assertEquals(
            MobileShareValidation.InvalidLink,
            parseMobileMagnetLink("https://example.invalid/file").validation,
        )
        val oversized = parseMobileMagnetLink("magnet:?xt=urn:btih:1&dn=" + "a".repeat(MOBILE_TRANSFER_INPUT_LIMIT))
        assertEquals("", oversized.input)
        assertEquals(MobileShareValidation.TooLong, oversized.validation)
    }

    @Test
    fun torrentViewsAndSendsHandOverOnlyTheirContentUri() {
        val uri = android.net.Uri.parse("content://downloads.example/torrents/7")
        val viewed = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-bittorrent")
        val sent = Intent(Intent.ACTION_SEND).setType("application/x-bittorrent").putExtra(Intent.EXTRA_STREAM, uri)
        for (intent in listOf(viewed, sent)) {
            assertEquals(uri, (intent.consumeMobileIncomingTransfer() as MobileIncomingTransfer.Torrent).uri)
            assertNull(intent.data)
            assertTrue(intent.extras?.isEmpty ?: true)
        }
        val fileUri = Intent(Intent.ACTION_SEND).setType("application/x-bittorrent")
            .putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse("file:///sdcard/a.torrent"))
        assertEquals(MobileShareValidation.InvalidTorrent, fileUri.consumeShared().validation)
        val missing = Intent(Intent.ACTION_SEND).setType("application/x-bittorrent")
        assertEquals(MobileShareValidation.InvalidTorrent, missing.consumeShared().validation)
    }

    @Test
    fun torrentContentIsBoundedAndMustLookLikeMetainfo() {
        val metainfo = "d8:announce3:url4:infod4:name5:Harbore".toByteArray()
        val read = readMobileTorrent("Harbor film.torrent") { metainfo.inputStream() }
        assertEquals("Harbor film.torrent", read.torrent?.fileName)
        assertTrue(metainfo.contentEquals(read.torrent?.content))
        assertNull(read.validation)
        assertFalse(read.toString().contains("Harbor"))
        for (bytes in listOf(ByteArray(0), "not a torrent".toByteArray(), "d3:keyi1ee".toByteArray())) {
            assertEquals(
                MobileShareValidation.InvalidTorrent,
                readMobileTorrent("a.torrent") { bytes.inputStream() }.validation,
            )
        }
        assertEquals(MobileShareValidation.InvalidTorrent, readMobileTorrent("a.torrent") { null }.validation)
        assertEquals(
            MobileShareValidation.InvalidTorrent,
            readMobileTorrent("a.torrent") { throw java.io.IOException("revoked") }.validation,
        )
        var served = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 'd'.code.also { served++ }
        }
        assertEquals(MobileShareValidation.TorrentTooLarge, readMobileTorrent("a.torrent") { endless }.validation)
        assertTrue(served <= MAX_TORRENT_BYTES + 1L)
    }

    @Test
    fun torrentProvidersAreUntrustedAndThisAppsOwnAreRefused() {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        Robolectric.buildContentProvider(TorrentProvider::class.java).create("test.torrents")
        Robolectric.buildContentProvider(TorrentProvider::class.java).create("io.put.sample.share")
        Robolectric.buildContentProvider(BrokenProvider::class.java).create("test.broken")
        val served = resolver.readMobileTorrent(Uri.parse("content://test.torrents/1"), "io.put.sample")
        assertEquals("Harbor film.torrent", served.torrent?.fileName)
        assertEquals(
            MobileShareValidation.InvalidTorrent,
            resolver.readMobileTorrent(Uri.parse("content://io.put.sample.share/1"), "io.put.sample").validation,
        )
        for (own in listOf("io.put.sample", "io.put.sample.share", "0@io.put.sample.share", "10@io.put.sample", null)) {
            assertTrue(own.toString(), isOwnProviderAuthority(own, "io.put.sample"))
        }
        for (other in listOf("test.torrents", "io.put.samplex.share", "0@test.torrents")) {
            assertFalse(other, isOwnProviderAuthority(other, "io.put.sample"))
        }
        assertEquals(
            MobileShareValidation.InvalidTorrent,
            resolver.readMobileTorrent(Uri.parse("content://test.broken/1"), "io.put.sample").validation,
        )
    }

    @Test
    fun torrentNamesAlwaysEndInTorrentWithoutPathsOrControlCharacters() {
        assertEquals("Harbor film.torrent", torrentFileName("Harbor film.torrent"))
        assertEquals("Archive été 東京.torrent", torrentFileName("Archive été 東京.TORRENT"))
        assertEquals("Sample folder.torrent", torrentFileName("../../Sample folder"))
        assertEquals("evil.torrent", torrentFileName("e\u0000v\nil"))
        assertEquals("Transfer.torrent", torrentFileName(null))
        assertEquals("Transfer.torrent", torrentFileName(" .torrent "))
        assertEquals(200 + ".torrent".length, torrentFileName("a".repeat(500)).length)
    }

    @Test
    fun otherActionsAndMimeTypesDoNotEnterTheTextShareFlow() {
        for (intent in listOf(
            Intent(Intent.ACTION_VIEW).setType("text/plain"),
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain"),
            Intent(Intent.ACTION_SEND).setType("image/png"),
            Intent(MobilePlaybackService.ACTION_OPEN_NOW_PLAYING),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://app.put.io/files/1")),
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("putio://transfers")),
        )) {
            intent.putExtra("existing", "preserved")
            assertNull(intent.consumeMobileIncomingTransfer())
            assertEquals("preserved", intent.getStringExtra("existing"))
        }
    }
}

private fun Intent.consumeShared(): MobileSharedTransfer =
    (requireNotNull(consumeMobileIncomingTransfer()) as MobileIncomingTransfer.Ready).transfer

private open class TorrentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?,
    ): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("Harbor film.torrent")) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = java.io.File.createTempFile("torrent", ".torrent").apply {
            writeBytes("d8:announce3:url4:infod4:name5:Harbore".toByteArray())
            deleteOnExit()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/x-bittorrent"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}

private class BrokenProvider : TorrentProvider() {
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        sort: String?,
    ): Cursor =
        throw UnsupportedOperationException("no queries here")
}
