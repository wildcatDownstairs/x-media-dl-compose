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

/** 单 Activity 内的三个互斥页面；导航由 [AppUiState.screen] 驱动。 */
enum class AppScreen {
    Home,
    Result,
    History,
}

/**
 * 界面所需状态的完整快照。
 *
 * [resolved] 与 [history] 分别服务结果页和历史页；[notice] 是自动消失的轻提示；
 * [pendingDownload] 非空时根节点显示重复下载确认框。把这些值放在同一个 StateFlow 中，
 * 可保证 Compose 每次重组看到内部一致的状态组合。
 */
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

/**
 * 连接 UI、解析器、相册下载器和本地历史库的页面级状态持有者。
 *
 * 所有公开方法都是用户事件入口；耗时工作统一启动在 [scope] 中，结果再以原子 copy/update
 * 写回 [uiState]。数据层对象只在这里持有，Composable 不直接访问数据库或下载器。
 */
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
        // 从系统分享/深链接启动时跳过首页输入步骤，直接解析传入帖子。
        if (!initialUrl.isNullOrBlank()) {
            resolve(initialUrl)
        }
    }

    /** 同步输入框，并清除上一次格式错误，避免用户修改后仍看到过期提示。 */
    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value, error = null) }
    }

    /** 粘贴任意文本；实际 URL 提取和格式校验由 [resolve] 统一处理。 */
    fun pasteFromClipboard() {
        val pasted = readClipboardText(appContext)
        if (pasted.isBlank()) {
            _uiState.update { it.copy(error = "剪贴板里没有可用文本。") }
        } else {
            resolve(pasted)
        }
    }

    /** 点击 DOWNLOAD 时优先用输入框；输入为空则尝试当前剪贴板中的 X 链接。 */
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

    /**
     * 规范化并解析帖子。
     *
     * 发起请求前先清空旧结果，避免新旧帖内容同时出现；异步结果只修改 loading/resolved/
     * error，不替换用户已经规范化后的输入值。
     */
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

    /** 立即切换到历史页，再异步读取数据库，页面可先展示稳定的框架。 */
    fun openHistory() {
        _uiState.update { it.copy(screen = AppScreen.History, error = null, loading = false) }
        refreshHistory()
    }

    /** 删除帖子级历史及其私有缩略图，不删除系统相册中的原始媒体。 */
    fun deleteHistoryPost(postUrl: String) {
        val previewPath = uiState.value.history.firstOrNull { it.postUrl == postUrl }?.previewPath
        scope.launch {
            historyStore.deletePost(postUrl)
            previewStore.deletePreview(previewPath)
            refreshHistory()
        }
    }

    /**
     * 用户点击媒体按钮后的判重入口。
     *
     * 必须先查持久数据库，不能依赖进程内集合；命中后只设置 [PendingDownload] 让 UI 弹窗，
     * 未命中才直接进入下载。
     */
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

    /** 用户确认重复下载：先关闭弹窗，再复用同一下载流程刷新历史记录。 */
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

    /** 取消重复下载时仅清空暂存上下文，不修改相册或历史。 */
    fun dismissDuplicateDialog() {
        _uiState.update { it.copy(pendingDownload = null) }
    }

    /** 结果页继续下载：剪贴板出现新帖子就原地解析，否则回首页。 */
    fun handleDownloadMore() {
        val clipboardUrl = readClipboardXUrl(appContext)
        val currentUrl = uiState.value.input
        if (clipboardUrl != null && clipboardUrl != currentUrl) {
            resolve(clipboardUrl)
        } else {
            returnHome()
        }
    }

    /** 单层状态导航的返回行为：非首页页面统一回首页。 */
    fun handleBack() {
        if (uiState.value.screen == AppScreen.Home) {
            return
        }
        returnHome()
    }

    /** 根 Composable 的定时器调用，用于消费一次性顶部提示。 */
    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    /** 清空帖子相关临时状态，但保留已加载历史，下一次打开可先显示旧快照。 */
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

    /**
     * 刷新帖子聚合历史，并为旧记录惰性修复本地缩略图。
     *
     * 第一阶段先发布数据库结果，第二阶段再逐项做可能较慢的相册/网络预览修复；因此即使
     * 某张封面失败，文字历史仍能及时显示。
     */
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

    /**
     * 执行“写相册 → 缓存预览 → 写历史”的有序事务流程。
     *
     * 只有相册保存成功后才记录数据库，保证“已下载”始终代表真实文件曾成功写入。
     */
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

    /** Activity 真正结束时取消未完成任务并关闭 SQLite 连接。 */
    override fun onCleared() {
        scope.cancel()
        historyStore.close()
        super.onCleared()
    }

    /** 向无参 ViewModelProvider 注入 Application Context 与可选初始分享链接。 */
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
