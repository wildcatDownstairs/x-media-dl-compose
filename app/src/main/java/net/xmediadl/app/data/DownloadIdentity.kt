package net.xmediadl.app.data

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Builds a durable identity for a downloaded media item.
 *
 * SaveTwitter returns short-lived wrapper URLs whose JWT changes on every resolve. The payload,
 * however, contains the stable twimg source URL. Prefer that source and only fall back to the
 * post/media slot when the wrapper cannot be inspected.
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
