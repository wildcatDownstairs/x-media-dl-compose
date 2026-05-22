package net.xmediadl.app

import android.content.ContentValues
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // App 既可以从桌面图标打开，也可以从系统分享菜单 / 浏览器链接打开。
        // 如果是后两种入口，Intent 里可能已经带着 X/Twitter 链接，
        // 这里先取出来，再交给 Compose 页面作为初始输入。
        val sharedUrl = extractUrlFromIntent(intent)
        setContent {
            XMediaDownloaderApp(initialUrl = sharedUrl)
        }
    }
}

// 一个真正可以下载的媒体项。SaveTwitter 的返回结果里可能同时有视频和图片，
// 这里统一抽象成 MediaItem，UI 层只需要关心 label 和 url。
private data class MediaItem(
    val label: String,
    val url: String,
    val type: MediaType,
    val quality: Int = 0,
    val fileSuffix: String = "",
)

private sealed interface MediaEntry

private data class VideoEntry(
    val video: MediaItem,
    val cover: MediaItem?,
) : MediaEntry

private data class PhotoEntry(
    val photo: MediaItem,
) : MediaEntry

private enum class MediaType {
    Video,
    Photo,
}

private fun MediaItem.fileExtension(): String = when (type) {
    MediaType.Video -> "mp4"
    MediaType.Photo -> "jpg"
}

private fun MediaItem.mimeType(): String = when (type) {
    MediaType.Video -> "video/mp4"
    MediaType.Photo -> "image/jpeg"
}

private fun MediaItem.galleryDirectory(): String = when (type) {
    MediaType.Video -> Environment.DIRECTORY_MOVIES
    MediaType.Photo -> Environment.DIRECTORY_PICTURES
}

// 解析完成后的整条帖子数据。它不是接口原样返回的 JSON，
// 而是把 SaveTwitter 返回的 HTML 清洗后，整理成 Compose UI 好展示的结构。
private data class ResolvedPost(
    val title: String,
    val duration: String,
    val thumbnailUrl: String?,
    val mediaEntries: List<MediaEntry>,
    val elapsedMs: Long,
)

@Composable
private fun XMediaDownloaderApp(initialUrl: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 这些 state 是页面的单一数据来源：
    // input 控制输入框，isResult 控制一级 / 二级页面切换，
    // loading/error/resolved 分别表示解析中的三种展示状态。
    var input by remember { mutableStateOf(initialUrl.orEmpty()) }
    var isResult by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var resolved by remember { mutableStateOf<ResolvedPost?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // “Download more videos” 按钮和 Android 返回手势都应该回到同一个首页状态。
    // 把清理逻辑抽成函数，可以避免两个入口以后改出不一致的行为。
    fun returnHome() {
        input = ""
        error = null
        resolved = null
        loading = false
        isResult = false
    }

    // 用户点击 DOWNLOAD、点击粘贴后自动解析、或者从系统分享进入 App，
    // 最终都会走到这个函数。这里负责做 URL 清洗、基础校验和启动网络请求。
    fun resolve(url: String) {
        val cleanUrl = normalizeSharedText(url)
        input = cleanUrl
        if (!looksLikeXUrl(cleanUrl)) {
            error = "请粘贴 x.com 或 twitter.com 的公开帖子分享链接。"
            return
        }

        isResult = true
        loading = true
        error = null
        resolved = null

        // 网络请求不能跑在主线程，否则 Android 会卡 UI 甚至抛异常。
        // rememberCoroutineScope() 提供了一个和当前 Compose 生命周期绑定的协程作用域。
        scope.launch {
            runCatching { resolveViaSaveTwitter(cleanUrl) }
                .onSuccess { resolved = it }
                .onFailure { error = it.message ?: "解析失败。" }
            loading = false
        }
    }

    // initialUrl 只在“从分享 / 浏览器打开 App”时有值。
    // LaunchedEffect 会在这个值进入 Compose 后自动执行一次解析。
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            resolve(initialUrl)
        }
    }

    // 结果页是同一个 Activity 内部的二级状态，不是 Android Navigation 的新页面。
    // 所以要手动拦截系统返回手势，让它先回首页，而不是直接退出 App。
    BackHandler(enabled = isResult) {
        returnHome()
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_600)
            notice = null
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

                    if (isResult) {
                        ResultScreen(
                            loading = loading,
                            error = error,
                            resolved = resolved,
                            onDownload = { item ->
                                val pendingName = buildFileName(
                                    resolved?.title.orEmpty(),
                                    item.quality,
                                    item.fileExtension(),
                                    item.fileSuffix,
                                )
                                notice = "开始保存到相册：$pendingName"
                                scope.launch {
                                    runCatching { context.downloadMedia(item, resolved?.title.orEmpty()) }
                                        .onSuccess { savedName ->
                                            notice = "已保存到相册：$savedName"
                                        }
                                        .onFailure { throwable ->
                                            notice = "下载失败：${throwable.message ?: "未知错误"}"
                                        }
                                }
                            },
                            onMore = ::returnHome,
                        )
                    } else {
                        HomeScreen(
                            input = input,
                            error = error,
                            onInputChange = {
                                input = it
                                error = null
                            },
                            onPaste = {
                                scope.launch {
                                    // Android 的剪贴板可能为空，也可能不是纯文本。
                                    // 这里只接受 text/plain，再把内容交给 resolve() 做 URL 提取和校验。
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val pasted = clipboard.primaryClip
                                        ?.takeIf { it.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) }
                                        ?.getItemAt(0)
                                        ?.coerceToText(context)
                                        ?.toString()
                                        .orEmpty()
                                    if (pasted.isBlank()) {
                                        error = "剪贴板里没有可用文本。"
                                    } else {
                                        resolve(pasted)
                                    }
                                }
                            },
                            onResolve = { resolve(input) },
                        )
                    }

                    Spacer(Modifier.weight(1f))
                    Footer()
                }

                notice?.let {
                    TopNotice(
                        message = it,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar() {
    // 顶部栏按 HTML 原型拆成左侧品牌和右侧 badge。
    // 这里的 mark 用 X↓，App 桌面图标则使用用户给的 Film SVG 风格。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "X↓",
                    color = AppColors.AccentText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                )
            }
            Text(
                "MEDIA DL",
                color = AppColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 1.sp,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("Personal · Ad-free", color = Color(0xFFCFCFCF), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TopNotice(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.BorderActive, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            message,
            color = AppColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeScreen(
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onResolve: () -> Unit,
) {
    Column {
        // 一级页面的布局基本按 index.html 的移动端视觉顺序实现：
        // eyebrow -> 大标题 -> 描述 -> 输入卡片 -> 错误提示。
        Text("// X Media Downloader", color = AppColors.Accent, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            "Download\nwithout the noise.",
            color = AppColors.TextPrimary,
            fontSize = 32.sp,
            lineHeight = 35.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Paste any X / Twitter post URL to grab videos and images directly — no ads, no redirects.",
            color = AppColors.TextSecondary,
            fontSize = 15.sp,
            lineHeight = 24.sp,
        )
        Spacer(Modifier.height(40.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Border, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("// Post URL", color = AppColors.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF050505))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = onInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        cursorBrush = SolidColor(AppColors.Accent),
                        textStyle = TextStyle(
                            color = AppColors.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onResolve() }),
                    )
                    if (input.isBlank()) {
                        Text("https://x.com/...", color = AppColors.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
                Button(
                    onClick = onPaste,
                    modifier = Modifier.size(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF050505), contentColor = AppColors.TextSecondary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    ClipboardIcon(
                        modifier = Modifier.size(18.dp),
                        color = AppColors.TextSecondary,
                    )
                }
            }
            Button(
                onClick = onResolve,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.AccentText),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("DOWNLOAD", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }

        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = AppColors.Danger, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ClipboardIcon(modifier: Modifier = Modifier, color: Color) {
    // HTML 原型里粘贴按钮使用的是一个 24x24 的 SVG clipboard。
    // Compose 这里用 Canvas 按比例画同样的结构，这样不需要额外引入图标库。
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx())
        val radius = androidx.compose.ui.geometry.CornerRadius(1.6.dp.toPx(), 1.6.dp.toPx())
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.17f, size.height * 0.17f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.66f, size.height * 0.75f),
            cornerRadius = radius,
            style = stroke,
        )
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.33f, size.height * 0.04f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.34f, size.height * 0.22f),
            cornerRadius = radius,
            style = stroke,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.33f, size.height * 0.48f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.67f, size.height * 0.48f),
            strokeWidth = 1.8.dp.toPx(),
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.33f, size.height * 0.65f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.67f, size.height * 0.65f),
            strokeWidth = 1.8.dp.toPx(),
        )
    }
}

@Composable
private fun ResultScreen(
    loading: Boolean,
    error: String?,
    resolved: ResolvedPost?,
    onDownload: (MediaItem) -> Unit,
    onMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 二级页有三种内容状态：loading、error、resolved。
        // 顶部标题和底部返回按钮始终存在，方便单手操作。
        Column {
            Text("// 解析结果", color = AppColors.Accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
            Text("选择要下载的媒体", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        val meta = resolved?.let {
            val count = it.mediaEntries.size
            "视频 / 图片 · $count 个媒体资源 · ${it.elapsedMs} ms"
        }
        if (meta != null) {
            Text(meta, color = Color(0xFFB7B7B7), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        when {
            loading -> LoadingPanel()
            error != null -> ErrorPanel(error)
            resolved != null -> ResultCard(resolved, onDownload)
        }

        Button(
            onClick = onMore,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(10.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f), contentColor = Color(0xFFD6D6D6)),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("↩ Download more videos", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LoadingPanel() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp))
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = AppColors.Accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        Column {
            Text("正在解析…", fontWeight = FontWeight.Bold)
            Text("请求 SaveTwitter → 读取下载项", color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ErrorPanel(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp))
            .padding(18.dp),
    ) {
        Text(message, color = AppColors.Danger, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun ResultCard(resolved: ResolvedPost, onDownload: (MediaItem) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemotePreview(resolved.thumbnailUrl)
        resolved.mediaEntries.forEach { entry ->
            when (entry) {
                is VideoEntry -> VideoDownloadRow(entry, onDownload)
                is PhotoEntry -> DownloadButton(entry.photo, onDownload)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            resolved.title.ifBlank { "Untitled post" },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        if (resolved.duration.isNotBlank()) {
            Text(resolved.duration, color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun VideoDownloadRow(entry: VideoEntry, onDownload: (MediaItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (entry.cover == null) {
            DownloadButton(entry.video, onDownload)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadButton(entry.video, onDownload, modifier = Modifier.weight(1f))
                DownloadButton(entry.cover, onDownload, modifier = Modifier.weight(1f))
            }
        }
        val qualityText = if (entry.video.quality > 0) "MP4 · ${entry.video.quality}p" else "MP4"
        Text(
            qualityText,
            color = AppColors.TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun DownloadButton(
    item: MediaItem,
    onDownload: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { onDownload(item) },
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.AccentText),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Text(
            item.label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RemotePreview(url: String?) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    // 缩略图 URL 改变时重新加载图片。失败时不让页面报错，
    // 直接显示一个 X 占位，下载按钮仍然可用。
    LaunchedEffect(url) {
        bitmap = if (url.isNullOrBlank()) null else loadBitmap(url)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF050505)),
        contentAlignment = Alignment.Center,
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("X", color = AppColors.Accent, fontSize = 42.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Footer() {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
        Text("Personal use only · powered by savetwitter api", color = AppColors.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

private suspend fun resolveViaSaveTwitter(postUrl: String): ResolvedPost = withContext(Dispatchers.IO) {
    val startedAt = System.currentTimeMillis()

    // SaveTwitter 首页本质上也是把用户输入的帖子 URL POST 到这个 ajaxSearch 接口。
    // cftoken 目前可以为空；请求头模拟浏览器 Ajax 请求，降低被服务端拒绝的概率。
    val body = "q=${URLEncoder.encode(postUrl, StandardCharsets.UTF_8.name())}&lang=en&cftoken="
    val connection = (URL("https://savetwitter.net/api/ajaxSearch").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 20_000
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        setRequestProperty("Origin", "https://savetwitter.net")
        setRequestProperty("Referer", "https://savetwitter.net/en4")
        setRequestProperty("X-Requested-With", "XMLHttpRequest")
        setRequestProperty("User-Agent", desktopUserAgent)
    }
    connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

    // 接口返回的是 JSON，但真正的下载按钮藏在 data 字段的 HTML 片段里。
    // 所以下一步不是按 JSON 字段取视频链接，而是解析这段 HTML。
    val response = connection.readText()
    val json = JSONObject(response)
    val html = json.optString("data")
    if (json.optString("status") != "ok" || html.isBlank()) {
        throw IllegalStateException(json.optString("msg", "没有解析到可下载媒体。"))
    }

    parseResolvedPost(html, System.currentTimeMillis() - startedAt)
}

private fun parseResolvedPost(html: String, elapsedMs: Long): ResolvedPost {
    // SaveTwitter 返回的 data 是服务器渲染好的 HTML。
    // 这里用正则只抓当前页面需要的几个稳定信息：
    // 缩略图、标题、时长、下载按钮链接。
    val thumbnail = Regex("""<img\s+src="([^"]+)""", RegexOption.IGNORE_CASE)
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.htmlUnescape()

    val title = Regex("""<h3[^>]*>(.*?)</h3>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.stripTags()
        .orEmpty()

    val duration = Regex("""<p>\s*([0-9:]+)\s*</p>""", RegexOption.IGNORE_CASE)
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()

    val mediaEntries = mutableListOf<MediaEntry>()
    val videoRun = mutableListOf<MediaItem>()
    var pendingVideo: MediaItem? = null

    fun flushBestVideoToPending() {
        // 一个视频通常会连续出现 1080p/720p/360p 这样的多个按钮。
        // UI 只显示该视频的最高质量，因此每组 MP4 链接先结算成一个 pending video。
        videoRun.maxByOrNull { it.quality }?.let { pendingVideo = it }
        videoRun.clear()
    }

    fun commitPendingVideoWithoutCover() {
        pendingVideo?.let {
            mediaEntries += VideoEntry(video = it, cover = null)
            pendingVideo = null
        }
    }

    Regex("""<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .findAll(html)
        .forEach { match ->
            val url = match.groupValues[1].htmlUnescape()
            val label = match.groupValues[2].stripTags()
            when {
                label.contains("Download MP4", ignoreCase = true) -> {
                    // 用户偏好直接下载最高清版本，所以 UI 不展示 720p/360p/270p 等低清选项。
                    // 当质量序列从低质量又跳回高质量时，基本可以判断进入了下一个视频。
                    val quality = Regex("""\((\d+)p\)""").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val previousQuality = videoRun.lastOrNull()?.quality ?: Int.MAX_VALUE
                    if (videoRun.isNotEmpty() && quality >= previousQuality) {
                        flushBestVideoToPending()
                        commitPendingVideoWithoutCover()
                    }
                    commitPendingVideoWithoutCover()
                    videoRun += MediaItem(label = "Download MP4 (${max(quality, 0)}p)", url = url, type = MediaType.Video, quality = quality)
                }
                label.contains("Download Photo", ignoreCase = true) -> {
                    flushBestVideoToPending()
                    val photo = MediaItem(label = "Download Photo", url = url, type = MediaType.Photo)
                    val video = pendingVideo
                    if (video != null) {
                        // SaveTwitter 的视频卡片通常会在 MP4 按钮后面紧跟一个 Download Photo。
                        // 这个 Photo 是视频封面，所以和视频按钮并排展示，而不是当成独立图片。
                        mediaEntries += VideoEntry(video = video, cover = photo)
                        pendingVideo = null
                    } else {
                        mediaEntries += PhotoEntry(photo)
                    }
                }
                else -> flushBestVideoToPending()
            }
        }
    flushBestVideoToPending()
    commitPendingVideoWithoutCover()

    val dedupedMediaEntries = mediaEntries.distinctBy {
        when (it) {
            is VideoEntry -> "video:${it.video.url}:${it.cover?.url.orEmpty()}"
            is PhotoEntry -> "photo:${it.photo.url}"
        }
    }
    if (dedupedMediaEntries.isEmpty()) {
        throw IllegalStateException("没有找到视频或图片下载项。")
    }
    val videoCount = dedupedMediaEntries.count { it is VideoEntry }
    val photoCount = dedupedMediaEntries.count { it is PhotoEntry }
    var videoIndex = 1
    var photoIndex = 1
    val labeledMediaEntries = dedupedMediaEntries.map { entry ->
        when (entry) {
            is VideoEntry -> {
                val index = videoIndex++
                val video = entry.video.copy(
                    label = if (videoCount == 1) "Download MP4" else "Download MP4 $index",
                    fileSuffix = if (videoCount == 1) "" else "-video-$index",
                )
                val cover = entry.cover?.copy(
                    label = if (videoCount == 1) "Download Cover" else "Cover $index",
                    fileSuffix = if (videoCount == 1) "-cover" else "-video-$index-cover",
                )
                VideoEntry(video = video, cover = cover)
            }
            is PhotoEntry -> {
                val index = photoIndex++
                PhotoEntry(entry.photo.copy(
                    label = if (photoCount == 1) "Download Photo" else "Download Photo $index",
                    fileSuffix = if (photoCount == 1) "" else "-photo-$index",
                ))
            }
        }
    }

    return ResolvedPost(
        title = title,
        duration = duration,
        thumbnailUrl = thumbnail,
        mediaEntries = labeledMediaEntries,
        elapsedMs = elapsedMs,
    )
}

private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    // 缩略图只是辅助展示，失败不应该影响解析和下载。
    // 因此这里用 runCatching 吃掉异常，返回 null 让 UI 显示占位。
    runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", desktopUserAgent)
        }
        connection.inputStream.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}

private suspend fun Context.downloadMedia(item: MediaItem, title: String): String {
    val fileName = buildFileName(title, item.quality, item.fileExtension(), item.fileSuffix)
    return saveToGallery(item.url, fileName, item.mimeType(), item.galleryDirectory())
}

private suspend fun Context.saveToGallery(
    url: String,
    fileName: String,
    mimeType: String,
    directory: String,
): String =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            this@saveToGallery.saveToGalleryWithMediaStore(url, fileName, mimeType, directory)
        } else {
            this@saveToGallery.saveToGalleryWithPublicFile(url, fileName, directory)
        }
    }

private fun Context.saveToGalleryWithMediaStore(
    url: String,
    fileName: String,
    mimeType: String,
    directory: String,
): String {
    // Android 10 以后推荐通过 MediaStore 写入系统媒体库。
    // 这样不需要传统的存储权限，也不会触发部分系统对 DownloadManager 公共路径的限制。
    // 图片写入 Pictures，视频写入 Movies，系统相册会把它们当作普通媒体扫描出来。
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/XMediaDL")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = contentResolver
    val collectionUri = when {
        mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }
    val uri = resolver.insert(collectionUri, values)
        ?: throw IllegalStateException("无法创建媒体文件。")

    try {
        resolver.openOutputStream(uri)?.use { output ->
            downloadUrlTo(url, output)
        } ?: throw IllegalStateException("无法写入媒体文件。")

        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return fileName
    } catch (throwable: Throwable) {
        resolver.delete(uri, null, null)
        throw throwable
    }
}

private fun Context.saveToGalleryWithPublicFile(url: String, fileName: String, directory: String): String {
    // Android 9 及以下没有 MediaStore.Downloads 的分区存储接口，
    // 退回到传统公共媒体目录。Manifest 里限制性声明了旧版写入权限。
    val mediaDir = File(Environment.getExternalStoragePublicDirectory(directory), "XMediaDL")
    if (!mediaDir.exists() && !mediaDir.mkdirs()) {
        throw IllegalStateException("无法创建相册目录。")
    }
    val outputFile = uniqueFile(mediaDir, fileName)
    outputFile.outputStream().use { output ->
        downloadUrlTo(url, output)
    }
    MediaScannerConnection.scanFile(
        this,
        arrayOf(outputFile.absolutePath),
        null,
        null,
    )
    return outputFile.name
}

private fun downloadUrlTo(url: String, output: OutputStream) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", desktopUserAgent)
        setRequestProperty("Referer", "https://savetwitter.net/")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code")
        }
        connection.inputStream.use { input ->
            input.copyTo(output)
        }
    } finally {
        connection.disconnect()
    }
}

private fun uniqueFile(directory: File, fileName: String): File {
    val dotIndex = fileName.lastIndexOf('.')
    val name = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
    val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""

    var candidate = File(directory, fileName)
    var index = 1
    while (candidate.exists()) {
        candidate = File(directory, "$name-$index$extension")
        index += 1
    }
    return candidate
}

private fun buildFileName(title: String, quality: Int, extension: String, fileSuffix: String = ""): String {
    // Windows/Android 文件名都不适合包含这些特殊字符。
    // 标题太长也会让下载列表很难读，所以限制到 48 个字符。
    val base = title
        .ifBlank { "x-media" }
        .replace(Regex("""[\\/:*?"<>|]"""), "")
        .replace(Regex("""\s+"""), "-")
        .take(48)
        .trim('-')
        .ifBlank { "x-media" }
    val suffix = if (quality > 0) "-${quality}p" else ""
    return "$base$fileSuffix$suffix.$extension"
}

private fun extractUrlFromIntent(intent: Intent?): String? {
    // 支持两类入口：
    // 1. ACTION_SEND：从 X App 或浏览器点系统分享，把文本分享给本 App。
    // 2. ACTION_VIEW：用户点开 x.com/twitter.com 链接时，由系统把链接交给本 App。
    if (intent == null) return null
    val raw = when (intent.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW -> intent.dataString
        else -> null
    }
    return raw?.let(::normalizeSharedText)?.takeIf(::looksLikeXUrl)
}

private fun normalizeSharedText(text: String): String {
    // 分享文本通常不止一个 URL，可能还带标题、换行或标点。
    // 这里优先提取第一段 http/https 链接，再去掉末尾常见中文/英文标点。
    val match = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE).find(text)
    return (match?.value ?: text.trim()).trimEnd(')', ',', '.', '，', '。')
}

private fun looksLikeXUrl(value: String): Boolean {
    // 这里只做轻量校验：确认 host 是 X/Twitter 相关域名。
    // 帖子是否公开、是否可解析，交给 SaveTwitter 接口返回结果决定。
    return runCatching {
        val host = Uri.parse(value).host.orEmpty().lowercase()
        host in setOf("x.com", "twitter.com", "www.x.com", "www.twitter.com", "mobile.twitter.com")
    }.getOrDefault(false)
}

private fun String.stripTags(): String {
    // 把 HTML 标签去掉后再做实体反转义，例如 &amp; -> &。
    return replace(Regex("""<[^>]+>"""), "")
        .htmlUnescape()
        .trim()
}

private fun String.htmlUnescape(): String {
    val decoded = replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
    return Html.fromHtml(decoded, Html.FROM_HTML_MODE_LEGACY).toString()
}

private fun HttpURLConnection.readText(): String {
    // 非 2xx 时 HttpURLConnection 的 inputStream 会抛异常，
    // 需要改读 errorStream，这样错误响应体也能展示成更有用的提示。
    val stream = if (responseCode in 200..299) inputStream else errorStream
    return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
}

private object AppColors {
    val Background = Color(0xFF0A0A0A)
    val Surface = Color(0xFF161616)
    val Border = Color.White.copy(alpha = 0.16f)
    val BorderActive = Color.White.copy(alpha = 0.36f)
    val TextPrimary = Color(0xFFF0F0F0)
    val TextSecondary = Color(0xFF999999)
    val TextMuted = Color(0xFF555555)
    val Accent = Color(0xFFE7FF52)
    val AccentText = Color(0xFF0A0A0A)
    val Danger = Color(0xFFFF4D4D)
}

private const val desktopUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36"
