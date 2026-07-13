package net.xmediadl.app.data

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 为下载媒体生成跨启动稳定的资源身份。
 *
 * SaveTwitter 返回的包装 URL 含有每次解析都会变化的 JWT，但 payload 内部仍保存稳定的
 * twimg 源地址。能解包时优先使用源地址；上游格式变化、无法解包时，才回退到“帖子 ID +
 * 媒体类型 + 槽位后缀”。因此重启 App 后重新解析得到新 token，仍会命中旧历史。
 */
internal fun mediaResourceKey(
    postUrl: String,
    mediaUrl: String,
    mediaType: String,
    fileSuffix: String,
): String {
    val normalizedType = mediaType.lowercase()
    val sourceUrl = stableMediaSourceUrl(mediaUrl)
    return if (sourceUrl != null) {
        "source:$normalizedType:$sourceUrl"
    } else {
        "slot:${postIdentity(postUrl)}:$normalizedType:${fileSuffix.ifBlank { "primary" }}"
    }
}

/** v4 迁移入口：旧行没有 fileSuffix，只能从历史文件名末尾恢复媒体槽位。 */
internal fun legacyMediaResourceKey(
    postUrl: String,
    mediaUrl: String,
    mediaType: String,
    fileName: String,
): String = mediaResourceKey(
    postUrl = postUrl,
    mediaUrl = mediaUrl,
    mediaType = mediaType,
    fileSuffix = legacyFileSuffix(mediaType, fileName),
)

/**
 * 从临时包装 URL 中提取并规范化稳定源地址。
 *
 * 查询参数通常只是尺寸、缓存或签名提示，所以资源身份只保留 scheme、host 和 path。
 * 若上游直接返回带媒体扩展名的 URL，也走相同规范化流程。
 */
internal fun stableMediaSourceUrl(mediaUrl: String): String? {
    val uri = runCatching { URI(mediaUrl) }.getOrNull() ?: return null
    val wrappedSource = queryParameter(uri.rawQuery, "token")
        ?.let(::decodeJwtSourceUrl)
    val candidate = wrappedSource ?: mediaUrl.takeIf { uri.path.hasMediaExtension() } ?: return null
    val sourceUri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = sourceUri.scheme?.lowercase() ?: return null
    val host = sourceUri.host?.lowercase() ?: return null
    val port = sourceUri.port.takeIf { it >= 0 }
    val authority = if (port == null) host else "$host:$port"
    return runCatching {
        URI(scheme, authority, sourceUri.path, null, null).toASCIIString()
    }.getOrNull()
}

/** Base64URL 解码 JWT payload；格式不符合预期时返回 null，让上层安全使用槽位回退。 */
private fun decodeJwtSourceUrl(token: String): String? {
    val payload = token.split('.').getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    val paddedPayload = payload.padEnd(payload.length + (4 - payload.length % 4) % 4, '=')
    return runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(paddedPayload), StandardCharsets.UTF_8)
        JSONObject(decoded).optString("url").takeIf { it.isNotBlank() }
    }.getOrNull()
}

private fun queryParameter(rawQuery: String?, name: String): String? = rawQuery
    ?.split('&')
    ?.asSequence()
    ?.map { part -> part.substringBefore('=') to part.substringAfter('=', "") }
    ?.firstOrNull { (key, _) -> URLDecoder.decode(key, StandardCharsets.UTF_8.name()) == name }
    ?.second
    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }

/** 统一域名、用户名路径、`/i/status/` 和分享参数，最终优先只保留 status 数字 ID。 */
private fun postIdentity(postUrl: String): String {
    val uri = runCatching { URI(postUrl) }.getOrNull()
    val statusId = uri?.path
        ?.let { Regex("/(?:i/)?status/(\\d+)", RegexOption.IGNORE_CASE).find(it) }
        ?.groupValues
        ?.getOrNull(1)
    if (statusId != null) return "status:$statusId"

    val host = uri?.host?.lowercase().orEmpty()
    val path = uri?.path?.trimEnd('/').orEmpty()
    return "$host$path".ifBlank { postUrl.substringBefore('?').substringBefore('#').lowercase() }
}

/** 从旧版 buildFileName 产物末尾恢复多视频、多图片和封面所使用的稳定后缀。 */
private fun legacyFileSuffix(mediaType: String, fileName: String): String {
    if (mediaType.equals("Video", ignoreCase = true)) {
        val index = Regex("-video-(\\d+)-\\d+p\\.[^.]+$", RegexOption.IGNORE_CASE)
            .find(fileName)
            ?.groupValues
            ?.getOrNull(1)
        return index?.let { "-video-$it" }.orEmpty()
    }

    Regex("-video-(\\d+)-cover\\.[^.]+$", RegexOption.IGNORE_CASE)
        .find(fileName)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return "-video-$it-cover" }
    if (Regex("-cover\\.[^.]+$", RegexOption.IGNORE_CASE).containsMatchIn(fileName)) return "-cover"
    Regex("-photo-(\\d+)\\.[^.]+$", RegexOption.IGNORE_CASE)
        .find(fileName)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return "-photo-$it" }
    return ""
}

private fun String?.hasMediaExtension(): Boolean = this
    ?.lowercase()
    ?.let { path -> listOf(".mp4", ".jpg", ".jpeg", ".png", ".webp", ".gif").any(path::endsWith) }
    ?: false
