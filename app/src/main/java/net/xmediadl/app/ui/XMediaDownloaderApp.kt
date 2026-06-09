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

@Composable
fun XMediaDownloaderApp(viewModel: XMediaViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    BackHandler(enabled = state.screen != AppScreen.Home) {
        viewModel.handleBack()
    }

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
