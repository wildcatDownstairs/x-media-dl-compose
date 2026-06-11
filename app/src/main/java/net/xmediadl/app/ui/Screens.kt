package net.xmediadl.app.ui

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.xmediadl.app.data.HistoryPreviewStore
import net.xmediadl.app.model.DownloadHistoryPost
import net.xmediadl.app.model.MediaItem
import net.xmediadl.app.model.PhotoEntry
import net.xmediadl.app.model.ResolvedPost
import net.xmediadl.app.model.VideoEntry
import net.xmediadl.app.network.RemoteImageLoader
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun TopBar() {
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
                    modifier = Modifier.offset(x = 1.dp),
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
fun TopNotice(message: String, modifier: Modifier = Modifier) {
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
fun HomeScreen(
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onResolve: () -> Unit,
    onHistory: () -> Unit,
) {
    Column {
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
            Button(
                onClick = onHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(10.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f), contentColor = Color(0xFFD6D6D6)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("DOWNLOAD HISTORY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = AppColors.Danger, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
fun ClipboardIcon(modifier: Modifier = Modifier, color: Color) {
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
fun ResultScreen(
    loading: Boolean,
    error: String?,
    resolved: ResolvedPost?,
    onDownload: (MediaItem) -> Unit,
    onMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
fun LoadingPanel() {
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
fun ErrorPanel(message: String) {
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
fun HistoryScreen(
    history: List<DownloadHistoryPost>,
    onOpenPost: (String) -> Unit,
    onDeletePost: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("// 下载历史", color = AppColors.Accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
            Text("已下载的帖子", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("点击条目会打开本机 X / Twitter 应用", color = Color(0xFFB7B7B7), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.Surface)
                    .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp))
                    .padding(18.dp),
            ) {
                Text("还没有下载记录。", color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            return
        }

        history.forEach { item ->
            HistoryItem(item = item, onOpenPost = onOpenPost, onDeletePost = onDeletePost)
        }
    }
}

@Composable
fun HistoryItem(
    item: DownloadHistoryPost,
    onOpenPost: (String) -> Unit,
    onDeletePost: (String) -> Unit,
    previewBitmapOverride: Bitmap? = null,
) {
    val context = LocalContext.current
    val previewStore = remember { HistoryPreviewStore(context.applicationContext) }
    val isPreviewMode = LocalInspectionMode.current
    var previewBitmap by remember(item.previewPath, item.previewUrl, previewBitmapOverride) {
        mutableStateOf(previewBitmapOverride)
    }
    var itemWidthPx by remember(item.postUrl) { mutableStateOf(0) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offsetX = remember(item.postUrl) { Animatable(0f) }
    val deleteThresholdPx = with(density) { 92.dp.toPx() }
    val maxDragPx = with(density) { 132.dp.toPx() }

    LaunchedEffect(item.previewPath, item.previewUrl, previewBitmapOverride, isPreviewMode) {
        if (previewBitmapOverride != null || isPreviewMode) {
            previewBitmap = previewBitmapOverride
            return@LaunchedEffect
        }

        // 历史页优先走本地缩略图。
        // 只有旧数据还没完成迁移时，才退回远端地址临时拉一次。
        previewBitmap = previewStore.loadBitmap(item.previewPath)
            ?: item.previewUrl?.let { RemoteImageLoader.loadBitmap(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF351212)),
    ) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(end = 22.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "DELETE",
                color = Color(0xFFFFA0A0),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .onSizeChanged { itemWidthPx = it.width }
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.value.roundToInt(), 0) }
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Surface)
                .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp))
                .pointerInput(item.postUrl) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val nextOffset = (offsetX.value + dragAmount).coerceIn(-maxDragPx, 0f)
                            scope.launch { offsetX.snapTo(nextOffset) }
                        },
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value <= -deleteThresholdPx) {
                                    offsetX.animateTo(
                                        targetValue = -maxDragPx,
                                        animationSpec = spring(
                                            dampingRatio = 0.42f,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                    )
                                    offsetX.animateTo(
                                        targetValue = -(itemWidthPx.takeIf { it > 0 } ?: 900).toFloat(),
                                        animationSpec = tween(durationMillis = 180),
                                    )
                                    onDeletePost(item.postUrl)
                                } else {
                                    offsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = 0.38f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.38f,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                            }
                        },
                    )
                }
                .clickable { onOpenPost(item.postUrl) },
        ) {
        val currentPreview = previewBitmap
        if (currentPreview != null) {
            Image(
                bitmap = currentPreview.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to AppColors.Surface,
                            0.38f to AppColors.Surface.copy(alpha = 0.94f),
                            0.66f to AppColors.Surface.copy(alpha = 0.66f),
                            1f to AppColors.Surface.copy(alpha = 0.18f),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.10f)),
            )
        }

        Column(
            modifier = Modifier
                .matchParentSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                item.title.ifBlank { item.postUrl },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${item.itemCount} 个下载项 · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.lastDownloadedAt))}",
                color = AppColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Text(
                item.postUrl,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = AppColors.TextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        }
    }
}

@Composable
fun ResultCard(resolved: ResolvedPost, onDownload: (MediaItem) -> Unit) {
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
fun VideoDownloadRow(entry: VideoEntry, onDownload: (MediaItem) -> Unit) {
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
fun DownloadButton(
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
fun RemotePreview(url: String?) {
    RemotePreview(url = url, bitmapOverride = null)
}

@Composable
private fun RemotePreview(url: String?, bitmapOverride: Bitmap?) {
    val isPreviewMode = LocalInspectionMode.current
    var bitmap by remember(url, bitmapOverride) { mutableStateOf(bitmapOverride) }

    LaunchedEffect(url, bitmapOverride, isPreviewMode) {
        if (bitmapOverride != null || isPreviewMode) {
            bitmap = bitmapOverride
            return@LaunchedEffect
        }

        bitmap = if (url.isNullOrBlank()) null else RemoteImageLoader.loadBitmap(url)
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
fun Footer() {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
        Text("Personal use only · powered by savetwitter api", color = AppColors.TextMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun PreviewFrame(
    modifier: Modifier = Modifier,
    notice: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = AppColors.Background,
        contentColor = AppColors.TextPrimary,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            ) {
                TopBar()
                Spacer(Modifier.height(32.dp))
                content()
                Spacer(Modifier.weight(1f))
                Footer()
            }

            if (notice != null) {
                TopNotice(
                    message = notice,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                )
            }
        }
    }
}

private fun samplePreviewBitmap(color: Int): Bitmap {
    return Bitmap.createBitmap(720, 720, Config.ARGB_8888).apply {
        eraseColor(color)
    }
}

private val previewRemoteBitmap: Bitmap
    get() = samplePreviewBitmap(0xFF3398DB.toInt())

private val previewHistoryBitmap: Bitmap
    get() = samplePreviewBitmap(0xFF735C3F.toInt())

private fun previewResolvedPost(): ResolvedPost {
    return ResolvedPost(
        title = "The new #TwitterAPI includes some interesting changes worth watching.",
        duration = "0:14",
        thumbnailUrl = null,
        mediaEntries = listOf(
            VideoEntry(
                video = MediaItem(
                    label = "Download MP4",
                    url = "https://example.com/video.mp4",
                    type = net.xmediadl.app.model.MediaType.Video,
                    quality = 1080,
                ),
                cover = MediaItem(
                    label = "Download Cover",
                    url = "https://example.com/cover.jpg",
                    type = net.xmediadl.app.model.MediaType.Photo,
                    fileSuffix = "-cover",
                ),
            ),
            PhotoEntry(
                photo = MediaItem(
                    label = "Download Photo",
                    url = "https://example.com/photo.jpg",
                    type = net.xmediadl.app.model.MediaType.Photo,
                ),
            ),
        ),
        elapsedMs = 550,
        postUrl = "https://x.com/user/status/1234567890",
    )
}

private fun previewHistoryItems(): List<DownloadHistoryPost> {
    val now = System.currentTimeMillis()
    return listOf(
        DownloadHistoryPost(
            postUrl = "https://x.com/CuteCatsMagic/status/2057125030610301155?s=20",
            title = "BREAKING UAE WILL LAUNCH AN OIL PIPELINE BYPASSING THE STRAIT OF HORMUZ",
            itemCount = 4,
            lastDownloadedAt = now,
            previewUrl = null,
            previewPath = null,
            previewFileName = "sample-1.jpg",
            previewMediaType = "Video",
        ),
        DownloadHistoryPost(
            postUrl = "https://x.com/CuteCatsMagic/status/2057319463423181065?s=20",
            title = "Untitled post",
            itemCount = 2,
            lastDownloadedAt = now - 86_400_000L,
            previewUrl = null,
            previewPath = null,
            previewFileName = "sample-2.jpg",
            previewMediaType = "Photo",
        ),
    )
}

@Preview(
    name = "Home Screen",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    widthDp = 412,
    heightDp = 915,
)
@Composable
private fun HomeScreenPreview() {
    MaterialTheme {
        PreviewFrame(notice = "已保存到相册：sample-1080p.mp4") {
            HomeScreen(
                input = "https://x.com/user/status/...",
                error = null,
                onInputChange = {},
                onPaste = {},
                onResolve = {},
                onHistory = {},
            )
        }
    }
}

@Preview(
    name = "Result Screen",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    widthDp = 412,
    heightDp = 915,
)
@Composable
private fun ResultScreenPreview() {
    MaterialTheme {
        PreviewFrame {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("// 解析结果", color = AppColors.Accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("选择要下载的媒体", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "视频 / 图片 · 2 个媒体资源 · 550 ms",
                    color = Color(0xFFB7B7B7),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.Surface)
                        .border(1.dp, AppColors.Border, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RemotePreview(url = null, bitmapOverride = previewRemoteBitmap)
                    previewResolvedPost().mediaEntries.forEach { entry ->
                        when (entry) {
                            is VideoEntry -> VideoDownloadRow(entry, onDownload = {})
                            is PhotoEntry -> DownloadButton(entry.photo, onDownload = {})
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The new #TwitterAPI includes some interesting changes worth watching.",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("0:14", color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Button(
                    onClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(10.dp)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.05f),
                        contentColor = Color(0xFFD6D6D6),
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("↩ Download more videos", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Preview(
    name = "History Screen",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    widthDp = 412,
    heightDp = 915,
)
@Composable
private fun HistoryScreenPreview() {
    MaterialTheme {
        PreviewFrame {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("// 下载历史", color = AppColors.Accent, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("已下载的帖子", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("点击条目会打开本机 X / Twitter 应用", color = Color(0xFFB7B7B7), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                previewHistoryItems().forEach { item ->
                    HistoryItem(
                        item = item,
                        onOpenPost = {},
                        onDeletePost = {},
                        previewBitmapOverride = previewHistoryBitmap,
                    )
                }
            }
        }
    }
}

@Preview(
    name = "History Item",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    widthDp = 412,
    heightDp = 180,
)
@Composable
private fun HistoryItemPreview() {
    MaterialTheme {
        Surface(color = AppColors.Background) {
            Box(modifier = Modifier.padding(16.dp)) {
                HistoryItem(
                    item = previewHistoryItems().first(),
                    onOpenPost = {},
                    onDeletePost = {},
                    previewBitmapOverride = previewHistoryBitmap,
                )
            }
        }
    }
}
