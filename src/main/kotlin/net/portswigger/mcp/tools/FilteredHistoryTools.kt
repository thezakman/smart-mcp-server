package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.proxy.ProxyWebSocketMessage
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.limitHistoryItemJson
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.HttpRequestSecurity

/** Read-only ColorStrike ports. All filtering and serialization follow the existing access decision. */
internal fun Server.registerFilteredHistoryTools(
    api: MontoyaApi,
    config: McpConfig,
    includeLegacyHttpSearch: Boolean = true,
    readOnlyMode: Boolean = false
) {
    fun requireAccess(type: DataAccessType) {
        check(runBlocking { DataAccessSecurity.checkDataAccessPermission(type, config) }) {
            "History access denied by Burp Suite"
        }
    }

    if (includeLegacyHttpSearch) mcpTool<GetProxyHttpHistoryRegex>(
        "Search captured HTTP URL/request/response/notes by regex, with optional colors, highlightedOnly, " +
            "includeStatic and inScopeOnly filters. Defaults to all colors including unmarked traffic. " +
            "Returns newest first with native Burp IDs. count=1..100. Captured credentials remain intact " +
            "by default; redactSecrets=true enables optional best-effort masking. Noise is compacted by default. " +
            "Each item is bounded valid JSON; get_request_by_index retrieves further detail. " +
            "Regex work is limited; narrow filters or simplify expressions when limits are exceeded. No traffic is sent.",
        behavior = READ_ONLY_TOOL
    ) {
        validateHistoryPage(count, offset, 100)
        val selectedColors = parseHistoryColors(colors)
        val matcher = HistoryRegex(regex)
        requireAccess(DataAccessType.HTTP_HISTORY)
        val items = filterHttpHistory(api,
            selectHistory(api.proxy().history(), selectedColors, highlightedOnly, includeStatic), inScopeOnly, matcher)
            .filter { snapshotMaxId == null || it.id() <= snapshotMaxId }
        capturedHistoryTextPage(items, count, offset) { filteredHttpItem(it, redactSecrets, compact) }
    }

    mcpTool<GetProxyWebsocketHistory>(
        "Read captured WebSocket history, newest first. Filters by Burp project scope by default " +
            "(inScopeOnly=false includes all). count=1..100, offset paginates filtered items. " +
            "Each bounded JSON item includes native message ID and WebSocket connection ID. " +
            "Captured credentials remain intact by default; redactSecrets=true enables optional masking. " +
            "Noise is compacted by default. No traffic is sent.",
        behavior = READ_ONLY_TOOL
    ) {
        validateHistoryPage(count, offset, 100)
        requireAccess(DataAccessType.WEBSOCKET_HISTORY)
        val items = selectWebSocketHistory(api, inScopeOnly, null)
        capturedHistoryTextPage(items, count, offset) { filteredWebSocketItem(it, redactSecrets, compact) }
    }

    mcpTool<GetProxyWebsocketHistoryRegex>(
        "Search captured WebSocket upgrade URL, payload and notes by regex, newest first. " +
            "Applies Burp project scope before reading payloads by default; inScopeOnly=false includes all. " +
            "count=1..100. Native message/connection IDs are preserved. Each item is bounded JSON, " +
            "with credentials intact by default and optional redactSecrets=true masking. Noise compaction is enabled by default. " +
            "Regex work is limited; an exceeded limit returns an error, never a partial match list. No traffic is sent.",
        behavior = READ_ONLY_TOOL
    ) {
        validateHistoryPage(count, offset, 100)
        val matcher = HistoryRegex(regex)
        requireAccess(DataAccessType.WEBSOCKET_HISTORY)
        val items = selectWebSocketHistory(api, inScopeOnly, matcher)
        capturedHistoryTextPage(items, count, offset) { filteredWebSocketItem(it, redactSecrets, compact) }
    }

    mcpTool<GetWebSocketMessageByIndex>(
        "Read a captured WebSocket message by its native Burp # ID. Includes messages outside project scope. " +
            "contentOffset and maxMessageChars=256..50000 provide bounded access to the complete payload and notes. " +
            "Original captured content, including credentials and encoded data, is returned by default. " +
            "Follow nextOffset for additional chunks; redactSecrets and compact are explicit opt-ins. No traffic is sent.",
        behavior = READ_ONLY_TOOL
    ) {
        require(index >= 0) { "index must be a non-negative native Burp ID" }
        validateMessageWindow(contentOffset, maxMessageChars)
        requireAccess(DataAccessType.WEBSOCKET_HISTORY)
        val item = api.proxy().webSocketHistory().firstOrNull { it.id() == index }
            ?: error("No WebSocket history message with Burp ID $index; it may have been removed")
        buildJsonObject {
            put("id", item.id())
            put("webSocketId", item.webSocketId())
            put("endpoint", item.upgradeRequest().url().substringBefore('?').substringBefore('#'))
            put("direction", item.direction().name)
            put("redactSecrets", redactSecrets)
            put("compact", compact)
            put("payload", messageWindow(item.payload()?.toString().orEmpty(), contentOffset,
                maxMessageChars, redactSecrets, compact))
            put("notes", messageWindow(item.annotations().notes().orEmpty(), contentOffset,
                maxMessageChars, redactSecrets, compact))
        }.toString()
    }

    if (!readOnlyMode) mcpTool<CreateRepeaterTabFromHistory>(
        "Open a captured request in Burp Repeater by its native Proxy history # ID. " +
            "The original Montoya request is reused, preserving its service and HTTP protocol. " +
            "This creates a local Repeater tab and does not send the request.",
        behavior = LOCAL_MUTATION_TOOL
    ) {
        require(index >= 0) { "index must be a non-negative native Burp ID" }
        requireAccess(DataAccessType.HTTP_HISTORY)
        val request = api.proxy().history().firstOrNull { it.id() == index }?.finalRequest()
            ?: error("No HTTP history item with Burp ID $index; it may have been removed")
        if (tabName == null) api.repeater().sendToRepeater(request)
        else api.repeater().sendToRepeater(request, tabName)
        TrafficStore.registerRepeaterTab(request, tabName)
        "Opened Burp history item #$index in Repeater${tabName?.let { " as '$it'" }.orEmpty()}"
    }

    if (!readOnlyMode) mcpTool<SendHistoryItemToIntruder>(
        "Open a captured request in Burp Intruder by its native Proxy history # ID. " +
            "The original Montoya request is reused, preserving its service and HTTP protocol. " +
            "This creates a local Intruder tab and does not start an attack.",
        behavior = LOCAL_MUTATION_TOOL
    ) {
        require(index >= 0) { "index must be a non-negative native Burp ID" }
        requireAccess(DataAccessType.HTTP_HISTORY)
        val request = api.proxy().history().firstOrNull { it.id() == index }?.finalRequest()
            ?: error("No HTTP history item with Burp ID $index; it may have been removed")
        if (tabName == null) api.intruder().sendToIntruder(request)
        else api.intruder().sendToIntruder(request, tabName)
        "Opened Burp history item #$index in Intruder${tabName?.let { " as '$it'" }.orEmpty()}; attack not started"
    }

    if (!readOnlyMode) mcpTool<ReplayHistoryItem>(
        "Replay one captured request by its native Burp # ID without changing it. Uses the original Montoya " +
            "request, preserving service and HTTP protocol, and passes through Burp MCP's existing per-target " +
            "request approval. No payload injection, batching or automatic retries. Returns a bounded response " +
            "with credentials intact by default; redactSecrets=true is explicit opt-in.",
        behavior = BURP_GATED_TOOL
    ) {
        require(index >= 0) { "index must be a non-negative native Burp ID" }
        validateMessageWindow(0, maxMessageChars)
        requireAccess(DataAccessType.HTTP_HISTORY)
        val request = api.proxy().history().firstOrNull { it.id() == index }?.finalRequest()
            ?: error("No HTTP history item with Burp ID $index; it may have been removed")
        val service = request.httpService()
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(
                service.host(), service.port(), config, request.toString(), api
            )
        }
        if (!allowed) return@mcpTool "Send HTTP request denied by Burp Suite"
        val result = OutboundRequestGate.withPermit { api.http().sendRequest(request) }
        val response = result.response()
        buildJsonObject {
            put("sourceId", index)
            put("statusCode", response?.statusCode()?.toInt()?.let(::JsonPrimitive) ?: JsonNull)
            put("endpoint", request.url().substringBefore('?').substringBefore('#'))
            put("redactSecrets", redactSecrets)
            put("compact", compact)
            put("response", messageWindow(response?.toString().orEmpty(), 0,
                maxMessageChars, redactSecrets, compact))
            put("responseStartMillis", result.timingData().orElse(null)
                ?.timeBetweenRequestSentAndStartOfResponse()?.toMillis()?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
    }
}

internal fun selectWebSocketHistory(
    api: MontoyaApi, inScopeOnly: Boolean, regex: HistoryRegex?
): List<ProxyWebSocketMessage> = api.proxy().webSocketHistory().asSequence()
    .filter { !inScopeOnly || api.scope().isInScope(it.upgradeRequest().url()) }
    .filter { item ->
        regex == null || regex.contains(item.upgradeRequest().url()) ||
            regex.contains(item.payload()?.toString().orEmpty()) || regex.contains(item.annotations().notes().orEmpty())
    }.sortedByDescending { it.id() }.toList()

internal fun <T> capturedHistoryTextPage(items: List<T>, count: Int, offset: Int, encode: (T) -> String): String =
    if (offset >= items.size) "Reached end of items"
    else items.asSequence().drop(offset).take(count).map(encode).joinToString("\n\n")

internal fun filteredHttpItem(item: ProxyHttpRequestResponse, redact: Boolean, compact: Boolean): String =
    limitHistoryItemJson(buildJsonObject {
        put("id", item.id())
        put("summary", historySummary(item))
        put("request", sanitizeHistoryText(item.finalRequest().toString(), redact, compact))
        put("response", item.response()?.let { JsonPrimitive(sanitizeHistoryText(it.toString(), redact, compact)) } ?: JsonNull)
        put("notes", sanitizeHistoryText(item.annotations().notes().orEmpty(), redact, compact))
        put("redactSecrets", redact)
        put("compact", compact)
    }.toString())

internal fun filteredWebSocketItem(item: ProxyWebSocketMessage, redact: Boolean, compact: Boolean): String =
    limitHistoryItemJson(buildJsonObject {
        put("id", item.id())
        put("webSocketId", item.webSocketId())
        put("endpoint", item.upgradeRequest().url().substringBefore('?').substringBefore('#'))
        put("direction", item.direction().name)
        put("payload", item.payload()?.let { JsonPrimitive(sanitizeHistoryText(it.toString(), redact, compact)) } ?: JsonNull)
        put("notes", sanitizeHistoryText(item.annotations().notes().orEmpty(), redact, compact))
        put("redactSecrets", redact)
        put("compact", compact)
    }.toString())

@Serializable
data class GetProxyHttpHistoryRegex(
    val regex: String,
    override val count: Int = 20,
    override val offset: Int = 0,
    val colors: List<String> = emptyList(),
    val highlightedOnly: Boolean = false,
    val includeStatic: Boolean = true,
    val inScopeOnly: Boolean = false,
    val redactSecrets: Boolean = false,
    val compact: Boolean = true,
    val snapshotMaxId: Int? = null
) : Paginated

@Serializable
data class GetProxyWebsocketHistory(
    override val count: Int = 20,
    override val offset: Int = 0,
    val inScopeOnly: Boolean = true,
    val redactSecrets: Boolean = false,
    val compact: Boolean = true
) : Paginated

@Serializable
data class GetProxyWebsocketHistoryRegex(
    val regex: String,
    override val count: Int = 20,
    override val offset: Int = 0,
    val inScopeOnly: Boolean = true,
    val redactSecrets: Boolean = false,
    val compact: Boolean = true
) : Paginated

@Serializable
data class GetWebSocketMessageByIndex(
    val index: Int,
    val contentOffset: Int = 0,
    val maxMessageChars: Int = 10000,
    val redactSecrets: Boolean = false,
    val compact: Boolean = false
)

@Serializable
data class CreateRepeaterTabFromHistory(val index: Int, val tabName: String? = null)

@Serializable
data class SendHistoryItemToIntruder(val index: Int, val tabName: String? = null)

@Serializable
data class ReplayHistoryItem(
    val index: Int,
    val maxMessageChars: Int = 10000,
    val redactSecrets: Boolean = false,
    val compact: Boolean = false
)
