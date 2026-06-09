package net.xmediadl.app.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xmediadl.app.utils.desktopUserAgent
import java.net.HttpURLConnection
import java.net.URL

object RemoteImageLoader {
    suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        // 缩略图只是辅助展示。加载失败时返回 null，UI 会显示占位，
        // 这样图片 CDN 临时不可用也不会影响真正的下载按钮。
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", desktopUserAgent)
            }
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
}
