package io.putdotio.android.files

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileFileSizeTest {
    @Test
    fun keepsOneDecimalBelowAHundredAndDropsAZeroOne() {
        val sizes = mapOf(
            128L to "128B",
            41_000L to "41kB",
            31_000_000L to "31MB",
            3_200_000_000L to "3.2GB",
            // Android's short formatter writes "19 GB" here; the folder line needs the decimal.
            19_400_000_000L to "19.4GB",
            1_040_000_000L to "1GB",
            250_400_000L to "250MB",
        )
        sizes.forEach { (bytes, text) -> assertEquals(text, formatFileSize(bytes, Locale.US)) }
    }
}
