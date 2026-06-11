package net.xmediadl.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xmediadl.app.network.RemoteImageLoader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class HistoryPreviewStore(context: Context) {
    private val appContext = context.applicationContext
    private val previewDirectory = File(context.filesDir, "history-previews").apply { mkdirs() }

    suspend fun ensurePreview(
        postUrl: String,
        previewUrl: String?,
        previewFileName: String? = null,
        previewMediaType: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val previewFile = File(previewDirectory, "${postUrl.sha256()}.jpg")
        if (previewFile.exists() && previewFile.length() > 0L) {
            return@withContext previewFile.absolutePath
        }

        // 新下载记录优先走解析阶段拿到的封面图，命中率最高。
        // 旧历史记录如果远端封面失效，再退回系统相册里已经下载过的本地媒体文件重建预览。
        val bitmap = when {
            !previewUrl.isNullOrBlank() -> RemoteImageLoader.loadBitmap(previewUrl)
            else -> null
        } ?: loadGalleryBitmap(previewFileName, previewMediaType)

        bitmap?.takeIf { it.writeJpeg(previewFile) }?.let { previewFile.absolutePath }
    }

    suspend fun loadBitmap(previewPath: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (previewPath.isNullOrBlank()) {
            return@withContext null
        }

        runCatching {
            val file = File(previewPath)
            if (!file.exists()) null else BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    suspend fun deletePreview(previewPath: String?) = withContext(Dispatchers.IO) {
        if (previewPath.isNullOrBlank()) {
            return@withContext
        }

        runCatching {
            val file = File(previewPath)
            if (file.exists()) {
                file.delete()
            }
        }
        Unit
    }

    private fun loadGalleryBitmap(fileName: String?, mediaType: String?): Bitmap? {
        if (fileName.isNullOrBlank() || mediaType.isNullOrBlank()) {
            return null
        }

        val contentUri = findGalleryUri(fileName, mediaType) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                appContext.contentResolver.loadThumbnail(contentUri, Size(720, 720), null)
            }.getOrNull()
        } else {
            loadLegacyGalleryBitmap(contentUri, mediaType)
        }
    }

    private fun findGalleryUri(fileName: String, mediaType: String): Uri? {
        val isVideo = mediaType.equals("Video", ignoreCase = true)
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(fileName, "%XMediaDL%")
        } else {
            arrayOf(fileName)
        }

        appContext.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val id = cursor.getLong(0)
            return Uri.withAppendedPath(collection, id.toString())
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun loadLegacyGalleryBitmap(contentUri: Uri, mediaType: String): Bitmap? {
        if (mediaType.equals("Photo", ignoreCase = true)) {
            return runCatching {
                appContext.contentResolver.openInputStream(contentUri)?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }

        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val path = appContext.contentResolver.query(
            contentUri,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                cursor.getString(0)
            }
        } ?: return null

        return ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
    }
}

private fun Bitmap.writeJpeg(targetFile: File): Boolean {
    return runCatching {
        FileOutputStream(targetFile).use { output ->
            compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
    }.getOrDefault(false)
}

private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
