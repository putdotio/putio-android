package io.putdotio.android.tv

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvLauncherArtProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the fallback art decodes as a 16 by 9 image`() {
        Robolectric.setupContentProvider(TvLauncherArtProvider::class.java, "${context.packageName}.launcherart")

        val art = context.contentResolver.openInputStream(Uri.parse(tvLauncherFallbackArt(context))).use {
            BitmapFactory.decodeStream(it)
        }

        assertEquals(art.width * 9, art.height * 16)
    }

    @Test
    fun `other paths are not served`() {
        Robolectric.setupContentProvider(TvLauncherArtProvider::class.java, "${context.packageName}.launcherart")

        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openInputStream(Uri.parse("content://${context.packageName}.launcherart/other.png"))
        }
    }
}
