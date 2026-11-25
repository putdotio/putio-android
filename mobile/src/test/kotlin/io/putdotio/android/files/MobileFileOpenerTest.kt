package io.putdotio.android.files

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.sdk.files.PutioFileType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MobileFileOpenerTest {
    @Test
    fun openWithAnotherAppTypesTheIntentAsTheDocumentsProviderDoes() {
        assertEquals("text/plain", viewedType(file("README", contentType = "text/plain; charset=utf-8")))
        assertEquals("application/pdf", viewedType(file("scan", contentType = "application/pdf")))
        assertEquals("video/mp4", viewedType(file("Clip.mp4", contentType = null)))
        assertEquals("video/mp4", viewedType(file("Clip.mp4", contentType = "application/octet-stream")))
    }

    private fun viewedType(item: FilesItem): String? {
        val started = mutableListOf<Intent>()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) {
                started += intent
            }
        }
        MobileFileOpener(userId = 42L).openWithAnotherApp(context, item)
        @Suppress("DEPRECATION")
        val view = started.single().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        return view?.type
    }

    private fun file(name: String, contentType: String?) = FilesItem(
        id = FilesItemId(7L),
        parentId = FilesItemId(0L),
        name = name,
        type = PutioFileType.FILE,
        sizeBytes = 42L,
        createdAt = "2026-11-25T00:00:00",
        contentType = contentType,
    )
}
