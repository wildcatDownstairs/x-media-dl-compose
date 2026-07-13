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
import net.xmediadl.app.data.HistoryPreviewStore
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
    private val previewStore = HistoryPreviewStore(appContext)
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
        val currentInput = uiState.value.input
        val clipboardUrl = readClipboardXUrl(appContext)

        // 用户经常是“复制链接 -> 回到 App -> 直接点 Download”。
        // 这里在输入框为空时自动回退到剪贴板，省掉必须再点一次 Paste 的步骤。
        when {
            currentInput.isNotBlank() -> resolve(currentInput)
            clipboardUrl != null -> resolve(clipboardUrl)
            else -> resolve(currentInput)
        }
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

    fun deleteHistoryPost(postUrl: String) {
        val previewPath = uiState.value.history.firstOrNull { it.postUrl == postUrl }?.previewPath
        scope.launch {
            historyStore.deletePost(postUrl)
            previewStore.deletePreview(previewPath)
            refreshHistory()
        }
    }

    fun requestDownload(item: MediaItem) {
        val post = uiState.value.resolved ?: return
        scope.launch {
            if (
                historyStore.hasMedia(
                    postUrl = post.postUrl,
                    mediaUrl = item.url,
                    mediaType = item.type.name,
                    fileSuffix = item.fileSuffix,
                )
            ) {
                _uiState.update {
                    it.copy(
                        pendingDownload = PendingDownload(
                            item = item,
                            postUrl = post.postUrl,
                            postTitle = post.title.ifBlank { "Untitled post" },
                            previewUrl = post.firstPreviewUrl(),
                            previewPath = null,
                        ),
                    )
                }
            } else {
                performDownload(
                    item = item,
                    postUrl = post.postUrl,
                    postTitle = post.title.ifBlank { "Untitled post" },
                    previewUrl = post.firstPreviewUrl(),
                )
            }
        }
    }

    fun confirmDuplicateDownload() {
        val pending = uiState.value.pendingDownload ?: return
        _uiState.update { it.copy(pendingDownload = null) }
        performDownload(
            item = pending.item,
            postUrl = pending.postUrl,
            postTitle = pending.postTitle,
            previewUrl = pending.previewUrl,
            previewPath = pending.previewPath,
        )
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
            val history = historyStore.listPosts()
            _uiState.update { it.copy(history = history) }

            // 兼容旧数据：以前只有 preview_url，没有落本地文件。
            // 现在除了远端 preview_url，也会尝试从相册里已下载的本地媒体文件反查预览。
            // 这样用户过去已经下载过的历史项，不需要重新下载也能把缩略图补回来。
            val repairedHistory = history.map { item ->
                if (item.previewPath != null) {
                    item
                } else {
                    val localPreviewPath = previewStore.ensurePreview(
                        postUrl = item.postUrl,
                        previewUrl = item.previewUrl,
                        previewFileName = item.previewFileName,
                        previewMediaType = item.previewMediaType,
                    )
                    if (localPreviewPath != null) {
                        historyStore.updatePreviewPath(item.postUrl, localPreviewPath)
                        item.copy(previewPath = localPreviewPath)
                    } else {
                        item
                    }
                }
            }

            if (repairedHistory != history) {
                _uiState.update { it.copy(history = repairedHistory) }
            }
        }
    }

    private fun performDownload(
        item: MediaItem,
        postUrl: String,
        postTitle: String,
        previewUrl: String?,
        previewPath: String? = null,
    ) {
        val pendingName = buildFileName(postTitle, item.quality, item.fileExtension(), item.fileSuffix)
        _uiState.update { it.copy(notice = "开始保存到相册：$pendingName") }

        scope.launch {
            runCatching { downloader.download(item, postTitle) }
                .onSuccess { savedName ->
                    // 每次确认下载时顺手缓存一张本地预览图。
                    // 这样历史列表重进、冷启动、弱网时都不再依赖远端封面链接是否还能访问。
                    val cachedPreviewPath = previewPath ?: previewStore.ensurePreview(postUrl, previewUrl)
                    historyStore.recordDownload(
                        postUrl = postUrl,
                        postTitle = postTitle.ifBlank { "Untitled post" },
                        mediaUrl = item.url,
                        mediaType = item.type.name,
                        fileName = savedName,
                        fileSuffix = item.fileSuffix,
                        previewUrl = previewUrl,
                        previewPath = cachedPreviewPath,
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
    return mediaEntries.firstNotNullOfOrNull { entry ->
        when (entry) {
            is PhotoEntry -> entry.photo.url
            is VideoEntry -> entry.cover?.url
        }
    }
        ?: thumbnailUrl
}
