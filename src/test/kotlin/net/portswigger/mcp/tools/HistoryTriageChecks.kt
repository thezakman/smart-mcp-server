package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Annotations
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.http.handler.TimingData
import burp.api.montoya.http.message.params.ParsedHttpParameter
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.asInputSchema
import net.portswigger.mcp.security.DataAccessApprovalHandler
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import java.lang.reflect.Proxy
import java.time.Duration

/** Framework-independent checks also runnable when the Gradle distribution is unavailable. */
object HistoryTriageChecks {
    @JvmStatic
    fun main(args: Array<String>) = runAll()

    fun runAll() {
        var count = 0
        fun scenario(name: String, block: () -> Unit) {
            block()
            count++
            println("PASS $name")
        }
        scenario("color normalization, GRAY, NONE and invalid color rejection") {
            check(parseHistoryColors(listOf(" red ", "GRAY", "none")) ==
                setOf(HighlightColor.RED, HighlightColor.GRAY, HighlightColor.NONE))
            check(runCatching { parseHistoryColors(listOf("unknown")) }.exceptionOrNull() is IllegalArgumentException)
        }
        scenario("static URL matching ignores query values and case") {
            check(isStaticHistoryUrl("https://example.invalid/a.PNG?v=1"))
            check(!isStaticHistoryUrl("https://example.invalid/api?file=a.png"))
        }
        scenario("pagination rejects negative and excessive inputs") {
            listOf(-1 to 0, 0 to 0, 21 to 0, 1 to -1).forEach { (n, offset) ->
                check(runCatching { validateHistoryPage(n, offset, 20) }.isFailure)
            }
        }
        scenario("header masking removes complete cookies, auth, API keys and folded values") {
            val raw = "GET / HTTP/1.1\r\nAuthorization: Basic abc123\r\n continued-secret\r\n" +
                "Cookie: session=secret-cookie\r\nSet-Cookie: a=secret-set\r\nX-Api-Key: secret-key\r\n\r\n{}"
            val text = sanitizeHistoryText(raw, true, false)
            listOf("abc123", "continued-secret", "secret-cookie", "secret-set", "secret-key").forEach {
                check(it !in text)
            }
        }
        scenario("credential fields and JWTs are masked before truncation") {
            val text = sanitizeHistoryText("""{"token":"json-secret","password":"a\"b","ok":true}
password=form-secret&other=value
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature Bearer opaque-secret""", true, false)
            check("json-secret" !in text && "form-secret" !in text && "opaque-secret" !in text)
            check("eyJ" !in text && "other=value" in text && "\"ok\":true" in text)
        }
        scenario("compaction is optional and leaves original evidence unchanged") {
            val raw = "<svg><path d=\"x\"/></svg>\n__VIEWSTATE=opaque-state&ok=1\n" + "A".repeat(300)
            val compact = sanitizeHistoryText(raw, false, true)
            check("SVG OMITTED" in compact && "VIEWSTATE OMITTED" in compact && "LONG ENCODED" in compact)
            check(sanitizeHistoryText(raw, false, false) == raw)
        }
        scenario("bounded JSON roundtrip and Unicode-safe chunk reconstruction") {
            val raw = "x".repeat(255) + "😀\"\\\n".repeat(200)
            var offset = 0
            val reconstructed = StringBuilder()
            do {
                val chunk = Json.parseToJsonElement(messageWindow(raw, offset, 256, false, false).toString()).jsonObject
                val text = chunk.getValue("text").jsonPrimitive.content
                check(text.length <= 256 && text.lastOrNull()?.isHighSurrogate() != true)
                reconstructed.append(text)
                val next = chunk["nextOffset"]?.jsonPrimitive?.intOrNull
                offset = next ?: -1
            } while (offset >= 0)
            check(reconstructed.toString() == raw)
            check(messageWindow(raw, Int.MAX_VALUE, 256, false, false)["nextOffset"] == JsonNull)
        }
        scenario("schemas expose actual defaults as optional") {
            check(GetProxyHttpHistorySummary::class.asInputSchema().required.orEmpty().isEmpty())
            check(GetRequestsByColor::class.asInputSchema().required == listOf("colors"))
            check(GetRequestByIndex::class.asInputSchema().required == listOf("index"))
        }

        var bodyReads = 0
        val items = listOf(
            fixture(7, HighlightColor.RED, "https://example.invalid/api?q=private-query") { bodyReads++ },
            fixture(91, HighlightColor.GRAY, "https://example.invalid/gray") { bodyReads++ },
            fixture(105, HighlightColor.NONE, "https://example.invalid/uncolored") { bodyReads++ },
            fixture(300, HighlightColor.RED, "https://example.invalid/logo.PNG") { bodyReads++ }
        )
        scenario("selection uses persistent IDs, includes GRAY and optionally static items") {
            check(selectHistory(items, emptySet(), true, false).map { it.id() } == listOf(91, 7))
            check(selectHistory(items, setOf(HighlightColor.RED), false, true).map { it.id() } == listOf(300, 7))
        }
        scenario("summary excludes bodies and query values with nullable timing") {
            val summary = historySummary(items.first())
            check(summary["id"]?.jsonPrimitive?.int == 7)
            check(summary["responseStartMillis"] == JsonNull)
            check("private-query" !in summary.toString() && "secret-body" !in summary.toString())
            check(bodyReads == 0)
        }
        scenario("missing responses and measured response-start timing stay explicit") {
            val pending = fixture(999, HighlightColor.BLUE, "https://example.invalid/pending", false) {}
            check(historySummary(pending)["statusCode"] == JsonNull)
            check(historyDetails(pending, 0, 256, true, false)["response"] == JsonNull)
            val timed = fixture(1000, HighlightColor.BLUE, "https://example.invalid/timed", true, 57) {}
            check(historySummary(timed)["responseStartMillis"]!!.jsonPrimitive.long == 57L)
        }
        scenario("slice-before-serialization and offset overflow") {
            val page = historyPage(items, 1, 1) { historyDetails(it, 0, 256, true, false) }
            check(bodyReads == 2) // Exactly one request and one response.
            check(page["nextOffset"]?.jsonPrimitive?.int == 2)
            check(page["items"]?.jsonArray?.single()?.jsonObject?.get("summary")?.jsonObject?.get("id")?.jsonPrimitive?.int == 91)
            check(historyPage(items, 1, Int.MAX_VALUE) { error("Must not serialize") }["items"] == JsonArray(emptyList()))
        }

        var historyReads = 0
        val proxy = fake<burp.api.montoya.proxy.Proxy> { method, _ ->
            when (method) { "history" -> { historyReads++; items }; else -> error(method) }
        }
        val api = fake<MontoyaApi> { method, _ -> when (method) { "proxy" -> proxy; else -> error(method) } }
        val storageValues = mutableMapOf<String, Any?>()
        val storage = fake<PersistedObject> { method, args ->
            when {
                method.startsWith("get") -> storageValues[args[0]]
                method.startsWith("set") -> { storageValues[args[0] as String] = args[1]; null }
                else -> error(method)
            }
        }
        val config = McpConfig(storage, fake<Logging> { _, _ -> null })
        config.requireDataAccessApproval = false
        val server = Server(Implementation("history-checks", "1"), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools())))
        server.registerHistoryTriageTools(api, config)
        val connection = fake<ClientConnection> { method, _ -> error("Unexpected client call $method") }
        fun call(name: String, arguments: String = "{}"): CallToolResult = runBlocking {
            server.tools.getValue(name).handler(connection, CallToolRequest(CallToolRequestParams(
                name = name, arguments = Json.parseToJsonElement(arguments).jsonObject
            )))
        }
        fun body(result: CallToolResult) = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
        try {
            scenario("MCP summary handler accepts omitted defaults") {
                val result = call("get_proxy_http_history_summary")
                check(result.isError == false)
                check(body(result)["total"]?.jsonPrimitive?.int == 2)
            }
            scenario("MCP color detail handler returns only requested page") {
                val result = call("get_requests_by_color", """{"colors":["red"],"includeStatic":true,"count":1,"offset":1}""")
                check(result.isError == false)
                val page = body(result)
                check(page["returned"]?.jsonPrimitive?.int == 1 && page["nextOffset"] == JsonNull)
                check(page["items"]!!.jsonArray.single().jsonObject["summary"]!!.jsonObject["id"]!!.jsonPrimitive.int == 7)
            }
            scenario("MCP ID lookup finds uncolored and static items without index renumbering") {
                listOf(105, 300).forEach { id ->
                    val result = call("get_request_by_index", """{"index":$id}""")
                    check(result.isError == false)
                    check(body(result)["summary"]!!.jsonObject["id"]!!.jsonPrimitive.int == id)
                }
                check(call("get_request_by_index", """{"index":0}""").isError == true)
            }
            scenario("MCP rejects invalid inputs before reading history") {
                val before = historyReads
                check(call("get_requests_by_color", """{"colors":["invalid"]}""").isError == true)
                check(call("get_requests_by_color", """{"colors":[]}""").isError == true)
                check(call("get_proxy_http_history_summary", """{"count":-1}""").isError == true)
                check(call("get_request_by_index", """{"index":7,"maxMessageChars":999999}""").isError == true)
                check(before == historyReads)
            }
            scenario("all three MCP tools honor existing data-access denial") {
                config.requireDataAccessApproval = true
                val previousHandler = DataAccessSecurity.approvalHandler
                DataAccessSecurity.approvalHandler = object : DataAccessApprovalHandler {
                    override suspend fun requestDataAccess(accessType: DataAccessType, config: McpConfig) = false
                }
                try {
                    val before = historyReads
                    check(call("get_proxy_http_history_summary").isError == true)
                    check(call("get_requests_by_color", """{"colors":["red"]}""").isError == true)
                    check(call("get_request_by_index", """{"index":7}""").isError == true)
                    check(before == historyReads)
                } finally { DataAccessSecurity.approvalHandler = previousHandler }
            }
        } finally {
            runBlocking { server.close() }
            config.cleanup()
        }
        println("History triage: $count scenarios passed")
    }

    private fun fixture(
        id: Int, color: HighlightColor, url: String, responsePresent: Boolean = true,
        latencyMillis: Long? = null, onRead: () -> Unit
    ): ProxyHttpRequestResponse {
        val annotations = fake<Annotations> { method, _ -> when (method) {
            "highlightColor" -> color; "notes" -> "note"; else -> error(method)
        } }
        val parameter = fake<ParsedHttpParameter> { method, _ -> when (method) {
            "name" -> "q"; else -> error("Parameter values must not be read: $method")
        } }
        val request = fake<HttpRequest> { method, _ -> when (method) {
            "url" -> url; "method" -> "GET"; "httpVersion" -> "HTTP/2"; "parameters" -> listOf(parameter)
            "toString" -> { onRead(); "GET / HTTP/2\r\nAuthorization: Bearer secret-body\r\n\r\n" }
            else -> error(method)
        } }
        val response = fake<HttpResponse> { method, _ -> when (method) {
            "statusCode" -> 200.toShort(); "toString" -> { onRead(); "HTTP/2 200 OK\r\n\r\nhello" }
            else -> error(method)
        } }
        val timing = latencyMillis?.let { milliseconds -> fake<TimingData> { method, _ -> when (method) {
            "timeBetweenRequestSentAndStartOfResponse" -> Duration.ofMillis(milliseconds)
            else -> error(method)
        } } }
        return fake { method, _ -> when (method) {
            "id" -> id; "annotations" -> annotations; "finalRequest", "request" -> request
            "response" -> if (responsePresent) response else null; "timingData" -> timing; else -> error(method)
        } }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> fake(crossinline handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as T
}
