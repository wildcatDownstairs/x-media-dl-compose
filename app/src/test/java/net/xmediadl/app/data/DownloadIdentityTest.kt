package net.xmediadl.app.data

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadIdentityTest {
    @Test
    fun changingWrapperTokenKeepsTheSameResourceKey() {
        val source = "https://video.twimg.com/amplify_video/123/vid/avc1/1080x1920/media.mp4?tag=27"
        val firstUrl = wrapperUrl(source, issuedAt = 100)
        val secondUrl = wrapperUrl(source, issuedAt = 200)

        assertNotEquals(firstUrl, secondUrl)
        assertEquals(
            mediaResourceKey(POST_URL, firstUrl, "Video", ""),
            mediaResourceKey(POST_URL, secondUrl, "Video", ""),
        )
    }

    @Test
    fun sourceQueryParametersDoNotChangeTheResourceKey() {
        val first = wrapperUrl("https://pbs.twimg.com/media/example.jpg?name=large", issuedAt = 100)
        val second = wrapperUrl("https://pbs.twimg.com/media/example.jpg?name=orig", issuedAt = 200)

        assertEquals(
            mediaResourceKey(POST_URL, first, "Photo", ""),
            mediaResourceKey(POST_URL, second, "Photo", ""),
        )
    }

    @Test
    fun opaqueUrlsFallBackToCanonicalPostAndMediaSlot() {
        val firstPost = "https://x.com/user/status/1234567890?s=20"
        val secondPost = "https://twitter.com/i/status/1234567890"

        assertEquals(
            mediaResourceKey(firstPost, "https://example.com/get?token=first", "Video", "-video-2"),
            mediaResourceKey(secondPost, "https://example.com/get?token=second", "Video", "-video-2"),
        )
        assertNotEquals(
            mediaResourceKey(firstPost, "https://example.com/get?token=first", "Video", "-video-1"),
            mediaResourceKey(secondPost, "https://example.com/get?token=second", "Video", "-video-2"),
        )
    }

    private fun wrapperUrl(source: String, issuedAt: Int): String {
        val payload = "{\"url\":\"$source\",\"iat\":$issuedAt}"
        val encodedPayload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return "https://dl.snapcdn.app/get?token=header.$encodedPayload.signature"
    }

    private companion object {
        const val POST_URL = "https://x.com/i/status/1234567890"
    }
}
