package net.xmediadl.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.xmediadl.app.utils.openPostUrl
import net.xmediadl.app.viewmodel.AppScreen
import net.xmediadl.app.viewmodel.XMediaViewModel

/**
 * App 的 Compose 根节点，也是 ViewModel 状态到页面的唯一绑定点。
 *
 * 页面 Composable 保持无业务状态：它们只接收 [state][net.xmediadl.app.viewmodel.AppUiState]
 * 和回调。返回键、短时通知、重复下载确认等跨页面行为集中在这里，避免各页面各自维护
 * 一份可能不一致的导航或弹窗状态。
 */
@Composable
fun XMediaDownloaderApp(viewModel: XMediaViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 首页交还给系统默认返回行为；结果页和历史页则退回 App 首页。
    BackHandler(enabled = state.screen != AppScreen.Home) {
        viewModel.handleBack()
    }

    // notice 是一次性轻提示。以文本为 key 重启计时，新的下载结果不会被旧计时器清掉。
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(2_600)
            viewModel.clearNotice()
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AppColors.Background,
            contentColor = AppColors.TextPrimary,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(20.dp))
                    TopBar()
                    Spacer(Modifier.height(32.dp))

                    // App 只有一个 Activity，通过状态枚举切换页面，不引入额外导航栈。
                    when (state.screen) {
                        AppScreen.History -> HistoryScreen(
                            history = state.history,
                            onOpenPost = { postUrl -> context.openPostUrl(postUrl) },
                            onDeletePost = viewModel::deleteHistoryPost,
                        )
                        AppScreen.Result -> ResultScreen(
                            loading = state.loading,
                            error = state.error,
                            resolved = state.resolved,
                            onDownload = viewModel::requestDownload,
                            onMore = viewModel::handleDownloadMore,
                        )
                        AppScreen.Home -> HomeScreen(
                            input = state.input,
                            error = state.error,
                            onInputChange = viewModel::updateInput,
                            onPaste = viewModel::pasteFromClipboard,
                            onResolve = viewModel::resolveCurrentInput,
                            onHistory = viewModel::openHistory,
                        )
                    }

                    Spacer(Modifier.weight(1f))
                    Footer()
                }

                state.notice?.let {
                    TopNotice(
                        message = it,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                    )
                }

                state.pendingDownload?.let {
                    AlertDialog(
                        onDismissRequest = viewModel::dismissDuplicateDialog,
                        title = { Text("已经下载过") },
                        text = { Text("这个媒体资源已经记录在下载历史中。是否重新下载？不会删除相册里已有的文件。") },
                        confirmButton = {
                            TextButton(onClick = viewModel::confirmDuplicateDownload) {
                                Text("重新下载")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::dismissDuplicateDialog) {
                                Text("取消")
                            }
                        },
                    )
                }
            }
        }
    }
}
