package net.xmediadl.app.utils

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets

/** 从分享 Intent 或深链接 Intent 中提取并校验 X/Twitter 帖子 URL。 */
fun extractUrlFromIntent(intent: Intent?): String? {
    // 支持两类入口：
    // ACTION_SEND 是从 X App 或浏览器分享文本进来；
    // ACTION_VIEW 是用户点 x.com/twitter.com 链接时由系统直接交给本 App。
    if (intent == null) return null
    val raw = when (intent.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW -> intent.dataString
        else -> null
    }
    return raw?.let(::normalizeSharedText)?.takeIf(::looksLikeXUrl)
}

/** 从可能混有标题、换行和标点的分享文本中提取第一条 HTTP(S) URL。 */
fun normalizeSharedText(text: String): String {
    // 分享文本经常包含标题、换行、表情或多个 URL。
    // 这里只取第一段 http/https 链接，再去掉末尾常见标点，避免把中文句号也带进请求。
    val match = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE).find(text)
    return (match?.value ?: text.trim()).trimEnd(')', ',', '.', '，', '。')
}

/**
 * 仅校验 URL 的 host 是否属于受支持的 X/Twitter 域名。
 *
 * 这里不要求固定路径格式，因为 X 会产生 `/user/status/...`、`/i/status/...` 等多种链接；
 * 帖子是否存在、是否公开交给解析服务判断。
 */
fun looksLikeXUrl(value: String): Boolean {
    // 这里只做 host 级别校验。帖子是否公开、是否能解析，由 SaveTwitter 接口决定。
    return runCatching {
        val host = Uri.parse(value).host.orEmpty().lowercase()
        host in setOf("x.com", "twitter.com", "www.x.com", "www.twitter.com", "mobile.twitter.com")
    }.getOrDefault(false)
}

/** 读取剪贴板首项并宽容处理厂商未正确声明 MIME 类型的情况。 */
fun readClipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return ""

    // 有些系统剪贴板不会严格标成 text/plain，但 coerceToText 仍然能取出文本。
    // 先保留 text/plain 判断，再允许其它 MIME 走 coerce，提升从 X App 复制链接的成功率。
    val canReadText = clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
        clip.description.hasMimeType("text/*") ||
        clip.itemCount > 0
    if (!canReadText) return ""

    return clip.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

/** 读取、规范化剪贴板内容，并只在它是受支持帖子链接时返回。 */
fun readClipboardXUrl(context: Context): String? {
    val url = normalizeSharedText(readClipboardText(context))
    return url.takeIf(::looksLikeXUrl)
}

/** 使用系统 URL 分发打开帖子；若安装了 X App，系统通常会优先交给它处理。 */
fun Context.openPostUrl(postUrl: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(postUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}

/** 去除 SaveTwitter 返回的简单 HTML 标签并解码实体，用于生成纯文本标题/按钮文案。 */
fun String.stripTags(): String {
    return replace(Regex("""<[^>]+>"""), "")
        .htmlUnescape()
        .trim()
}

/** 解码接口 HTML 中常见实体；最终交给 Android Html 处理其余合法实体。 */
fun String.htmlUnescape(): String {
    val decoded = replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
    return Html.fromHtml(decoded, Html.FROM_HTML_MODE_LEGACY).toString()
}

/**
 * 同时支持读取成功响应和 HTTP 错误响应。
 *
 * [HttpURLConnection.inputStream] 在非 2xx 时会直接抛错，因此必须切换到 errorStream，
 * 才能把上游返回的真实错误信息传给解析层。
 */
fun HttpURLConnection.readTextSafely(): String {
    // 非 2xx 响应读取 inputStream 会抛异常，errorStream 才有错误内容。
    // 把错误体也读出来，可以让解析失败提示更接近真实原因。
    val stream = if (responseCode in 200..299) inputStream else errorStream
    return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
}

/** 网络请求统一使用桌面浏览器 UA，减少上游按移动端返回不同 HTML 结构的概率。 */
const val desktopUserAgent: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Safari/537.36"
