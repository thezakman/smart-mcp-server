package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.params.HttpParameter
import burp.api.montoya.http.message.params.HttpParameterType
import burp.api.montoya.http.message.requests.HttpRequest
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import net.portswigger.mcp.RuntimeDiagnostics
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import java.security.MessageDigest
import java.util.Locale

internal fun Server.registerAdvancedTools(api: MontoyaApi, config: McpConfig, readOnlyMode: Boolean = false) {
    TrafficStore.ensureRegistered(api)

    fun requireAccess(type: DataAccessType) {
        check(runBlocking { DataAccessSecurity.checkDataAccessPermission(type, config) }) {
            "${type.name.lowercase().replace('_', ' ')} access denied by Burp Suite"
        }
    }

    mcpTool<ListSiteMap>(
        "Compact Site Map index with a stable content-derived key, method, host, path, status, MIME type and sizes. " +
            "Filters are applied before pagination. No request or response body is returned and no traffic is sent. " +
            "Use get_site_map_items_by_key for selected full exchanges.",
        behavior = READ_ONLY_TOOL
    ) {
        validatePage(count, offset, 200)
        requireAccess(DataAccessType.HTTP_HISTORY)
        val entries = filteredSiteMap(api, this)
        jsonPage(entries, count, offset) { siteMapSummary(it) }
    }

    mcpTool<GetSiteMapItemsByKey>(
        "Fetch selected Site Map request/response pairs using keys returned by list_site_map. " +
            "Messages are bounded and credentials remain unchanged. No traffic is sent.",
        behavior = READ_ONLY_TOOL
    ) {
        require(keys.isNotEmpty()) { "Provide at least one Site Map key" }
        require(keys.size <= 20) { "At most 20 Site Map keys may be requested" }
        validateMessageWindow(contentOffset, maxMessageChars)
        requireAccess(DataAccessType.HTTP_HISTORY)
        val wanted = keys.toSet()
        val matches = api.siteMap().requestResponses().filter { siteMapKey(it) in wanted }
        if (matches.isEmpty()) return@mcpTool "No Site Map items match the supplied keys"
        buildJsonArray {
            matches.forEach { item ->
                add(buildJsonObject {
                    put("key", siteMapKey(item))
                    put("summary", siteMapSummary(item))
                    put("request", messageWindow(item.request()?.toString().orEmpty(), contentOffset,
                        maxMessageChars, redact = false, compact = false))
                    put("response", messageWindow(item.response()?.toString().orEmpty(), contentOffset,
                        maxMessageChars, redact = false, compact = false))
                    put("notes", item.annotations()?.notes().orEmpty())
                })
            }
        }.toString()
    }

    mcpTool<ListOrganizerItems>(
        "Compact Organizer index with native item ID, status, annotation, method, endpoint, response status, MIME type " +
            "and message sizes. No bodies. Use get_organizer_items_by_id for detail.",
        behavior = READ_ONLY_TOOL
    ) {
        validatePage(count, offset, 200)
        requireAccess(DataAccessType.ORGANIZER)
        var items = api.organizer().items().asSequence()
        if (!hostFilter.isNullOrBlank()) items = items.filter {
            safeValue { it.httpService()?.host()?.contains(hostFilter, ignoreCase = true) } == true
        }
        val list = items.toList().let { if (newestFirst) it.asReversed() else it }
        jsonPage(list, count, offset) { organizerSummary(it) }
    }

    mcpTool<GetOrganizerItemsById>(
        "Fetch full Organizer entries by native IDs. Request, response and notes are returned unchanged in bounded chunks.",
        behavior = READ_ONLY_TOOL
    ) {
        require(ids.isNotEmpty()) { "Provide at least one Organizer ID" }
        require(ids.size <= 20) { "At most 20 Organizer IDs may be requested" }
        validateMessageWindow(contentOffset, maxMessageChars)
        requireAccess(DataAccessType.ORGANIZER)
        val wanted = ids.toSet()
        val matches = api.organizer().items().filter { it.id() in wanted }
        if (matches.isEmpty()) return@mcpTool "No Organizer items match IDs: $ids"
        buildJsonArray {
            matches.forEach { item ->
                add(buildJsonObject {
                    put("id", item.id())
                    put("summary", organizerSummary(item))
                    put("request", messageWindow(item.request()?.toString().orEmpty(), contentOffset,
                        maxMessageChars, redact = false, compact = false))
                    put("response", messageWindow(item.response()?.toString().orEmpty(), contentOffset,
                        maxMessageChars, redact = false, compact = false))
                })
            }
        }.toString()
    }

    if (!readOnlyMode) mcpTool<SetOrganizerItemNotes>("Set notes on one Organizer item by native ID.", LOCAL_MUTATION_TOOL) {
        require(notes.length <= 100_000) { "notes must not exceed 100000 characters" }
        requireAccess(DataAccessType.ORGANIZER)
        val item = api.organizer().items().firstOrNull { it.id() == id }
            ?: error("No Organizer item with ID $id")
        item.annotations().setNotes(notes)
        "Updated notes for Organizer item $id"
    }

    if (!readOnlyMode) mcpTool<SetOrganizerItemHighlight>(
        "Set one Organizer item's highlight: RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PINK, MAGENTA, GRAY or NONE.",
        behavior = LOCAL_MUTATION_TOOL
    ) {
        requireAccess(DataAccessType.ORGANIZER)
        val color = try {
            HighlightColor.valueOf(color.trim().uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            error("Invalid highlight color: $color")
        }
        val item = api.organizer().items().firstOrNull { it.id() == id }
            ?: error("No Organizer item with ID $id")
        item.annotations().setHighlightColor(color)
        "Updated highlight for Organizer item $id to ${color.name}"
    }

    if (!readOnlyMode) mcpTool<SaveExchangeToOrganizer>(
        "Save an already captured Proxy, Repeater or Intruder exchange to Organizer without sending target traffic. " +
            "source is PROXY or CAPTURED. Optional notes are attached to the saved copy.",
        behavior = LOCAL_MUTATION_TOOL
    ) {
        requireAccess(DataAccessType.ORGANIZER)
        val pair = resolveExchange(api, source, id, config, requireDataAccess = true)
        val request = HttpRequest.httpRequest(pair.service, pair.request)
        val requestResponse = HttpRequestResponse.httpRequestResponse(
            request,
            burp.api.montoya.http.message.responses.HttpResponse.httpResponse(pair.response)
        )
        val annotated = notes?.let {
            require(it.length <= 100_000) { "notes must not exceed 100000 characters" }
            requestResponse.withAnnotations(burp.api.montoya.core.Annotations.annotations(it))
        } ?: requestResponse
        api.organizer().sendToOrganizer(annotated)
        "Saved ${source.uppercase(Locale.ROOT)} exchange $id to Organizer without sending traffic"
    }

    mcpTool<GetRepeaterTraffic>(
        "Compact index of Repeater exchanges observed after this extension was loaded. No bodies; no traffic is sent.",
        behavior = READ_ONLY_TOOL
    ) {
        capturedTrafficPage(ToolType.REPEATER, newestFirst, count, offset, config)
    }

    mcpTool<GetIntruderTraffic>(
        "Compact index of Intruder exchanges observed after this extension was loaded. No bodies; no traffic is sent.",
        behavior = READ_ONLY_TOOL
    ) {
        capturedTrafficPage(ToolType.INTRUDER, newestFirst, count, offset, config)
    }

    mcpTool<GetCapturedExchangeById>(
        "Fetch one Repeater or Intruder exchange by captured message ID. Content is unchanged and chunked.",
        behavior = READ_ONLY_TOOL
    ) {
        validateMessageWindow(contentOffset, maxMessageChars)
        requireAccess(DataAccessType.HTTP_HISTORY)
        val item = TrafficStore.byId(messageId) ?: error("No captured exchange with message ID $messageId")
        buildJsonObject {
            put("summary", Json.encodeToJsonElement(item.summary()))
            put("request", messageWindow(item.request, contentOffset, maxMessageChars, false, false))
            put("response", messageWindow(item.response, contentOffset, maxMessageChars, false, false))
        }.toString()
    }

    mcpTool<CompareHttpExchanges>(
        "Compare two already captured Proxy/Repeater/Intruder exchanges without sending traffic. " +
            "Returns status, sizes, hashes, JSON-key differences and differing response headers.",
        behavior = READ_ONLY_TOOL
    ) {
        val left = resolveExchange(api, leftSource, leftId, config, requireDataAccess = true)
        val right = resolveExchange(api, rightSource, rightId, config, requireDataAccess = true)
        compareExchanges(left, right).toString()
    }

    mcpTool<PreviewRequestMutation>(
        "Preview exactly one explicit mutation to a captured Proxy request without sending it. " +
            "location: METHOD, PATH, HEADER, BODY, QUERY_PARAMETER, BODY_PARAMETER, COOKIE or JSON_PARAMETER.",
        behavior = READ_ONLY_TOOL
    ) {
        validateMessageWindow(0, maxMessageChars)
        requireAccess(DataAccessType.HTTP_HISTORY)
        val original = proxyRequestById(api, sourceId)
        val mutated = mutateRequest(original, location, name, value)
        mutationPreview(sourceId, original, mutated, location, name, maxMessageChars).toString()
    }

    if (!readOnlyMode) mcpTool<SendMutatedRequest>(
        "Send exactly one explicit mutation of a captured Proxy request. Always preview first. " +
            "Pass the preview's mutatedSha256 as expectedRequestSha256. This tool performs one request only, " +
            "with no batching or retries, and uses Burp's per-target approval.",
        behavior = BURP_GATED_TOOL
    ) {
        validateMessageWindow(0, maxMessageChars)
        requireAccess(DataAccessType.HTTP_HISTORY)
        val original = proxyRequestById(api, sourceId)
        val mutated = mutateRequest(original, location, name, value)
        val actualHash = sha256(mutated.toString())
        require(actualHash.equals(expectedRequestSha256, ignoreCase = true)) {
            "Mutation does not match the reviewed preview; run preview_request_mutation again and pass its mutatedSha256"
        }
        val service = mutated.httpService()
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(service.host(), service.port(), config, mutated.toString(), api)
        }
        if (!allowed) return@mcpTool "Send HTTP request denied by Burp Suite"
        val result = OutboundRequestGate.withPermit { api.http().sendRequest(mutated) }
        val response = result.response()
        buildJsonObject {
            put("sourceId", sourceId)
            put("sentRequestSha256", actualHash)
            put("location", location.uppercase(Locale.ROOT))
            name?.let { put("name", it) }
            put("statusCode", response?.statusCode()?.toInt()?.let(::JsonPrimitive) ?: JsonNull)
            put("responseStartMillis", result.timingData().orElse(null)
                ?.timeBetweenRequestSentAndStartOfResponse()?.toMillis()?.let(::JsonPrimitive) ?: JsonNull)
            put("response", messageWindow(response?.toString().orEmpty(), 0, maxMessageChars, false, false))
        }.toString()
    }

    mcpTool(
        "get_mcp_diagnostics",
        "Report Burp, JVM, MCP endpoint, embedded proxy, capture-buffer and last server-state diagnostics.",
        READ_ONLY_TOOL
    ) {
        buildJsonObject {
            put("server", RuntimeDiagnostics.snapshot(config))
            put("burpVersion", safeValue { api.burpSuite().version().toString() } ?: "unknown")
            put("javaVersion", System.getProperty("java.version"))
            put("javaVendor", System.getProperty("java.vendor"))
            put("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            put("embeddedProxyPresent", AdvancedToolsMarker::class.java.classLoader
                .getResource("mcp-proxy-all.jar") != null)
            put("captureCounts", Json.encodeToJsonElement(TrafficStore.counts()))
        }.toString()
    }

    mcpTool<GetMcpActionLog>(
        "Read the bounded local MCP action audit log. It records tool name, timestamp, duration and error only; " +
            "request arguments and response bodies are never copied into the log.",
        behavior = READ_ONLY_TOOL
    ) {
        validatePage(count, offset, 200)
        jsonPage(ToolAuditLog.snapshot(newestFirst), count, offset) { Json.encodeToJsonElement(it) }
    }
}

private object AdvancedToolsMarker

private fun capturedTrafficPage(
    tool: ToolType, newestFirst: Boolean, count: Int, offset: Int, config: McpConfig
): String {
    validatePage(count, offset, 200)
    check(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.HTTP_HISTORY, config) }) {
        "Captured traffic access denied by Burp Suite"
    }
    return jsonPage(TrafficStore.snapshot(tool, newestFirst), count, offset) {
        Json.encodeToJsonElement(it.summary())
    }
}

private fun filteredSiteMap(api: MontoyaApi, args: ListSiteMap): List<HttpRequestResponse> =
    api.siteMap().requestResponses().asSequence().filter { item ->
        val request = item.request() ?: return@filter false
        val url = safeValue { request.url() } ?: return@filter false
        (args.prefix.isNullOrBlank() || url.startsWith(args.prefix, ignoreCase = true)) &&
            (!args.inScopeOnly || safeValue { request.isInScope() } == true) &&
            (args.host.isNullOrBlank() || safeValue { request.httpService().host() }
                ?.contains(args.host, ignoreCase = true) == true) &&
            (args.method.isNullOrBlank() || safeValue { request.method() }
                ?.equals(args.method, ignoreCase = true) == true) &&
            (args.statusCode == null || safeValue { item.response()?.statusCode()?.toInt() } == args.statusCode) &&
            (args.mimeType.isNullOrBlank() || safeValue { item.response()?.mimeType()?.name }
                ?.contains(args.mimeType, ignoreCase = true) == true)
    }.toList()

private fun siteMapSummary(item: HttpRequestResponse): JsonObject {
    val request = item.request()
    val response = item.response()
    return buildJsonObject {
        put("key", siteMapKey(item))
        put("method", safeValue { request?.method() }?.let(::JsonPrimitive) ?: JsonNull)
        put("host", safeValue { request?.httpService()?.host() }?.let(::JsonPrimitive) ?: JsonNull)
        put("port", safeValue { request?.httpService()?.port() }?.let(::JsonPrimitive) ?: JsonNull)
        put("secure", safeValue { request?.httpService()?.secure() }?.let(::JsonPrimitive) ?: JsonNull)
        put("path", safeValue { request?.path() }?.let(::JsonPrimitive) ?: JsonNull)
        put("statusCode", safeValue { response?.statusCode()?.toInt() }?.let(::JsonPrimitive) ?: JsonNull)
        put("mimeType", safeValue { response?.mimeType()?.name }?.let(::JsonPrimitive) ?: JsonNull)
        put("requestBytes", safeValue { request?.toByteArray()?.length() } ?: 0)
        put("responseBytes", safeValue { response?.toByteArray()?.length() } ?: 0)
    }
}

private fun siteMapKey(item: HttpRequestResponse): String {
    val request = item.request()
    return sha256(buildString {
        append(safeValue { request?.httpService()?.host() }.orEmpty()).append(':')
        append(safeValue { request?.httpService()?.port() } ?: -1).append('|')
        append(safeValue { request?.method() }.orEmpty()).append('|')
        append(safeValue { request?.url() }.orEmpty()).append('|')
        append(request?.toString().orEmpty())
    })
}

private fun organizerSummary(item: burp.api.montoya.organizer.OrganizerItem): JsonObject = buildJsonObject {
    val request = item.request()
    val response = item.response()
    put("id", item.id())
    put("status", safeValue { item.status().displayName() } ?: "unknown")
    put("highlight", safeValue { item.annotations().highlightColor().name } ?: "NONE")
    put("notes", item.annotations().notes())
    put("method", safeValue { request?.method() }?.let(::JsonPrimitive) ?: JsonNull)
    put("host", safeValue { item.httpService()?.host() }?.let(::JsonPrimitive) ?: JsonNull)
    put("path", safeValue { request?.path() }?.let(::JsonPrimitive) ?: JsonNull)
    put("statusCode", safeValue { response?.statusCode()?.toInt() }?.let(::JsonPrimitive) ?: JsonNull)
    put("mimeType", safeValue { response?.mimeType()?.name }?.let(::JsonPrimitive) ?: JsonNull)
    put("requestBytes", safeValue { request?.toByteArray()?.length() } ?: 0)
    put("responseBytes", safeValue { response?.toByteArray()?.length() } ?: 0)
}

private data class ExchangeView(
    val source: String,
    val id: Int,
    val service: burp.api.montoya.http.HttpService,
    val request: String,
    val response: String
)

private fun resolveExchange(
    api: MontoyaApi, source: String, id: Int, config: McpConfig, requireDataAccess: Boolean
): ExchangeView {
    if (requireDataAccess) {
        check(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.HTTP_HISTORY, config) }) {
            "Captured traffic access denied by Burp Suite"
        }
    }
    return when (source.trim().uppercase(Locale.ROOT)) {
        "PROXY" -> {
            val item = api.proxy().history().firstOrNull { it.id() == id }
                ?: error("No Proxy history item with Burp ID $id")
            ExchangeView("PROXY", id, item.finalRequest().httpService(), item.finalRequest().toString(),
                item.response()?.toString().orEmpty())
        }
        "CAPTURED", "REPEATER", "INTRUDER" -> {
            val item = TrafficStore.byId(id) ?: error("No captured Repeater/Intruder exchange with message ID $id")
            val host = item.host ?: error("Captured exchange $id has no HTTP service host")
            val port = item.port ?: if (item.secure == true) 443 else 80
            val service = HttpService.httpService(host, port, item.secure == true)
            ExchangeView(item.tool, id, service, item.request, item.response)
        }
        else -> error("source must be PROXY or CAPTURED")
    }
}

private fun compareExchanges(left: ExchangeView, right: ExchangeView): JsonObject {
    val leftResponse = parseRawMessage(left.response)
    val rightResponse = parseRawMessage(right.response)
    val leftJsonKeys = jsonKeys(leftResponse.body)
    val rightJsonKeys = jsonKeys(rightResponse.body)
    val headerNames = (leftResponse.headers.keys + rightResponse.headers.keys).sorted()
    return buildJsonObject {
        put("left", exchangeMetrics(left, leftResponse))
        put("right", exchangeMetrics(right, rightResponse))
        put("sameResponse", left.response == right.response)
        put("statusChanged", leftResponse.startLine != rightResponse.startLine)
        put("responseLengthDelta", right.response.length - left.response.length)
        put("bodyLengthDelta", rightResponse.body.length - leftResponse.body.length)
        put("jsonKeysOnlyLeft", JsonArray((leftJsonKeys - rightJsonKeys).sorted().map(::JsonPrimitive)))
        put("jsonKeysOnlyRight", JsonArray((rightJsonKeys - leftJsonKeys).sorted().map(::JsonPrimitive)))
        put("differingHeaders", buildJsonArray {
            headerNames.asSequence().filter { leftResponse.headers[it] != rightResponse.headers[it] }.take(100)
                .forEach { name -> add(buildJsonObject {
                    put("name", name)
                    put("left", leftResponse.headers[name]?.take(1000)?.let(::JsonPrimitive) ?: JsonNull)
                    put("right", rightResponse.headers[name]?.take(1000)?.let(::JsonPrimitive) ?: JsonNull)
                }) }
        })
    }
}

private fun exchangeMetrics(exchange: ExchangeView, parsed: ParsedMessage): JsonObject = buildJsonObject {
    put("source", exchange.source)
    put("id", exchange.id)
    put("responseStartLine", parsed.startLine)
    put("responseCharacters", exchange.response.length)
    put("bodyCharacters", parsed.body.length)
    put("responseSha256", sha256(exchange.response))
    put("bodySha256", sha256(parsed.body))
}

private data class ParsedMessage(val startLine: String, val headers: Map<String, String>, val body: String)

private fun parseRawMessage(raw: String): ParsedMessage {
    val normalized = raw.replace("\r\n", "\n")
    val split = normalized.indexOf("\n\n")
    val head = if (split >= 0) normalized.substring(0, split) else normalized
    val body = if (split >= 0) normalized.substring(split + 2) else ""
    val lines = head.lines()
    val headers = linkedMapOf<String, String>()
    lines.drop(1).forEach { line ->
        val colon = line.indexOf(':')
        if (colon > 0) {
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            headers[name] = headers[name]?.let { "$it\n$value" } ?: value
        }
    }
    return ParsedMessage(lines.firstOrNull().orEmpty(), headers, body)
}

private fun jsonKeys(body: String): Set<String> = try {
    val result = linkedSetOf<String>()
    fun walk(element: JsonElement, path: String, depth: Int) {
        if (depth > 20 || result.size >= 1000) return
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                val next = if (path.isEmpty()) key else "$path.$key"
                result += next
                walk(value, next, depth + 1)
            }
            is JsonArray -> element.take(20).forEach { walk(it, "$path[]", depth + 1) }
            else -> Unit
        }
    }
    walk(Json.parseToJsonElement(body), "", 0)
    result
} catch (_: Exception) {
    emptySet()
}

private fun proxyRequestById(api: MontoyaApi, id: Int): HttpRequest {
    require(id >= 0) { "sourceId must be a non-negative native Burp ID" }
    return api.proxy().history().firstOrNull { it.id() == id }?.finalRequest()
        ?: error("No Proxy history item with Burp ID $id")
}

internal fun mutateRequest(
    request: HttpRequest, location: String, name: String?, value: String
): HttpRequest {
    require(value.length <= 1_000_000) { "Mutation value must not exceed 1000000 characters" }
    val normalized = location.trim().uppercase(Locale.ROOT)
    fun requiredName(): String = name?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("name is required for $normalized")
    return when (normalized) {
        "METHOD" -> request.withMethod(value.trim().also { require(it.matches(Regex("[A-Za-z]+"))) {
            "METHOD value must contain letters only"
        } })
        "PATH" -> request.withPath(value.also { require(it.startsWith('/')) { "PATH value must start with /" } })
        "HEADER" -> if (request.hasHeader(requiredName())) request.withUpdatedHeader(requiredName(), value)
            else request.withAddedHeader(requiredName(), value)
        "BODY" -> request.withBody(value)
        "QUERY_PARAMETER" -> request.withParameter(HttpParameter.parameter(requiredName(), value, HttpParameterType.URL))
        "BODY_PARAMETER" -> request.withParameter(HttpParameter.parameter(requiredName(), value, HttpParameterType.BODY))
        "COOKIE" -> request.withParameter(HttpParameter.parameter(requiredName(), value, HttpParameterType.COOKIE))
        "JSON_PARAMETER" -> request.withParameter(HttpParameter.parameter(requiredName(), value, HttpParameterType.JSON))
        else -> error("location must be METHOD, PATH, HEADER, BODY, QUERY_PARAMETER, BODY_PARAMETER, COOKIE or JSON_PARAMETER")
    }
}

private fun mutationPreview(
    sourceId: Int, original: HttpRequest, mutated: HttpRequest, location: String, name: String?, maxChars: Int
): JsonObject = buildJsonObject {
    put("sourceId", sourceId)
    put("location", location.uppercase(Locale.ROOT))
    name?.let { put("name", it) }
    put("originalSha256", sha256(original.toString()))
    put("mutatedSha256", sha256(mutated.toString()))
    put("changed", original.toString() != mutated.toString())
    put("target", "${mutated.httpService().host()}:${mutated.httpService().port()}")
    put("request", messageWindow(mutated.toString(), 0, maxChars, false, false))
}

private fun <T> jsonPage(items: List<T>, count: Int, offset: Int, encode: (T) -> JsonElement): String {
    val page = if (offset >= items.size) emptyList() else items.drop(offset).take(count)
    return buildJsonObject {
        put("total", items.size)
        put("offset", offset)
        put("returned", page.size)
        put("nextOffset", if (offset + page.size < items.size) JsonPrimitive(offset + page.size) else JsonNull)
        put("items", JsonArray(page.map(encode)))
    }.toString()
}

private fun validatePage(count: Int, offset: Int, maxCount: Int) {
    require(count in 1..maxCount) { "count must be between 1 and $maxCount" }
    require(offset >= 0) { "offset must be non-negative" }
}

private inline fun <T> safeValue(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Serializable
data class ListSiteMap(
    val prefix: String? = null,
    val host: String? = null,
    val method: String? = null,
    val statusCode: Int? = null,
    val mimeType: String? = null,
    val inScopeOnly: Boolean = false,
    val count: Int = 50,
    val offset: Int = 0
)

@Serializable
data class GetSiteMapItemsByKey(
    val keys: List<String>,
    val contentOffset: Int = 0,
    val maxMessageChars: Int = 10_000
)

@Serializable
data class ListOrganizerItems(
    val hostFilter: String? = null,
    val newestFirst: Boolean = true,
    val count: Int = 50,
    val offset: Int = 0
)

@Serializable
data class GetOrganizerItemsById(
    val ids: List<Int>,
    val contentOffset: Int = 0,
    val maxMessageChars: Int = 10_000
)

@Serializable data class SetOrganizerItemNotes(val id: Int, val notes: String)
@Serializable data class SetOrganizerItemHighlight(val id: Int, val color: String)
@Serializable data class SaveExchangeToOrganizer(
    val source: String,
    val id: Int,
    val notes: String? = null
)

@Serializable data class GetRepeaterTraffic(
    val newestFirst: Boolean = true,
    val count: Int = 50,
    val offset: Int = 0
)

@Serializable data class GetIntruderTraffic(
    val newestFirst: Boolean = true,
    val count: Int = 50,
    val offset: Int = 0
)

@Serializable data class GetCapturedExchangeById(
    val messageId: Int,
    val contentOffset: Int = 0,
    val maxMessageChars: Int = 10_000
)

@Serializable data class CompareHttpExchanges(
    val leftSource: String,
    val leftId: Int,
    val rightSource: String,
    val rightId: Int
)

@Serializable data class PreviewRequestMutation(
    val sourceId: Int,
    val location: String,
    val name: String? = null,
    val value: String,
    val maxMessageChars: Int = 20_000
)

@Serializable data class SendMutatedRequest(
    val sourceId: Int,
    val location: String,
    val name: String? = null,
    val value: String,
    val expectedRequestSha256: String,
    val maxMessageChars: Int = 20_000
)

@Serializable data class GetMcpActionLog(
    val newestFirst: Boolean = true,
    val count: Int = 50,
    val offset: Int = 0
)
