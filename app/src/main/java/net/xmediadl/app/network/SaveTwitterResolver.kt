package net.xmediadl.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xmediadl.app.model.MediaEntry
import net.xmediadl.app.model.MediaItem
import net.xmediadl.app.model.MediaType
import net.xmediadl.app.model.PhotoEntry
import net.xmediadl.app.model.ResolvedPost
import net.xmediadl.app.model.VideoEntry
import net.xmediadl.app.utils.desktopUserAgent
import net.xmediadl.app.utils.htmlUnescape
import net.xmediadl.app.utils.readTextSafely
import net.xmediadl.app.utils.stripTags
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.max

class SaveTwitterResolver {
    suspend fun resolve(postUrl: String): ResolvedPost = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()

        // SaveTwitter 首页提交表单时会 POST 到 ajaxSearch。
        // 返回值外层是 JSON，但真正的下载按钮在 data 里的 HTML 片段中。
        val body = "q=${URLEncoder.encode(postUrl, StandardCharsets.UTF_8.name())}&lang=en&cftoken="
        val connection = (URL("https://savetwitter.net/api/ajaxSearch").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Origin", "https://savetwitter.net")
            setRequestProperty("Referer", "https://savetwitter.net/en4")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("User-Agent", desktopUserAgent)
        }
        connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

        val response = connection.readTextSafely()
        val json = JSONObject(response)
        val html = json.optString("data")
        if (json.optString("status") != "ok" || html.isBlank()) {
            throw IllegalStateException(json.optString("msg", "没有解析到可下载媒体。"))
        }

        parseResolvedPost(html, System.currentTimeMillis() - startedAt)
    }

    private fun parseResolvedPost(html: String, elapsedMs: Long): ResolvedPost {
        val thumbnail = Regex("""<img\s+src="([^"]+)""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.htmlUnescape()

        val title = Regex("""<h3[^>]*>(.*?)</h3>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.stripTags()
            .orEmpty()

        val duration = Regex("""<p>\s*([0-9:]+)\s*</p>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        val mediaEntries = mutableListOf<MediaEntry>()
        val videoRun = mutableListOf<MediaItem>()
        var pendingVideo: MediaItem? = null

        fun flushBestVideoToPending() {
            // SaveTwitter 会把同一个视频的多个清晰度连续放出来。
            // App 只显示最高质量，因此先缓存一组 MP4，再取 quality 最大的那个。
            videoRun.maxByOrNull { it.quality }?.let { pendingVideo = it }
            videoRun.clear()
        }

        fun commitPendingVideoWithoutCover() {
            pendingVideo?.let {
                mediaEntries += VideoEntry(video = it, cover = null)
                pendingVideo = null
            }
        }

        Regex("""<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .forEach { match ->
                val url = match.groupValues[1].htmlUnescape()
                val label = match.groupValues[2].stripTags()
                when {
                    label.contains("Download MP4", ignoreCase = true) -> {
                        val quality = Regex("""\((\d+)p\)""").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                        val previousQuality = videoRun.lastOrNull()?.quality ?: Int.MAX_VALUE
                        if (videoRun.isNotEmpty() && quality >= previousQuality) {
                            flushBestVideoToPending()
                            commitPendingVideoWithoutCover()
                        }
                        commitPendingVideoWithoutCover()
                        videoRun += MediaItem(
                            label = "Download MP4 (${max(quality, 0)}p)",
                            url = url,
                            type = MediaType.Video,
                            quality = quality,
                        )
                    }
                    label.contains("Download Photo", ignoreCase = true) -> {
                        flushBestVideoToPending()
                        val photo = MediaItem(label = "Download Photo", url = url, type = MediaType.Photo)
                        val video = pendingVideo
                        if (video != null) {
                            // 视频卡片里的 Download Photo 通常是封面，
                            // 所以和视频按钮并排展示，而不是当作独立图片资源。
                            mediaEntries += VideoEntry(video = video, cover = photo)
                            pendingVideo = null
                        } else {
                            mediaEntries += PhotoEntry(photo)
                        }
                    }
                    else -> flushBestVideoToPending()
                }
            }
        flushBestVideoToPending()
        commitPendingVideoWithoutCover()

        val dedupedMediaEntries = mediaEntries.distinctBy {
            when (it) {
                is VideoEntry -> "video:${it.video.url}:${it.cover?.url.orEmpty()}"
                is PhotoEntry -> "photo:${it.photo.url}"
            }
        }
        if (dedupedMediaEntries.isEmpty()) {
            throw IllegalStateException("没有找到视频或图片下载项。")
        }

        return ResolvedPost(
            title = title,
            duration = duration,
            thumbnailUrl = thumbnail,
            mediaEntries = labelMediaEntries(dedupedMediaEntries),
            elapsedMs = elapsedMs,
        )
    }

    private fun labelMediaEntries(mediaEntries: List<MediaEntry>): List<MediaEntry> {
        val videoCount = mediaEntries.count { it is VideoEntry }
        val photoCount = mediaEntries.count { it is PhotoEntry }
        var videoIndex = 1
        var photoIndex = 1

        return mediaEntries.map { entry ->
            when (entry) {
                is VideoEntry -> {
                    val index = videoIndex++
                    val video = entry.video.copy(
                        label = if (videoCount == 1) "Download MP4" else "Download MP4 $index",
                        fileSuffix = if (videoCount == 1) "" else "-video-$index",
                    )
                    val cover = entry.cover?.copy(
                        label = if (videoCount == 1) "Download Cover" else "Cover $index",
                        fileSuffix = if (videoCount == 1) "-cover" else "-video-$index-cover",
                    )
                    VideoEntry(video = video, cover = cover)
                }
                is PhotoEntry -> {
                    val index = photoIndex++
                    PhotoEntry(
                        entry.photo.copy(
                            label = if (photoCount == 1) "Download Photo" else "Download Photo $index",
                            fileSuffix = if (photoCount == 1) "" else "-photo-$index",
                        ),
                    )
                }
            }
        }
    }
}
