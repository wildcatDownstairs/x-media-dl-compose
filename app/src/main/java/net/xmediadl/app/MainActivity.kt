package net.xmediadl.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import net.xmediadl.app.ui.XMediaDownloaderApp
import net.xmediadl.app.utils.extractUrlFromIntent
import net.xmediadl.app.viewmodel.XMediaViewModel

/**
 * Android 进程进入 App 后的唯一 Activity。
 *
 * 这里刻意只做三件事：解析系统 Intent、创建具有 Activity 生命周期的 ViewModel、挂载
 * Compose 根节点。网络解析、下载和数据库访问都留在 ViewModel/数据层，避免 Activity 因
 * 旋转或系统重建而把正在执行的业务任务一起销毁。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Activity 只负责 Android 入口，不直接处理下载、解析或数据库。
        // 这样做的好处是：Activity 被系统重建时，页面状态仍然由 ViewModel 托管，
        // UI、业务逻辑、数据存储也不会全部混在一个文件里。
        val sharedUrl = extractUrlFromIntent(intent)
        val factory = XMediaViewModel.Factory(applicationContext, sharedUrl)
        val viewModel = ViewModelProvider(this, factory)[XMediaViewModel::class.java]

        setContent {
            XMediaDownloaderApp(viewModel = viewModel)
        }
    }
}
