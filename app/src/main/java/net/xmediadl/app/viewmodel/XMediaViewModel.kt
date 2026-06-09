package net.xmediadl.app.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.xmediadl.app.data.DownloadHistoryStore
import net.xmediadl.app.download.GalleryDownloader
import net.xmediadl.app.download.buildFileName
import net.xmediadl.app.model.MediaItem
import net.xmediadl.app.model.PendingDownload
import net.xmediadl.app.model.PhotoEntry
import net.xmediadl.app.model.ResolvedPost
import net.xmediadl.app.model.VideoEntry
import net.xmediadl.app.model.fileExtension
import net.xmediadl.app.network.SaveTwitterResolver
import net.xmediadl.app.utils.looksLikeXUrl
import net.xmediadl.app.utils.normalizeSharedText
import net.xmediadl.app.utils.readClipboardText
import net.xmediadl.app.utils.readClipboardXUrl

enum class AppScreen {
    Home,
    Result,
    History,
}

data class AppUiState(
    val input: String = "",
    val screen: AppScreen = AppScreen.Home,
    val loading: Boolean = false,
    val error: String? = null,
    val resolved: ResolvedPost? = null,
    val notice: String? = null,
    val history: List<net.xmediadl.app.model.DownloadHistoryPost> = emptyList(),
    val pendingDownload: PendingDownload? = null,
)

class XMediaViewModel(
    context: Context,
    initialUrl: String?,
) : ViewModel() {
    private val appContext = context.applicationContext
    private val historyStore = DownloadHistoryStore(appContext)
    private val resolver = SaveTwitterResolver()
    private val downloader = GalleryDownloader(appContext)

    // ViewModel 不应该依赖 Compose 的 rememberCoroutineScope。
    // 这里使用独立 scope，页面旋转或 Activity 重建时任务不会因为 Composable 重组而丢失。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow(AppUiState(input = initialUrl.orEmpty()))
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        if (!initialUrl.isNullOrBlank()) {
            resolve(initialUrl)
        }
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value, error = null) }
    }

    fun pasteFromClipboard() {
        val pasted = readClipboardText(appContext)
        if (pasted.isBlank()) {
            _uiState.update { it.copy(error = "剪贴板里没有可用文本。") }
        } else {
            resolve(pasted)
        }
    }

    fun resolveCurrentInput() {
        resolve(uiState.value.input)
    }

    fun resolve(rawUrl: String) {
        val cleanUrl = normalizeSharedText(rawUrl)
        if (!looksLikeXUrl(cleanUrl)) {
            _uiState.update {
                it.copy(
                    input = cleanUrl,
                    screen = AppScreen.Home,
                    error = "请粘贴 x.com 或 twitter.com 的公开帖子分享链接。",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                input = cleanUrl,
                screen = AppScreen.Result,
                loading = true,
                error = null,
                resolved = null,
                pendingDownload = null,
            )
        }

        scope.launch {
            runCatching { resolver.resolve(cleanUrl) }
                .onSuccess { post ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            resolved = post.copy(postUrl = cleanUrl),
                            error = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            error = throwable.message ?: "解析失败。",
                        )
                    }
                }
        }
    }

    fun openHistory() {
        _uiState.update { it.copy(screen = AppScreen.History, error = null, loading = false) }
        refreshHistory()
    }

    fun requestDownload(item: MediaItem) {
        val post = uiState.value.resolved ?: return
        scope.launch {
            if (historyStore.hasMedia(item.url)) {
                _uiState.update {
                    it.copy(
                        pendingDownload = PendingDownload(
                            item = item,
                            postUrl = post.postUrl,
                            postTitle = post.title.ifBlank { "Untitled post" },
                            previewUrl = post.firstPreviewUrl(),
                        ),
                    )
                }
            } else {
                performDownload(item, post.postUrl, post.title.ifBlank { "Untitled post" }, post.firstPreviewUrl())
            }
        }
    }

    fun confirmDuplicateDownload() {
        val pending = uiState.value.pendingDownload ?: return
        _uiState.update { it.copy(pendingDownload = null) }
        performDownload(pending.item, pending.postUrl, pending.postTitle, pending.previewUrl)
    }

    fun dismissDuplicateDialog() {
        _uiState.update { it.copy(pendingDownload = null) }
    }

    fun handleDownloadMore() {
        val clipboardUrl = readClipboardXUrl(appContext)
        val currentUrl = uiState.value.input
        if (clipboardUrl != null && clipboardUrl != currentUrl) {
            resolve(clipboardUrl)
        } else {
            returnHome()
        }
    }

    fun handleBack() {
        if (uiState.value.screen == AppScreen.Home) {
            return
        }
        returnHome()
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private fun returnHome() {
        _uiState.update {
            it.copy(
                input = "",
                screen = AppScreen.Home,
                loading = false,
                error = null,
                resolved = null,
                pendingDownload = null,
            )
        }
    }

    private fun refreshHistory() {
        scope.launch {
            _uiState.update { it.copy(history = historyStore.listPosts()) }
        }
    }

    private fun performDownload(item: MediaItem, postUrl: String, postTitle: String, previewUrl: String?) {
        val pendingName = buildFileName(postTitle, item.quality, item.fileExtension(), item.fileSuffix)
        _uiState.update { it.copy(notice = "开始保存到相册：$pendingName") }

        scope.launch {
            runCatching { downloader.download(item, postTitle) }
                .onSuccess { savedName ->
                    historyStore.recordDownload(
                        postUrl = postUrl,
                        postTitle = postTitle.ifBlank { "Untitled post" },
                        mediaUrl = item.url,
                        mediaType = item.type.name,
                        fileName = savedName,
                        previewUrl = previewUrl,
                    )
                    _uiState.update { it.copy(notice = "已保存到相册：$savedName") }
                    refreshHistory()
                }
                .onFailure { throwable ->
                    _uiState.update { it.copy(notice = "下载失败：${throwable.message ?: "未知错误"}") }
                }
        }
    }

    override fun onCleared() {
        scope.cancel()
        historyStore.close()
        super.onCleared()
    }

    class Factory(
        private val context: Context,
        private val initialUrl: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return XMediaViewModel(context, initialUrl) as T
        }
    }
}

private fun ResolvedPost.firstPreviewUrl(): String? {
    // 历史列表右侧需要一张稳定的预览图。
    // 优先用第一个媒体资源的图片：图片帖用图片本身，视频帖用封面；
    // 如果解析结果没有明确封面，再退回帖子缩略图。
    return mediaEntries.asSequence()
        .mapNotNull { entry ->
            when (entry) {
                is PhotoEntry -> entry.photo.url
                is VideoEntry -> entry.cover?.url
            }
        }
        .firstOrNull()
        ?: thumbnailUrl
}
