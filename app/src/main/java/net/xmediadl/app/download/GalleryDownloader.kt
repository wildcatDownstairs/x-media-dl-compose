package net.xmediadl.app.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xmediadl.app.model.MediaItem
import net.xmediadl.app.model.fileExtension
import net.xmediadl.app.model.galleryDirectory
import net.xmediadl.app.model.mimeType
import net.xmediadl.app.utils.desktopUserAgent
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class GalleryDownloader(private val context: Context) {
    suspend fun download(item: MediaItem, title: String): String {
        val fileName = buildFileName(title, item.quality, item.fileExtension(), item.fileSuffix)
        return saveToGallery(item.url, fileName, item.mimeType(), item.galleryDirectory())
    }

    private suspend fun saveToGallery(
        url: String,
        fileName: String,
        mimeType: String,
        directory: String,
    ): String =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToGalleryWithMediaStore(url, fileName, mimeType, directory)
            } else {
                saveToGalleryWithPublicFile(url, fileName, directory)
            }
        }

    private fun saveToGalleryWithMediaStore(
        url: String,
        fileName: String,
        mimeType: String,
        directory: String,
    ): String {
        // Android 10 以后推荐通过 MediaStore 写入系统媒体库。
        // 这条路径不需要传统写存储权限，并且相册会把它识别成普通图片或视频。
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/XMediaDL")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collectionUri = when {
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collectionUri, values)
            ?: throw IllegalStateException("无法创建媒体文件。")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                downloadUrlTo(url, output)
            } ?: throw IllegalStateException("无法写入媒体文件。")

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return fileName
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            throw throwable
        }
    }

    private fun saveToGalleryWithPublicFile(url: String, fileName: String, directory: String): String {
        // Android 9 及以下没有分区存储接口，只能退回公共媒体目录。
        // Manifest 里把 WRITE_EXTERNAL_STORAGE 限制到 maxSdkVersion=28。
        val mediaDir = File(Environment.getExternalStoragePublicDirectory(directory), "XMediaDL")
        if (!mediaDir.exists() && !mediaDir.mkdirs()) {
            throw IllegalStateException("无法创建相册目录。")
        }
        val outputFile = uniqueFile(mediaDir, fileName)
        outputFile.outputStream().use { output ->
            downloadUrlTo(url, output)
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(outputFile.absolutePath),
            null,
            null,
        )
        return outputFile.name
    }

    private fun downloadUrlTo(url: String, output: OutputStream) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", desktopUserAgent)
            setRequestProperty("Referer", "https://savetwitter.net/")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            connection.inputStream.use { input ->
                input.copyTo(output)
            }
        } finally {
            connection.disconnect()
        }
    }
}

fun buildFileName(title: String, quality: Int, extension: String, fileSuffix: String = ""): String {
    // 文件名会同时经过 Android 媒体库和桌面文件系统。
    // 去掉特殊字符、压短标题，可以减少保存失败和列表显示过长的问题。
    val base = title
        .ifBlank { "x-media" }
        .replace(Regex("""[\\/:*?"<>|]"""), "")
        .replace(Regex("""\s+"""), "-")
        .take(48)
        .trim('-')
        .ifBlank { "x-media" }
    val suffix = if (quality > 0) "-${quality}p" else ""
    return "$base$fileSuffix$suffix.$extension"
}

private fun uniqueFile(directory: File, fileName: String): File {
    val dotIndex = fileName.lastIndexOf('.')
    val name = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""

    var candidate = File(directory, fileName)
    var index = 1
    while (candidate.exists()) {
        candidate = File(directory, "$name-$index$extension")
        index += 1
    }
    return candidate
}
