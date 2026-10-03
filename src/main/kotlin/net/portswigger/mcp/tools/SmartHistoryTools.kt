package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.ToolField
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
            "including host, path, status, header, Trace ID and parsed JSON filters, with a filter-bound stable " +
            "keyset cursor and total output budget. Use get_http_exchange to fetch selected raw content.",
        behavior = READ_ONLY_TOOL
    ) {
        withHistorySearchPermit {
            validateHistoryPage(count, offset, 100)
            require(maxOutputChars in 1000..200000) { "maxOutputChars must be between 1000 and 200000" }
            require(cursor == null || offset == 0) { "offset must be 0 when cursor is provided" }
            require(fields.isNotEmpty()) { "fields must contain at least one field" }
            val unknownFields = fields.filterNot(HISTORY_FIELDS::contains)
            require(unknownFields.isEmpty()) { "Unknown fields: ${unknownFields.joinToString()}. Valid fields: ${HISTORY_FIELDS.sorted().joinToString()}" }

            val pageCursor = cursor?.let(::decodeHistoryCursor)
            val queryHash = historyQueryHash(this)
            require(pageCursor?.queryHash == null || pageCursor.queryHash == queryHash) {
                "History cursor does not match the current filters, projection or sort"
            }
            val requestedOffset = pageCursor?.offset ?: offset
            val selectedColors = parseHistoryColors(colors)
            val matcher = regex?.takeIf(String::isNotBlank)?.let(::HistoryRegex)
            val normalizedMethods = methods.map { it.uppercase(Locale.ROOT) }.toSet()
            val normalizedMimeTypes = mimeTypes.map { it.uppercase(Locale.ROOT) }.toSet()
            requireAccess()

            var sequence = HistoryMetadataIndex.refresh(api.proxy().history()).asSequence().filter { entry ->
                (selectedColors.isEmpty() || entry.color in selectedColors) &&
                    (!highlightedOnly || entry.color != HighlightColor.NONE) &&
                    (includeStatic || !isStaticHistoryUrl(entry.url)) &&
                    (hostContains.isNullOrBlank() || entry.host.contains(hostContains, ignoreCase = true)) &&
                    (pathPrefix.isNullOrBlank() || entry.path.startsWith(pathPrefix)) &&
                    (normalizedMethods.isEmpty() || entry.method in normalizedMethods) &&
                    (statusCodes.isEmpty() || entry.statusCode in statusCodes) &&
                    (normalizedMimeTypes.isEmpty() || entry.mimeType in normalizedMimeTypes) &&
                    (hasResponse == null || entry.hasResponse == hasResponse) &&
                    (hasParameters == null || entry.hasParameters == hasParameters) &&
                    (headerName.isNullOrBlank() || entry.headerNames.any { it.equals(headerName, ignoreCase = true) }) &&
                    (headerValueContains.isNullOrBlank() || entry.headerValues.contains(headerValueContains, ignoreCase = true)) &&
                    (traceId.isNullOrBlank() || entry.traceIds.any { it.contains(traceId, ignoreCase = true) })
            }

            if (inScopeOnly) sequence = sequence.filter { api.scope().isInScope(it.url) }
            val structured = sequence.toList()
            val snapshotMaxId = pageCursor?.snapshotMaxId ?: structured.maxOfOrNull { it.id }
            var snapshot = structured.filter { snapshotMaxId == null || it.id <= snapshotMaxId }
            if (matcher != null) {
                val matchingIds = filterHttpHistory(api, snapshot.map { it.item }, false, matcher).map { it.id() }.toSet()
                snapshot = snapshot.filter { it.id in matchingIds }
            }
            if (!jsonKey.isNullOrBlank() || !jsonContains.isNullOrBlank()) {
                snapshot = snapshot.filter { matchesJsonContent(it.item, jsonKey, jsonContains) }
            }
            val normalizedSort = sort.uppercase(Locale.ROOT)
            snapshot = when (normalizedSort) {
                "NEWEST" -> snapshot.sortedByDescending { it.id }
                "OLDEST" -> snapshot.sortedBy { it.id }
                "SLOWEST" -> snapshot.sortedWith(compareByDescending<IndexedHttpHistory> {
                    it.responseStartMillis ?: -1
                }.thenByDescending { it.id })
                "LARGEST_RESPONSE" -> snapshot.sortedWith(compareByDescending<IndexedHttpHistory> {
                    it.responseBytes.toLong()
                }.thenByDescending { it.id })
                else -> throw IllegalArgumentException("sort must be NEWEST, OLDEST, SLOWEST or LARGEST_RESPONSE")
            }

            val pageSequence = pageCursor?.lastId?.let { lastId ->
                snapshot.asSequence().filter { isAfterHistoryCursor(it, lastId, pageCursor.lastMetric, normalizedSort) }
            } ?: snapshot.asSequence().drop(requestedOffset)
            val candidates = pageSequence.take(count).map { entry ->
                HistorySearchCandidate(entry, projectHistorySummary(smartHistorySummary(entry.item), fields.toSet(), omitNulls))
            }.toList()
            buildBudgetedSearchPage(
                total = snapshot.size,
                offset = requestedOffset,
                snapshotMaxId = snapshotMaxId,
                candidates = candidates,
                maxOutputChars = maxOutputChars,
                sort = normalizedSort,
                queryHash = queryHash
            ).toString()
        }
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
    @ToolField("Header name matched case-insensitively across request and response headers.")
    val headerName: String? = null,
    @ToolField("Case-insensitive substring matched across request and response header values.")
    val headerValueContains: String? = null,
    @ToolField("Case-insensitive Trace ID substring matched in trace, correlation and request ID headers.")
    val traceId: String? = null,
    @ToolField("JSON key or dotted key path required in a request or response body.", example = "data.user.id")
    val jsonKey: String? = null,
    @ToolField("Case-insensitive substring required in a parsed JSON request or response body.")
    val jsonContains: String? = null,
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

private data class HistoryPageCursor(
    val snapshotMaxId: Int?,
    val offset: Int,
    val lastId: Int? = null,
    val lastMetric: Long? = null,
    val sort: String? = null,
    val queryHash: String? = null
)

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
    if (parts.size == 7 && parts[0] == "v2") {
        val snapshot = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid history cursor")
        val offset = parts[2].toIntOrNull() ?: throw IllegalArgumentException("Invalid history cursor")
        val lastId = parts[3].toIntOrNull() ?: throw IllegalArgumentException("Invalid history cursor")
        val metric = parts[4].toLongOrNull() ?: throw IllegalArgumentException("Invalid history cursor")
        require(snapshot >= -1 && offset >= 0) { "Invalid history cursor" }
        return HistoryPageCursor(snapshot.takeIf { it >= 0 }, offset, lastId, metric, parts[5], parts[6])
    }
    require(parts.size == 3 && parts[0] == "v1") { "Invalid history cursor" }
    val snapshot = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid history cursor")
    val offset = parts[2].toIntOrNull() ?: throw IllegalArgumentException("Invalid history cursor")
    require(snapshot >= -1 && offset >= 0) { "Invalid history cursor" }
    return HistoryPageCursor(snapshot.takeIf { it >= 0 }, offset)
}

private fun encodeStableHistoryCursor(
    snapshotMaxId: Int?, offset: Int, last: IndexedHttpHistory, sort: String, queryHash: String
): String {
    val raw = "v2:${snapshotMaxId ?: -1}:$offset:${last.id}:${historySortMetric(last, sort)}:$sort:$queryHash"
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
}

private fun smartHistorySummary(item: ProxyHttpRequestResponse): JsonObject {
    val request = item.finalRequest()
    val url = safeSmart { request.url() }.orEmpty()
    return JsonObject(historySummary(item).toMutableMap().apply {
        put("host", JsonPrimitive(safeSmart { request.httpService().host() }.orEmpty()))
        put("path", JsonPrimitive(indexedHistoryPath(url)))
    })
}

internal fun projectHistorySummary(summary: JsonObject, fields: Set<String>, omitNulls: Boolean): JsonObject =
    JsonObject(summary.filter { (name, value) -> name in fields && (!omitNulls || value !is JsonNull) })

private data class HistorySearchCandidate(val entry: IndexedHttpHistory, val json: JsonObject)

private fun buildBudgetedSearchPage(
    total: Int,
    offset: Int,
    snapshotMaxId: Int?,
    candidates: List<HistorySearchCandidate>,
    maxOutputChars: Int,
    sort: String,
    queryHash: String
): JsonObject {
    val accepted = mutableListOf<HistorySearchCandidate>()
    for (candidate in candidates) {
        val trial = searchPageJson(
            total, offset, snapshotMaxId, accepted + candidate, hasMore = true,
            truncatedByBudget = false, sort = sort, queryHash = queryHash
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
        truncatedByBudget = accepted.size < candidates.size,
        sort = sort,
        queryHash = queryHash
    )
    check(result.toString().length <= maxOutputChars) { "maxOutputChars is too small for search metadata" }
    return result
}

private fun searchPageJson(
    total: Int,
    offset: Int,
    snapshotMaxId: Int?,
    items: List<HistorySearchCandidate>,
    hasMore: Boolean,
    truncatedByBudget: Boolean,
    sort: String,
    queryHash: String
): JsonObject = buildJsonObject {
    put("total", total)
    put("offset", offset)
    put("returned", items.size)
    put("snapshotMaxId", snapshotMaxId?.let(::JsonPrimitive) ?: JsonNull)
    put("nextCursor", if (hasMore && items.isNotEmpty()) JsonPrimitive(
        encodeStableHistoryCursor(snapshotMaxId, offset + items.size, items.last().entry, sort, queryHash)
    ) else JsonNull)
    put("truncatedByBudget", truncatedByBudget)
    put("items", JsonArray(items.map { it.json }))
}

private fun historySortMetric(entry: IndexedHttpHistory, sort: String): Long = when (sort) {
    "SLOWEST" -> entry.responseStartMillis ?: -1L
    "LARGEST_RESPONSE" -> entry.responseBytes.toLong()
    else -> entry.id.toLong()
}

internal fun isAfterHistoryCursor(
    entry: IndexedHttpHistory, lastId: Int, lastMetric: Long?, sort: String
): Boolean = when (sort) {
    "NEWEST" -> entry.id < lastId
    "OLDEST" -> entry.id > lastId
    "SLOWEST", "LARGEST_RESPONSE" -> {
        val metric = historySortMetric(entry, sort)
        val boundary = lastMetric ?: Long.MIN_VALUE
        metric < boundary || (metric == boundary && entry.id < lastId)
    }
    else -> false
}

private fun historyQueryHash(args: SearchHttpHistory): String {
    val canonical = Json.encodeToString(
        args.copy(count = 0, offset = 0, cursor = null, maxOutputChars = 0)
    )
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .take(10).joinToString("") { "%02x".format(it) }
}

internal fun matchesJsonContent(
    item: ProxyHttpRequestResponse, requiredKey: String?, requiredContent: String?
): Boolean {
    val bodies = listOfNotNull(
        safeSmart { item.finalRequest().bodyToString() },
        safeSmart { item.response()?.bodyToString() }
    )
    return bodies.any { raw ->
        val root = safeSmart { Json.parseToJsonElement(raw) } ?: return@any false
        val paths = linkedSetOf<String>()
        fun visit(element: JsonElement, path: String, depth: Int) {
            if (depth > 30 || paths.size >= 10_000) return
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    val next = if (path.isEmpty()) key else "$path.$key"
                    paths += next
                    visit(value, next, depth + 1)
                }
                is JsonArray -> element.take(100).forEach { visit(it, "$path[]", depth + 1) }
                else -> Unit
            }
        }
        visit(root, "", 0)
        (requiredKey.isNullOrBlank() || paths.any {
            it.equals(requiredKey, ignoreCase = true) || it.substringAfterLast('.').equals(requiredKey, ignoreCase = true)
        }) && (requiredContent.isNullOrBlank() || root.toString().contains(requiredContent, ignoreCase = true))
    }
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
        "id" to JsonPrimitive(item.id()),
        "resourceUris" to buildJsonObject {
            put("request", "burp://proxy/${item.id()}/request")
            if (item.response() != null) put("response", "burp://proxy/${item.id()}/response")
        }
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
            values[part.lowercase()] = buildJsonObject {
                put("text", "")
                put("offset", contentOffset)
                put("nextOffset", contentOffset)
                put("truncated", true)
                put("omittedByOutputBudget", true)
                put("retrieval", "Request this part alone with the same contentOffset, or use its burp:// resource URI")
            }
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
    values["complete"] = JsonPrimitive(omitted.isEmpty())
    val result = JsonObject(values)
    check(result.toString().length <= maxOutputChars) { "maxOutputChars is too small for requested exchange metadata" }
    return result
}

private inline fun <T> safeSmart(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}
