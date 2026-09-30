package io.putdotio.android.downloads

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.putdotio.android.files.FilesItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UserScopedCacheKeysTest {
    @Test
    fun keysDropTheSessionTokenAndKeepEveryOtherParameter() {
        val signed = "https://cdn.put.io/hls/7/seg-1.ts?oauth_token=secret-a&range=0-99&tag=a&tag=b"
        val key = UserScopedCacheKeys(42L).buildCacheKey(DataSpec(Uri.parse(signed)))

        assertEquals("u42|https://cdn.put.io/hls/7/seg-1.ts?range=0-99&tag=a&tag=b", key)
        assertFalse(key.contains("oauth_token") || key.contains("secret"))
    }

    @Test
    fun anotherSessionTokenMapsToTheSameBytesAndAnotherUserDoesNot() {
        val first = DataSpec(Uri.parse("https://cdn.put.io/seg.ts?oauth_token=one"))
        val second = DataSpec(Uri.parse("https://cdn.put.io/seg.ts?oauth_token=two"))

        assertEquals(UserScopedCacheKeys(1L).buildCacheKey(first), UserScopedCacheKeys(1L).buildCacheKey(second))
        assertNotEquals(UserScopedCacheKeys(1L).buildCacheKey(first), UserScopedCacheKeys(2L).buildCacheKey(first))
    }

    @Test
    fun tokenFreeApiUrlsAreKeptVerbatim() {
        val api = DownloadArtifact.HLS.apiUrl(FilesItemId(7L))

        assertEquals("u3|$api", UserScopedCacheKeys(3L).buildCacheKey(DataSpec(Uri.parse(api))))
    }
}
