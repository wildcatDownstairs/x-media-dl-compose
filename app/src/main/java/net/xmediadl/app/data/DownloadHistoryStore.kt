package net.xmediadl.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.xmediadl.app.model.DownloadHistoryPost

class DownloadHistoryStore(context: Context) :
    SQLiteOpenHelper(context, "download_history.db", null, 1) {

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
                downloaded_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_downloads_post_url ON downloads(post_url)")
        db.execSQL("CREATE INDEX idx_downloads_downloaded_at ON downloads(downloaded_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS downloads")
        onCreate(db)
    }

    suspend fun hasMedia(mediaUrl: String): Boolean = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT 1 FROM downloads WHERE media_url = ? LIMIT 1",
            arrayOf(mediaUrl),
        ).use { cursor ->
            cursor.moveToFirst()
        }
    }

    suspend fun recordDownload(
        postUrl: String,
        postTitle: String,
        mediaUrl: String,
        mediaType: String,
        fileName: String,
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
        }
        writableDatabase.insertWithOnConflict(
            "downloads",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    suspend fun listPosts(): List<DownloadHistoryPost> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            """
            SELECT post_url, post_title, COUNT(*) AS item_count, MAX(downloaded_at) AS last_downloaded_at
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
                        ),
                    )
                }
            }
        }
    }
}
