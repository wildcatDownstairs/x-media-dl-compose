package net.xmediadl.app.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xmediadl.app.utils.desktopUserAgent
import java.net.HttpURLConnection
import java.net.URL

/**
 * 只负责加载界面缩略图的轻量网络组件。
 *
 * 缩略图不是下载主流程的必要条件，因此这里采用“失败即返回 null”的策略，调用方统一
 * 显示占位图；真正媒体下载仍由 [net.xmediadl.app.download.GalleryDownloader] 负责并
 * 传播明确错误。
 */
object RemoteImageLoader {
    /** 在 IO 调度器中下载并解码位图；任何网络或图片格式异常都会降级为 null。 */
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
