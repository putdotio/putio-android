package io.putdotio.android

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenSourceNoticeTest {
    @Test
    fun theAppSourceLinksTheReleaseTagForEveryChannel() {
        assertEquals("https://github.com/putdotio/putio-android/tree/v3.4.0", appSourceNotice("3.4.0").sourceUrl)
        assertEquals("https://github.com/putdotio/putio-android/tree/v3.4.0", appSourceNotice("3.4.0-nightly").sourceUrl)
        assertEquals("3.4.0-nightly", appSourceNotice("3.4.0-nightly").version)
    }
}
