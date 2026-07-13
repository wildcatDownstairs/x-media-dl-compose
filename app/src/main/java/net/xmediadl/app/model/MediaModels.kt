package net.xmediadl.app.model

import android.os.Environment

/**
 * 一个可由用户单独点击下载的媒体资源。
 *
 * [url] 是本次解析得到的实际下载地址，可能是短期地址，不能作为跨启动持久判重键；
 * [fileSuffix] 则描述资源在帖子中的稳定槽位，例如第二段视频或视频封面，同时用于避免
 * 同一帖子中的多个文件重名。
 */
data class MediaItem(
    val label: String,
    val url: String,
    val type: MediaType,
    val quality: Int = 0,
    val fileSuffix: String = "",
)

/** 解析结果中的展示单元；视频可带封面，独立图片则单独占一个单元。 */
sealed interface MediaEntry

/** 一段视频及其可选封面下载项。 */
data class VideoEntry(
    val video: MediaItem,
    val cover: MediaItem?,
) : MediaEntry

/** 不依附于视频的图片下载项。 */
data class PhotoEntry(
    val photo: MediaItem,
) : MediaEntry

/** 媒体类型同时决定 MIME、扩展名以及写入系统媒体库的目录。 */
enum class MediaType {
    Video,
    Photo,
}

/**
 * SaveTwitter 页面被转换后的领域模型。
 *
 * [postUrl] 在解析完成后由 ViewModel 回填为用户输入的原始帖子地址；[elapsedMs] 只用于
 * 结果页展示解析耗时，不参与下载或缓存逻辑。
 */
data class ResolvedPost(
    val title: String,
    val duration: String,
    val thumbnailUrl: String?,
    val mediaEntries: List<MediaEntry>,
    val elapsedMs: Long,
    val postUrl: String = "",
)

/**
 * 历史页按帖子聚合后的只读模型。
 *
 * 数据库中每个媒体占一行，这个模型把同一 [postUrl] 的行合并为一张卡片；本地
 * [previewPath] 优先于可能失效的远端 [previewUrl]。
 */
data class DownloadHistoryPost(
    val postUrl: String,
    val title: String,
    val itemCount: Int,
    val lastDownloadedAt: Long,
    val previewUrl: String?,
    val previewPath: String?,
    val previewFileName: String?,
    val previewMediaType: String?,
)

/** 用户点击已下载资源后，交给确认弹窗暂存的完整下载上下文。 */
data class PendingDownload(
    val item: MediaItem,
    val postUrl: String,
    val postTitle: String,
    val previewUrl: String?,
    val previewPath: String?,
)

/** 返回 MediaStore 文件名所需的标准扩展名。 */
fun MediaItem.fileExtension(): String = when (type) {
    MediaType.Video -> "mp4"
    MediaType.Photo -> "jpg"
}

/** 返回写入 MediaStore 时声明的 MIME 类型。 */
fun MediaItem.mimeType(): String = when (type) {
    MediaType.Video -> "video/mp4"
    MediaType.Photo -> "image/jpeg"
}

/** 图片进入 Pictures，视频进入 Movies，二者下方还会创建 XMediaDL 子目录。 */
fun MediaItem.galleryDirectory(): String = when (type) {
    MediaType.Video -> Environment.DIRECTORY_MOVIES
    MediaType.Photo -> Environment.DIRECTORY_PICTURES
}
