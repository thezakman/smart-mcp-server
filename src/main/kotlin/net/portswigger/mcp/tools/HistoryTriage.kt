package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import java.util.Locale

/** Read-only triage inspired by geozin/burp-mcp-colorstrike; existing history tools remain compatible. */
internal fun Server.registerHistoryTriageTools(api: MontoyaApi, config: McpConfig) {
    fun requireAccess() {
        check(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.HTTP_HISTORY, config) }) {
            "HTTP history access denied by Burp Suite"
        }
    }

    mcpTool<GetProxyHttpHistorySummary>(
        "Read-only summary of captured HTTP traffic: native Burp IDs, colors, endpoints without query values, " +
            "status, parameter names and observed response-start timing. No bodies. " +
            "Defaults to highlighted traffic; colors=[] selects all colors. includeStatic controls asset filtering. " +
            "Optional regex searches captured URL/request/response/notes; inScopeOnly uses Burp project scope. " +
            "Regex searches have bounded work and fail explicitly when limits are exceeded. " +
            "count=1..100; offset paginates filtered items, newest first. Reuse snapshotMaxId from the first " +
            "response on later pages to exclude newly arriving traffic. Returns JSON and nextOffset."
    ) {
        validateHistoryPage(count, offset, 100)
        val selectedColors = parseHistoryColors(colors)
        val matcher = regex?.let { HistoryRegex(it) }
        requireAccess()
        val items = filterHttpHistory(api,
            selectHistory(api.proxy().history(), selectedColors, highlightedOnly, includeStatic), inScopeOnly, matcher)
            .filter { snapshotMaxId == null || it.id() <= snapshotMaxId }
        historyPage(items, count, offset) { historySummary(it) }.toString()
    }

    mcpTool<GetRequestsByColor>(
        "Read captured requests/responses by highlight colors (RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, " +
            "PINK, MAGENTA, GRAY, NONE). No requests are sent. count=1..20, offset paginates items. " +
            "Captured credentials are returned unchanged by default for security analysis; " +
            "set redactSecrets=true only when explicitly needed. maxMessageChars=256..50000 per message. " +
            "Optional regex searches captured URL/request/response/notes; inScopeOnly uses project scope. " +
            "Returns valid JSON with explicit truncation metadata. Reuse snapshotMaxId while paging. " +
            "Use get_request_by_index for further chunks."
    ) {
        validateHistoryPage(count, offset, 20)
        validateMessageWindow(0, maxMessageChars)
        require(colors.isNotEmpty()) { "Provide at least one color" }
        val selectedColors = parseHistoryColors(colors)
        val matcher = regex?.let { HistoryRegex(it) }
        requireAccess()
        val items = filterHttpHistory(api,
            selectHistory(api.proxy().history(), selectedColors, false, includeStatic), inScopeOnly, matcher)
            .filter { snapshotMaxId == null || it.id() <= snapshotMaxId }
        historyPage(items, count, offset) { historyDetails(it, 0, maxMessageChars, redactSecrets, compact) }.toString()
    }

    mcpTool<GetRequestByIndex>(
        "Read a captured HTTP exchange by its native Burp # ID (index is NOT a list offset). " +
            "Includes uncolored and static items. No requests are sent. " +
            "contentOffset and maxMessageChars=256..50000 allow bounded access to complete request/response text; " +
            "follow each message's nextOffset. Original captured text is returned by default. " +
            "redactSecrets=true enables best-effort masking. Returns an error if the ID no longer exists."
    ) {
        require(index >= 0) { "index must be a non-negative native Burp ID" }
        validateMessageWindow(contentOffset, maxMessageChars)
        requireAccess()
        val item = api.proxy().history().firstOrNull { it.id() == index }
            ?: error("No HTTP history item with Burp ID $index; it may have been removed")
        historyDetails(item, contentOffset, maxMessageChars, redactSecrets, compact).toString()
    }
}

@Serializable
data class GetProxyHttpHistorySummary(
    val count: Int = 20,
    val offset: Int = 0,
    val colors: List<String> = emptyList(),
    val highlightedOnly: Boolean = true,
    val includeStatic: Boolean = false,
    val regex: String? = null,
    val inScopeOnly: Boolean = false,
    val snapshotMaxId: Int? = null
)

@Serializable
data class GetRequestsByColor(
    val colors: List<String>,
    val count: Int = 5,
    val offset: Int = 0,
    val includeStatic: Boolean = false,
    val redactSecrets: Boolean = false,
    val compact: Boolean = true,
    val maxMessageChars: Int = 5000,
    val regex: String? = null,
    val inScopeOnly: Boolean = false,
    val snapshotMaxId: Int? = null
)

@Serializable
data class GetRequestByIndex(
    val index: Int,
    val contentOffset: Int = 0,
    val maxMessageChars: Int = 10000,
    val redactSecrets: Boolean = false,
    val compact: Boolean = false
)

internal fun parseHistoryColors(colors: List<String>): Set<HighlightColor> = colors.map { value ->
    HighlightColor.entries.firstOrNull { it.name == value.trim().uppercase(Locale.ROOT) }
        ?: throw IllegalArgumentException("Unknown color '$value'. Valid colors: ${HighlightColor.entries.joinToString { it.name }}")
}.toSet()

private val staticExtensions = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "svg", "ico", "bmp", "avif", "woff", "woff2", "ttf", "otf", "eot",
    "css", "js", "mjs", "map", "mp3", "mp4", "webm", "ogg", "wav", "pdf", "zip", "gz", "br"
)

internal fun isStaticHistoryUrl(url: String): Boolean =
    url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "")
        .lowercase(Locale.ROOT) in staticExtensions

internal fun selectHistory(
    history: List<ProxyHttpRequestResponse>, colors: Set<HighlightColor>, highlightedOnly: Boolean, includeStatic: Boolean
): List<ProxyHttpRequestResponse> = history.asSequence().filter {
    val color = it.annotations().highlightColor() ?: HighlightColor.NONE
    (!highlightedOnly || color != HighlightColor.NONE) && (colors.isEmpty() || color in colors) &&
        (includeStatic || !isStaticHistoryUrl(safeHistory { it.finalRequest().url() }.orEmpty()))
}.sortedByDescending { it.id() }.toList()

internal fun validateHistoryPage(count: Int, offset: Int, maximum: Int) {
    require(count in 1..maximum) { "count must be between 1 and $maximum" }
    require(offset >= 0) { "offset must be non-negative" }
}

internal fun validateMessageWindow(offset: Int, maxChars: Int) {
    require(offset >= 0) { "contentOffset must be non-negative" }
    require(maxChars in 256..50000) { "maxMessageChars must be between 256 and 50000" }
}

internal fun historyPage(
    items: List<ProxyHttpRequestResponse>, count: Int, offset: Int, mapper: (ProxyHttpRequestResponse) -> JsonObject
): JsonObject {
    // Slice before mapping: bodies outside this page are never serialized or sanitized.
    val page = items.asSequence().drop(offset).take(count).map(mapper).toList()
    val next = offset.toLong() + page.size
    return buildJsonObject {
        put("total", items.size)
        put("snapshotMaxId", items.maxOfOrNull { it.id() }?.let(::JsonPrimitive) ?: JsonNull)
        put("offset", offset)
        put("returned", page.size)
        put("nextOffset", if (next < items.size) JsonPrimitive(next) else JsonNull)
        put("colorCounts", buildJsonObject {
            items.groupingBy { (it.annotations().highlightColor() ?: HighlightColor.NONE).name }.eachCount()
                .toSortedMap().forEach { (color, count) -> put(color, count) }
        })
        put("items", JsonArray(page))
    }
}

internal fun historySummary(item: ProxyHttpRequestResponse): JsonObject {
    val request = item.finalRequest()
    val response = item.response()
    val parameterNames = safeHistory { request.parameters().map { it.name() }.distinct() }.orEmpty()
    val rawEndpoint = safeHistory { request.url().substringBefore('?').substringBefore('#') }.orEmpty()
    return buildJsonObject {
        put("id", item.id())
        put("color", (item.annotations().highlightColor() ?: HighlightColor.NONE).name)
        put("method", safeHistory { request.method().take(32) }?.let(::JsonPrimitive) ?: JsonNull)
        put("endpoint", rawEndpoint.take(2048))
        put("httpVersion", safeHistory { request.httpVersion() }?.let(::JsonPrimitive) ?: JsonNull)
        put("statusCode", safeHistory { response?.statusCode()?.toInt() }?.let(::JsonPrimitive) ?: JsonNull)
        put("mimeType", safeHistory { response?.mimeType()?.name }?.let(::JsonPrimitive) ?: JsonNull)
        put("requestBytes", safeHistory { request.toByteArray().length() } ?: 0)
        put("responseBytes", safeHistory { response?.toByteArray()?.length() } ?: 0)
        put("parameterNames", JsonArray(parameterNames.take(100).map { JsonPrimitive(it.take(128)) }))
        put("parameterNamesTruncated", parameterNames.size > 100 || parameterNames.any { it.length > 128 })
        put("endpointTruncated", rawEndpoint.length > 2048)
        // Timing is observed response-start latency, not proof of any server-side behavior.
        put("responseStartMillis", item.timingData()?.timeBetweenRequestSentAndStartOfResponse()?.toMillis()
            ?.let(::JsonPrimitive) ?: JsonNull)
    }
}

private inline fun <T> safeHistory(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

internal fun historyDetails(
    item: ProxyHttpRequestResponse, offset: Int, maxChars: Int, redact: Boolean, compact: Boolean
): JsonObject = buildJsonObject {
    put("summary", historySummary(item))
    put("redactSecrets", redact)
    put("compact", compact)
    put("request", messageWindow(item.finalRequest().toString(), offset, maxChars, redact, compact))
    put("response", item.response()?.let { messageWindow(it.toString(), offset, maxChars, redact, compact) } ?: JsonNull)
    put("notes", messageWindow(item.annotations().notes().orEmpty(), offset, maxChars, redact, compact))
}

internal fun messageWindow(raw: String, offset: Int, maxChars: Int, redact: Boolean, compact: Boolean): JsonObject {
    validateMessageWindow(offset, maxChars)
    val text = sanitizeHistoryText(raw, redact, compact)
    var start = offset.coerceAtMost(text.length)
    if (start > 0 && start < text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start++
    var end = (start.toLong() + maxChars).coerceAtMost(text.length.toLong()).toInt()
    if (end > start && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return buildJsonObject {
        put("text", text.substring(start, end))
        put("originalCharacters", raw.length)
        put("availableCharacters", text.length)
        put("offset", start)
        put("nextOffset", if (end < text.length) JsonPrimitive(end) else JsonNull)
        put("truncated", start > 0 || end < text.length)
        put("transformed", text != raw)
    }
}

private val credentialHeader = Regex(
    "(?im)^(authorization|proxy-authorization|cookie|set-cookie|x-api-key|api-key|x-auth-token|x-csrf-token|x-xsrf-token):[^\\r\\n]*(?:\\r?\\n[ \\t]+[^\\r\\n]*)*"
)
private val bearerToken = Regex("(?i)\\bBearer[ \\t]+[A-Za-z0-9._~+/=-]+")
private val jwt = Regex("\\beyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*")
private val jsonCredential = Regex(
    "(?i)(\"(?:access_token|refresh_token|id_token|token|password|secret|api_key|apikey|client_secret)\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\""
)
private val formCredential = Regex(
    "(?im)((?:[?&]|^)(?:access_token|refresh_token|id_token|token|password|secret|api_key|apikey|client_secret)=)[^&\\s]*"
)
private val svg = Regex("(?is)<svg\\b[^>]*>.*?</svg\\s*>")
private val base64Blob = Regex("[A-Za-z0-9+/]{256,}={0,2}")
private val viewState = Regex("(?i)(__VIEWSTATE(?:GENERATOR|ENCRYPTED)?=)[^&\\s]+")

internal fun sanitizeHistoryText(raw: String, redact: Boolean, compact: Boolean): String {
    var text = raw
    if (redact) {
        text = credentialHeader.replace(text) { "${it.groupValues[1]}: [REDACTED]" }
        text = bearerToken.replace(text, "Bearer [REDACTED]")
        text = jwt.replace(text, "[JWT REDACTED]")
        text = jsonCredential.replace(text) { "${it.groupValues[1]}\"[REDACTED]\"" }
        text = formCredential.replace(text) { "${it.groupValues[1]}[REDACTED]" }
    }
    if (compact) {
        text = svg.replace(text, "[SVG OMITTED]")
        text = viewState.replace(text) { "${it.groupValues[1]}[VIEWSTATE OMITTED]" }
        text = base64Blob.replace(text, "[LONG ENCODED VALUE OMITTED]")
    }
    return text
}
