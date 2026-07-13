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

/**
 * 下载历史缩略图的本地文件仓库。
 *
 * 每个帖子以 URL 的 SHA-256 作为文件名，避免特殊字符和不同标题造成路径问题。新记录
 * 优先缓存解析接口给出的封面；旧记录的远端地址失效后，可根据历史文件名从 MediaStore
 * 找回相册文件并重建缩略图。所有磁盘和媒体库访问都切到 IO 调度器。
 *
 * 注意：构造函数会创建 [previewDirectory]，因此 Compose `@Preview` 不能实例化本类；
 * Layoutlib 不提供完整的应用文件目录。UI 层会在 inspection mode 下跳过创建。
 */
class HistoryPreviewStore(context: Context) {
    private val appContext = context.applicationContext
    private val previewDirectory = File(context.filesDir, "history-previews").apply { mkdirs() }

    /** 返回已有缩略图，或从远端/相册生成后返回其绝对路径；全部失败时返回 null。 */
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

    /** 安全读取本地缩略图，路径为空、文件丢失或解码失败都按无预览处理。 */
    suspend fun loadBitmap(previewPath: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (previewPath.isNullOrBlank()) {
            return@withContext null
        }

        runCatching {
            val file = File(previewPath)
            if (!file.exists()) null else BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    /** 删除 App 私有目录中的缩略图；不会触碰系统相册里的原始图片或视频。 */
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

    /** 根据历史行保存的文件名，在 MediaStore 中寻找原媒体并生成适合历史卡片的位图。 */
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

    /**
     * 只查询 XMediaDL 子目录中的同名文件，优先最近写入项。
     * Android 9 没有 RELATIVE_PATH，因此旧系统只能退化为按显示名查找。
     */
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

    /** Android 9 及以下没有 loadThumbnail，图片直接解码，视频通过旧 ThumbnailUtils 生成。 */
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

/** 以固定质量写入 App 私有缓存；异常转换为 false，避免缩略图失败影响下载成功状态。 */
private fun Bitmap.writeJpeg(targetFile: File): Boolean {
    return runCatching {
        FileOutputStream(targetFile).use { output ->
            compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
    }.getOrDefault(false)
}

/** 为帖子 URL 生成固定长度、文件系统安全的缓存键。 */
private fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
