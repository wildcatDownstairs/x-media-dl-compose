package net.xmediadl.app.model

import android.os.Environment

data class MediaItem(
    val label: String,
    val url: String,
    val type: MediaType,
    val quality: Int = 0,
    val fileSuffix: String = "",
)

sealed interface MediaEntry

data class VideoEntry(
    val video: MediaItem,
    val cover: MediaItem?,
) : MediaEntry

data class PhotoEntry(
    val photo: MediaItem,
) : MediaEntry

enum class MediaType {
    Video,
    Photo,
}

data class ResolvedPost(
    val title: String,
    val duration: String,
    val thumbnailUrl: String?,
    val mediaEntries: List<MediaEntry>,
    val elapsedMs: Long,
    val postUrl: String = "",
)

data class DownloadHistoryPost(
    val postUrl: String,
    val title: String,
    val itemCount: Int,
    val lastDownloadedAt: Long,
    val previewUrl: String?,
)

data class PendingDownload(
    val item: MediaItem,
    val postUrl: String,
    val postTitle: String,
    val previewUrl: String?,
)

fun MediaItem.fileExtension(): String = when (type) {
    MediaType.Video -> "mp4"
    MediaType.Photo -> "jpg"
}

fun MediaItem.mimeType(): String = when (type) {
    MediaType.Video -> "video/mp4"
    MediaType.Photo -> "image/jpeg"
}

fun MediaItem.galleryDirectory(): String = when (type) {
    MediaType.Video -> Environment.DIRECTORY_MOVIES
    MediaType.Photo -> Environment.DIRECTORY_PICTURES
}
