package net.xmediadl.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xmediadl.app.model.DownloadHistoryPost

/**
 * 下载历史的 SQLite 单一入口。
 *
 * 表中每个成功保存的媒体占一行，`post_url` 用于历史页聚合，`resource_key` 用于跨进程
 * 稳定判重。所有公开操作都切到 IO 调度器，UI/ViewModel 不直接拼 SQL。数据库升级只做
 * 增量迁移，绝不采用删表重建，确保安装新版 APK 时保留用户已有历史。
 *
 * Schema 版本：1 为基础记录，2 增加远端预览，3 增加本地预览，4 增加稳定资源键。
 */
class DownloadHistoryStore(context: Context) :
    SQLiteOpenHelper(context, "download_history.db", null, 4) {

    /** 新安装一次性创建当前最新版表结构和历史页常用索引。 */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE downloads (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                post_url TEXT NOT NULL,
                post_title TEXT NOT NULL,
                media_url TEXT NOT NULL UNIQUE,
                media_type TEXT NOT NULL,
                file_name TEXT NOT NULL,
                downloaded_at INTEGER NOT NULL,
                preview_url TEXT,
                preview_path TEXT,
                resource_key TEXT NOT NULL UNIQUE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_downloads_post_url ON downloads(post_url)")
        db.execSQL("CREATE INDEX idx_downloads_downloaded_at ON downloads(downloaded_at)")
    }

    /** 按版本顺序执行可叠加迁移，因此 v1 用户也可直接升级到最新版本。 */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 旧版本已经在用户手机里存过下载历史，不能为了加一个字段直接删表。
            // SQLite 支持 ALTER TABLE ADD COLUMN，适合这种向后兼容的小升级。
            db.execSQL("ALTER TABLE downloads ADD COLUMN preview_url TEXT")
        }
        if (oldVersion < 3) {
            // 仅保存远端预览 URL 在冷启动后并不可靠，CDN 或鉴权变化就会丢图。
            // 这里补一个本地预览文件路径字段，后续历史列表优先读本地文件。
            db.execSQL("ALTER TABLE downloads ADD COLUMN preview_path TEXT")
        }
        if (oldVersion < 4) {
            // SaveTwitter 的下载 URL 带短期 JWT，每次解析都可能变化，不能直接拿它做持久判重键。
            // 先无损添加可空列并回填旧记录，再合并已经产生的重复项并建立唯一索引。
            db.execSQL("ALTER TABLE downloads ADD COLUMN resource_key TEXT")
            backfillResourceKeys(db)
            db.execSQL(
                """
                DELETE FROM downloads
                WHERE resource_key IS NOT NULL
                    AND id NOT IN (
                        SELECT MAX(id)
                        FROM downloads
                        WHERE resource_key IS NOT NULL
                        GROUP BY resource_key
                    )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX idx_downloads_resource_key ON downloads(resource_key)")
        }
    }

    /**
     * 判断媒体是否已成功保存过。
     *
     * 优先比较稳定资源键；保留 `media_url` 比较是为了兼容极端异常状态下尚未回填资源键
     * 的记录。
     */
    suspend fun hasMedia(
        postUrl: String,
        mediaUrl: String,
        mediaType: String,
        fileSuffix: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val resourceKey = mediaResourceKey(postUrl, mediaUrl, mediaType, fileSuffix)
        readableDatabase.rawQuery(
            "SELECT 1 FROM downloads WHERE resource_key = ? OR media_url = ? LIMIT 1",
            arrayOf(resourceKey, mediaUrl),
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    /**
     * 在文件已经写入相册后新增或刷新历史行。
     *
     * `CONFLICT_REPLACE` 同时受旧 `media_url` 约束和新 `resource_key` 索引保护，所以用户
     * 确认重复下载只会刷新记录，不会让同一资源的历史计数无限增长。
     */
    suspend fun recordDownload(
        postUrl: String,
        postTitle: String,
        mediaUrl: String,
        mediaType: String,
        fileName: String,
        fileSuffix: String,
        previewUrl: String?,
        previewPath: String?,
    ) = withContext(Dispatchers.IO) {
        // media_url 是去重键。同一个资源再次下载时更新记录时间，
        // 相册里的旧文件不处理，由用户自己决定是否保留。
        val values = ContentValues().apply {
            put("post_url", postUrl)
            put("post_title", postTitle)
            put("media_url", mediaUrl)
            put("media_type", mediaType)
            put("file_name", fileName)
            put("downloaded_at", System.currentTimeMillis())
            put("preview_url", previewUrl.orEmpty())
            put("preview_path", previewPath.orEmpty())
            put("resource_key", mediaResourceKey(postUrl, mediaUrl, mediaType, fileSuffix))
        }
        writableDatabase.insertWithOnConflict(
            "downloads",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    /**
     * 把媒体级行聚合成历史页的帖子级卡片，并选取每帖最早可用的预览元数据。
     * 子查询过滤空字符串，以兼容旧版本把缺失值写成空文本的记录。
     */
    suspend fun listPosts(): List<DownloadHistoryPost> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            """
            SELECT
                post_url,
                post_title,
                COUNT(*) AS item_count,
                MAX(downloaded_at) AS last_downloaded_at,
                COALESCE(
                    (
                        SELECT d2.preview_url
                        FROM downloads d2
                        WHERE d2.post_url = downloads.post_url
                            AND d2.preview_url IS NOT NULL
                            AND d2.preview_url != ''
                        ORDER BY d2.downloaded_at ASC
                        LIMIT 1
                    ),
                    ''
                ) AS preview_url,
                COALESCE(
                    (
                        SELECT d3.preview_path
                        FROM downloads d3
                        WHERE d3.post_url = downloads.post_url
                            AND d3.preview_path IS NOT NULL
                            AND d3.preview_path != ''
                        ORDER BY d3.downloaded_at ASC
                        LIMIT 1
                    ),
                    ''
                ) AS preview_path,
                COALESCE(
                    (
                        SELECT d4.file_name
                        FROM downloads d4
                        WHERE d4.post_url = downloads.post_url
                            AND d4.file_name IS NOT NULL
                            AND d4.file_name != ''
                        ORDER BY d4.downloaded_at ASC
                        LIMIT 1
                    ),
                    ''
                ) AS preview_file_name,
                COALESCE(
                    (
                        SELECT d5.media_type
                        FROM downloads d5
                        WHERE d5.post_url = downloads.post_url
                            AND d5.media_type IS NOT NULL
                            AND d5.media_type != ''
                        ORDER BY d5.downloaded_at ASC
                        LIMIT 1
                    ),
                    ''
                ) AS preview_media_type
            FROM downloads
            GROUP BY post_url
            ORDER BY last_downloaded_at DESC
            """.trimIndent(),
            emptyArray<String>(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DownloadHistoryPost(
                            postUrl = cursor.getString(0),
                            title = cursor.getString(1),
                            itemCount = cursor.getInt(2),
                            lastDownloadedAt = cursor.getLong(3),
                            previewUrl = cursor.getString(4).ifBlank { null },
                            previewPath = cursor.getString(5).ifBlank { null },
                            previewFileName = cursor.getString(6).ifBlank { null },
                            previewMediaType = cursor.getString(7).ifBlank { null },
                        ),
                    )
                }
            }
        }
    }

    /** 旧记录补建缩略图后，把同一帖所有媒体行指向同一个本地预览文件。 */
    suspend fun updatePreviewPath(postUrl: String, previewPath: String) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("preview_path", previewPath)
        }
        writableDatabase.update(
            "downloads",
            values,
            "post_url = ?",
            arrayOf(postUrl),
        )
        Unit
    }

    /** 删除指定帖子的全部历史行，但不删除系统相册中的媒体文件。 */
    suspend fun deletePost(postUrl: String) = withContext(Dispatchers.IO) {
        // 这里删除的是 App 自己的下载历史，不会触碰相册里的图片或视频文件。
        // 一个帖子可能有多张图或多个视频，所以按 post_url 删除该帖的全部历史项。
        writableDatabase.delete(
            "downloads",
            "post_url = ?",
            arrayOf(postUrl),
        )
        Unit
    }

    /**
     * 为 v1-v3 历史逐行计算稳定键。
     *
     * 先把游标内容读入内存、关闭游标后再更新同一张表，避免边遍历边修改导致 CursorWindow
     * 行为依赖 SQLite 实现细节。历史规模很小，这种方式更安全也更易审计。
     */
    private fun backfillResourceKeys(db: SQLiteDatabase) {
        val migratedKeys = mutableListOf<Pair<Long, String>>()
        db.query(
            "downloads",
            arrayOf("id", "post_url", "media_url", "media_type", "file_name"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val resourceKey = legacyMediaResourceKey(
                    postUrl = cursor.getString(1),
                    mediaUrl = cursor.getString(2),
                    mediaType = cursor.getString(3),
                    fileName = cursor.getString(4),
                )
                migratedKeys += id to resourceKey
            }
        }
        migratedKeys.forEach { (id, resourceKey) ->
            val values = ContentValues().apply { put("resource_key", resourceKey) }
            db.update("downloads", values, "id = ?", arrayOf(id.toString()))
        }
    }
}
