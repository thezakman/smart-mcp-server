package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.ToolField
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

private val DEFAULT_HISTORY_FIELDS = listOf(
    "id", "method", "host", "path", "statusCode", "mimeType", "requestBytes", "responseBytes", "color"
)
private val HISTORY_FIELDS = setOf(
    "id", "color", "method", "host", "path", "endpoint", "httpVersion", "statusCode", "mimeType",
    "requestBytes", "responseBytes", "parameterNames", "parameterNamesTruncated", "endpointTruncated",
    "responseStartMillis"
)
private val EXCHANGE_PARTS = setOf("SUMMARY", "REQUEST", "RESPONSE", "NOTES")

internal fun Server.registerSmartHistoryTools(api: MontoyaApi, config: McpConfig) {
    fun requireAccess() {
        check(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.HTTP_HISTORY, config) }) {
            "HTTP history access denied by Burp Suite"
        }
    }

    mcpTool<SearchHttpHistory>(
        "Search the Burp Proxy HTTP history with structured filters. Returns compact projected metadata only, " +
            "with a stable cursor and a total output budget. Use get_http_exchange to fetch selected raw content.",
        behavior = READ_ONLY_TOOL
    ) {
        validateHistoryPage(count, offset, 100)
        require(maxOutputChars in 1000..200000) { "maxOutputChars must be between 1000 and 200000" }
        require(cursor == null || offset == 0) { "offset must be 0 when cursor is provided" }
        require(fields.isNotEmpty()) { "fields must contain at least one field" }
        val unknownFields = fields.filterNot(HISTORY_FIELDS::contains)
        require(unknownFields.isEmpty()) { "Unknown fields: ${unknownFields.joinToString()}. Valid fields: ${HISTORY_FIELDS.sorted().joinToString()}" }

        val pageCursor = cursor?.let(::decodeHistoryCursor)
        val requestedOffset = pageCursor?.offset ?: offset
        val selectedColors = parseHistoryColors(colors)
        val matcher = regex?.takeIf(String::isNotBlank)?.let(::HistoryRegex)
        val normalizedMethods = methods.map { it.uppercase(Locale.ROOT) }.toSet()
        val normalizedMimeTypes = mimeTypes.map { it.uppercase(Locale.ROOT) }.toSet()
        requireAccess()

        var sequence = api.proxy().history().asSequence().filter { item ->
            val request = item.finalRequest()
            val url = safeSmart { request.url() }.orEmpty()
            val color = item.annotations().highlightColor() ?: HighlightColor.NONE
            val host = safeSmart { request.httpService().host() }.orEmpty()
            val path = historyPath(url)
            val response = item.response()
            val status = safeSmart { response?.statusCode()?.toInt() }
            val mime = safeSmart { response?.mimeType()?.name?.uppercase(Locale.ROOT) }
            val hasParams = safeSmart { request.parameters().isNotEmpty() } ?: false

            (selectedColors.isEmpty() || color in selectedColors) &&
                (!highlightedOnly || color != HighlightColor.NONE) &&
                (includeStatic || !isStaticHistoryUrl(url)) &&
                (hostContains.isNullOrBlank() || host.contains(hostContains, ignoreCase = true)) &&
                (pathPrefix.isNullOrBlank() || path.startsWith(pathPrefix)) &&
                (normalizedMethods.isEmpty() || request.method().uppercase(Locale.ROOT) in normalizedMethods) &&
                (statusCodes.isEmpty() || status in statusCodes) &&
                (normalizedMimeTypes.isEmpty() || mime in normalizedMimeTypes) &&
                (hasResponse == null || (response != null) == hasResponse) &&
                (hasParameters == null || hasParams == hasParameters)
        }

        if (inScopeOnly) sequence = sequence.filter { api.scope().isInScope(it.finalRequest().url()) }
        val structured = sequence.toList()
        val snapshotMaxId = pageCursor?.snapshotMaxId ?: structured.maxOfOrNull { it.id() }
        var snapshot = structured.filter { snapshotMaxId == null || it.id() <= snapshotMaxId }
        if (matcher != null) snapshot = filterHttpHistory(api, snapshot, false, matcher)
        snapshot = when (sort.uppercase(Locale.ROOT)) {
            "NEWEST" -> snapshot.sortedByDescending { it.id() }
            "OLDEST" -> snapshot.sortedBy { it.id() }
            "SLOWEST" -> snapshot.sortedByDescending {
                it.timingData()?.timeBetweenRequestSentAndStartOfResponse()?.toMillis() ?: -1
            }
            "LARGEST_RESPONSE" -> snapshot.sortedByDescending { it.response()?.toByteArray()?.length() ?: -1 }
            else -> throw IllegalArgumentException("sort must be NEWEST, OLDEST, SLOWEST or LARGEST_RESPONSE")
        }

        val candidates = snapshot.asSequence().drop(requestedOffset).take(count).map { item ->
            projectHistorySummary(smartHistorySummary(item), fields.toSet(), omitNulls)
        }.toList()
        buildBudgetedSearchPage(
            total = snapshot.size,
            offset = requestedOffset,
            snapshotMaxId = snapshotMaxId,
            candidates = candidates,
            maxOutputChars = maxOutputChars
        ).toString()
    }

    mcpTool<GetHttpExchange>(
        "Read selected parts of one Proxy HTTP exchange by native Burp ID. Raw captured content is unchanged by " +
            "default. The complete JSON response is bounded by maxOutputChars; follow each part's nextOffset.",
        behavior = READ_ONLY_TOOL
    ) {
        require(id >= 0) { "id must be a non-negative native Burp ID" }
        require(contentOffset >= 0) { "contentOffset must be non-negative" }
        require(maxOutputChars in 2000..200000) { "maxOutputChars must be between 2000 and 200000" }
        require(maxMessageChars in 256..50000) { "maxMessageChars must be between 256 and 50000" }
        val normalizedParts = parts.map { it.uppercase(Locale.ROOT) }.toSet()
        require(normalizedParts.isNotEmpty()) { "parts must contain at least one part" }
        val unknownParts = normalizedParts - EXCHANGE_PARTS
        require(unknownParts.isEmpty()) { "Unknown parts: ${unknownParts.joinToString()}. Valid parts: ${EXCHANGE_PARTS.joinToString()}" }
        val unknownFields = fields.filterNot(HISTORY_FIELDS::contains)
        require(unknownFields.isEmpty()) { "Unknown fields: ${unknownFields.joinToString()}" }
        requireAccess()

        val item = api.proxy().history().firstOrNull { it.id() == id }
            ?: error("No HTTP history item with Burp ID $id; it may have been removed")
        buildBudgetedExchange(
            item = item,
            parts = normalizedParts,
            fields = fields.toSet(),
            contentOffset = contentOffset,
            maxMessageChars = maxMessageChars,
            maxOutputChars = maxOutputChars,
            redactSecrets = redactSecrets,
            compact = compact,
            omitNulls = omitNulls
        ).toString()
    }
}

@Serializable
data class SearchHttpHistory(
    @ToolField("Maximum number of summaries to return.", minimum = 1, maximum = 100, example = "20")
    val count: Int = 20,
    @ToolField("Zero-based offset. Use cursor instead for stable subsequent pages.", minimum = 0)
    val offset: Int = 0,
    @ToolField("Opaque nextCursor returned by a previous call.")
    val cursor: String? = null,
    @ToolField("Fields to include in each result item.")
    val fields: List<String> = DEFAULT_HISTORY_FIELDS,
    @ToolField("Maximum characters for the complete JSON result.", minimum = 1000, maximum = 200000)
    val maxOutputChars: Int = 20000,
    @ToolField("Omit fields whose value is null.")
    val omitNulls: Boolean = true,
    @ToolField("Burp highlight colors. Empty selects all colors.", enumValues = ["RED", "ORANGE", "YELLOW", "GREEN", "CYAN", "BLUE", "PINK", "MAGENTA", "GRAY", "NONE"])
    val colors: List<String> = emptyList(),
    @ToolField("Return only items that have a highlight color.")
    val highlightedOnly: Boolean = false,
    @ToolField("Include common static assets.")
    val includeStatic: Boolean = false,
    @ToolField("Restrict results to the current Burp project scope.")
    val inScopeOnly: Boolean = false,
    @ToolField("Case-insensitive substring matched against the request host.")
    val hostContains: String? = null,
    @ToolField("Path prefix matched without query values.", example = "/api/")
    val pathPrefix: String? = null,
    @ToolField("HTTP methods to include.", example = "GET")
    val methods: List<String> = emptyList(),
    @ToolField("Response status codes to include.")
    val statusCodes: List<Int> = emptyList(),
    @ToolField("Burp response MIME names to include.")
    val mimeTypes: List<String> = emptyList(),
    @ToolField("Require a response when true, or require no response when false.")
    val hasResponse: Boolean? = null,
    @ToolField("Require at least one parsed request parameter when true.")
    val hasParameters: Boolean? = null,
    @ToolField("Optional bounded regex over URL, request, response and notes.")
    val regex: String? = null,
    @ToolField("Result ordering.", enumValues = ["NEWEST", "OLDEST", "SLOWEST", "LARGEST_RESPONSE"])
    val sort: String = "NEWEST"
)

@Serializable
data class GetHttpExchange(
    @ToolField("Native Burp Proxy history ID.", minimum = 0)
    val id: Int,
    @ToolField("Parts to return.", enumValues = ["SUMMARY", "REQUEST", "RESPONSE", "NOTES"])
    val parts: List<String> = listOf("SUMMARY", "REQUEST", "RESPONSE"),
    @ToolField("Summary fields to include.")
    val fields: List<String> = HISTORY_FIELDS.sorted(),
    @ToolField("Character offset used for request, response and notes.", minimum = 0)
    val contentOffset: Int = 0,
    @ToolField("Maximum characters for each message part.", minimum = 256, maximum = 50000)
    val maxMessageChars: Int = 10000,
    @ToolField("Maximum characters for the complete JSON result.", minimum = 2000, maximum = 200000)
    val maxOutputChars: Int = 30000,
    @ToolField("Explicitly mask common secrets. False preserves the original captured content.")
    val redactSecrets: Boolean = false,
    @ToolField("Compact repeated blank lines and oversized noise blocks.")
    val compact: Boolean = false,
    @ToolField("Omit null summary fields.")
    val omitNulls: Boolean = true
)

private data class HistoryPageCursor(val snapshotMaxId: Int?, val offset: Int)

internal fun encodeHistoryCursor(snapshotMaxId: Int?, offset: Int): String {
    val raw = "v1:${snapshotMaxId ?: -1}:$offset"
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
}

private fun decodeHistoryCursor(encoded: String): HistoryPageCursor {
    val raw = try {
        String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid history cursor")
    }
    val parts = raw.split(':')
    require(parts.size == 3 && parts[0] == "v1") { "Invalid history cursor" }
    val snapshot = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid history cursor")
    val offset = parts[2].toIntOrNull() ?: throw IllegalArgumentException("Invalid history cursor")
    require(snapshot >= -1 && offset >= 0) { "Invalid history cursor" }
    return HistoryPageCursor(snapshot.takeIf { it >= 0 }, offset)
}

private fun smartHistorySummary(item: ProxyHttpRequestResponse): JsonObject {
    val request = item.finalRequest()
    val url = safeSmart { request.url() }.orEmpty()
    return JsonObject(historySummary(item).toMutableMap().apply {
        put("host", JsonPrimitive(safeSmart { request.httpService().host() }.orEmpty()))
        put("path", JsonPrimitive(historyPath(url)))
    })
}

private fun historyPath(url: String): String = try {
    URI(url).rawPath?.ifBlank { "/" } ?: "/"
} catch (_: Exception) {
    "/" + url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#')
}

internal fun projectHistorySummary(summary: JsonObject, fields: Set<String>, omitNulls: Boolean): JsonObject =
    JsonObject(summary.filter { (name, value) -> name in fields && (!omitNulls || value !is JsonNull) })

private fun buildBudgetedSearchPage(
    total: Int,
    offset: Int,
    snapshotMaxId: Int?,
    candidates: List<JsonObject>,
    maxOutputChars: Int
): JsonObject {
    val accepted = mutableListOf<JsonObject>()
    for (candidate in candidates) {
        val trial = searchPageJson(
            total, offset, snapshotMaxId, accepted + candidate, hasMore = true, truncatedByBudget = false
        )
        if (trial.toString().length > maxOutputChars) break
        accepted += candidate
    }
    val nextOffset = offset + accepted.size
    val hasMore = nextOffset < total
    val result = searchPageJson(
        total,
        offset,
        snapshotMaxId,
        accepted,
        hasMore,
        truncatedByBudget = accepted.size < candidates.size
    )
    check(result.toString().length <= maxOutputChars) { "maxOutputChars is too small for search metadata" }
    return result
}

private fun searchPageJson(
    total: Int,
    offset: Int,
    snapshotMaxId: Int?,
    items: List<JsonObject>,
    hasMore: Boolean,
    truncatedByBudget: Boolean
): JsonObject = buildJsonObject {
    put("total", total)
    put("offset", offset)
    put("returned", items.size)
    put("snapshotMaxId", snapshotMaxId?.let(::JsonPrimitive) ?: JsonNull)
    put("nextCursor", if (hasMore) JsonPrimitive(encodeHistoryCursor(snapshotMaxId, offset + items.size)) else JsonNull)
    put("truncatedByBudget", truncatedByBudget)
    put("items", JsonArray(items))
}

private fun buildBudgetedExchange(
    item: ProxyHttpRequestResponse,
    parts: Set<String>,
    fields: Set<String>,
    contentOffset: Int,
    maxMessageChars: Int,
    maxOutputChars: Int,
    redactSecrets: Boolean,
    compact: Boolean,
    omitNulls: Boolean
): JsonObject {
    val values = linkedMapOf<String, JsonElement>(
        "source" to JsonPrimitive("PROXY"),
        "id" to JsonPrimitive(item.id())
    )
    if ("SUMMARY" in parts) {
        values["summary"] = projectHistorySummary(smartHistorySummary(item), fields, omitNulls)
    }
    val omitted = mutableListOf<String>()
    val rawParts = listOf(
        "REQUEST" to item.finalRequest().toString(),
        "RESPONSE" to item.response()?.toString(),
        "NOTES" to item.annotations().notes().orEmpty()
    )
    for ((part, raw) in rawParts) {
        if (part !in parts) continue
        if (raw == null) {
            values[part.lowercase()] = JsonNull
            continue
        }
        val key = part.lowercase()
        val remaining = maxOutputChars - JsonObject(values).toString().length - 256
        if (remaining < 512) {
            omitted += part
            continue
        }
        val allowed = minOf(maxMessageChars, (remaining - 256).coerceAtLeast(256))
        var window = messageWindow(raw, contentOffset, allowed, redactSecrets, compact)
        var trial = JsonObject(values + (key to window))
        if (trial.toString().length > maxOutputChars) {
            val overflow = trial.toString().length - maxOutputChars
            val reduced = (allowed - overflow - 64).coerceAtLeast(256)
            window = messageWindow(raw, contentOffset, reduced, redactSecrets, compact)
            trial = JsonObject(values + (key to window))
        }
        if (trial.toString().length <= maxOutputChars) values[key] = window else omitted += part
    }
    values["omittedParts"] = JsonArray(omitted.map(::JsonPrimitive))
    val result = JsonObject(values)
    check(result.toString().length <= maxOutputChars) { "maxOutputChars is too small for requested exchange metadata" }
    return result
}

private inline fun <T> safeSmart(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}
