package io.putdotio.android.files

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FilesFileSizeTest {
    @Test
    fun keepsOneDecimalBelowAHundredAndDropsAZeroOne() {
        val sizes = mapOf(
            128L to "128 B",
            41_000L to "41 kB",
            31_000_000L to "31 MB",
            3_200_000_000L to "3.2 GB",
            // Android's short formatter writes "19 GB" here; the folder line needs the decimal.
            19_400_000_000L to "19.4 GB",
            1_040_000_000L to "1 GB",
            250_400_000L to "250 MB",
        )
        sizes.forEach { (bytes, text) -> assertEquals(text, formatFileSize(bytes, Locale.US)) }
    }
}
