package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Annotations
import burp.api.montoya.core.HighlightColor
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.Http
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.proxy.ProxyWebSocketMessage
import burp.api.montoya.repeater.Repeater
import burp.api.montoya.intruder.Intruder
import burp.api.montoya.scope.Scope
import burp.api.montoya.websocket.Direction
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

object FilteredHistoryChecks {
    @JvmStatic
    fun main(args: Array<String>) = runAll()

    fun runAll() {
        var passed = 0
        fun scenario(name: String, block: () -> Unit) {
            block()
            passed++
            println("PASS $name")
        }
        scenario("regex supports flags and fails explicitly on malformed or oversized patterns") {
            check(HistoryRegex("(?i)hello").contains("HELLO"))
            check(runCatching { HistoryRegex("[") }.exceptionOrNull() is IllegalArgumentException)
            check(runCatching { HistoryRegex("x".repeat(1025)) }.isFailure)
            check(runCatching { HistoryRegex("x").contains("x".repeat(1_000_001)) }.isFailure)
        }
        scenario("regex character budget bounds backtracking and spans multiple fields") {
            val error = runCatching { HistoryRegex("(a+)+$", 1000).contains("a".repeat(100) + "!") }.exceptionOrNull()
            check(error is IllegalStateException && "budget exceeded" in error.message.orEmpty())
            val matcher = HistoryRegex("z", 10)
            check(!matcher.contains("aaaaaa"))
            check(runCatching { matcher.contains("aaaaaa") }.isFailure)
        }
        scenario("ported tool schemas keep defaults optional") {
            check(GetProxyHttpHistoryRegex::class.asInputSchema().required == listOf("regex"))
            check(GetProxyWebsocketHistory::class.asInputSchema().required.orEmpty().isEmpty())
            check(GetProxyWebsocketHistoryRegex::class.asInputSchema().required == listOf("regex"))
            check(GetWebSocketMessageByIndex::class.asInputSchema().required == listOf("index"))
            check(CreateRepeaterTabFromHistory::class.asInputSchema().required == listOf("index"))
            check(SendHistoryItemToIntruder::class.asInputSchema().required == listOf("index"))
            check(ReplayHistoryItem::class.asInputSchema().required == listOf("index"))
        }

        val bodyReads = mutableListOf<Int>()
        val http = listOf(
            httpFixture(7, HighlightColor.RED, "https://scope.invalid/api", "match-body") { bodyReads += 7 },
            httpFixture(91, HighlightColor.BLUE, "https://scope.invalid/blue", "match-body") { bodyReads += 91 },
            httpFixture(105, HighlightColor.RED, "https://outside.invalid/api", "match-body") { bodyReads += 105 },
            httpFixture(300, HighlightColor.RED, "https://scope.invalid/logo.png", "match-body") { bodyReads += 300 },
            httpFixture(400, HighlightColor.RED, "https://scope.invalid/notes", "other", "note-match") { bodyReads += 400 }
        )
        val wsReads = mutableListOf<Int>()
        val ws = listOf(
            wsFixture(8, "https://scope.invalid/ws?token=query-secret", """{"token":"ws-secret","kind":"notice"}""") { wsReads += 8 },
            wsFixture(90, "https://outside.invalid/ws", "notice") { wsReads += 90 },
            wsFixture(32, "https://scope.invalid/ws", "message \"" + "😀".repeat(4000), "note-match") { wsReads += 32 }
        )
        var proxyReads = 0
        var scopeReads = 0
        val repeaterCalls = mutableListOf<Array<out Any?>>()
        val intruderCalls = mutableListOf<Array<out Any?>>()
        val replayed = mutableListOf<HttpRequest>()
        val proxy = fake<burp.api.montoya.proxy.Proxy> { method, _ ->
            proxyReads++
            when (method) { "history" -> http; "webSocketHistory" -> ws; else -> error(method) }
        }
        val scope = fake<Scope> { method, args ->
            check(method == "isInScope")
            scopeReads++
            (args.single() as String).startsWith("https://scope.invalid/")
        }
        val repeater = fake<Repeater> { method, args ->
            check(method == "sendToRepeater")
            repeaterCalls += args
            null
        }
        val intruder = fake<Intruder> { method, args ->
            check(method == "sendToIntruder")
            intruderCalls += args
            null
        }
        val replayResponse = fake<HttpResponse> { method, _ -> when (method) {
            "statusCode" -> 201.toShort()
            "toString" -> "HTTP/2 201 Created\r\nSet-Cookie: session=replay-secret\r\n\r\nreplayed"
            else -> error(method)
        } }
        val replayResult = fake<HttpRequestResponse> { method, _ -> when (method) {
            "statusCode" -> 201.toShort(); "url" -> "https://scope.invalid/api?private=query"
            "response" -> replayResponse; "timingData" -> java.util.Optional.empty<Any>()
            else -> error(method)
        } }
        val httpClient = fake<Http> { method, args ->
            check(method == "sendRequest")
            replayed += args.single() as HttpRequest
            replayResult
        }
        val api = fake<MontoyaApi> { method, _ -> when (method) {
            "proxy" -> proxy; "scope" -> scope; "repeater" -> repeater; "intruder" -> intruder
            "http" -> httpClient
            else -> error("No target operations allowed: $method")
        } }
        val values = mutableMapOf<String, Any?>()
        val storage = fake<PersistedObject> { method, args -> when {
            method.startsWith("get") -> values[args[0]]
            method.startsWith("set") -> { values[args[0] as String] = args[1]; null }
            else -> error(method)
        } }
        val config = McpConfig(storage, fake<Logging> { _, _ -> null })
        config.requireDataAccessApproval = false
        config.requireHttpRequestApproval = false
        val server = Server(Implementation("filtered-history-checks", "1"), ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools())))
        server.registerHistoryTriageTools(api, config)
        server.registerFilteredHistoryTools(api, config)
        val connection = fake<ClientConnection> { method, _ -> error(method) }
        fun call(name: String, arguments: String = "{}"): CallToolResult = runBlocking {
            server.tools.getValue(name).handler(connection, CallToolRequest(CallToolRequestParams(
                name = name, arguments = Json.parseToJsonElement(arguments).jsonObject
            )))
        }
        fun text(result: CallToolResult): String {
            check(result.isError == false) { result.toString() }
            return (result.content.single() as TextContent).text
        }
        fun page(result: CallToolResult) = Json.parseToJsonElement(text(result)).jsonObject
        fun lines(result: CallToolResult) = text(result).split("\n\n").map { Json.parseToJsonElement(it).jsonObject }
        try {
            scenario("HTTP summary combines regex, colors and scope before reading bodies") {
                bodyReads.clear()
                val result = page(call("get_proxy_http_history_summary",
                    """{"colors":["RED"],"regex":"match-body","inScopeOnly":true}"""))
                check(result["items"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.int == 7)
                check(bodyReads.toSet() == setOf(7, 400))
                check("match-body" !in result.toString())
            }
            scenario("color detail preserves credentials by default and masking is explicit") {
                val result = text(call("get_requests_by_color",
                    """{"colors":["RED"],"regex":"private-header","inScopeOnly":true}"""))
                check("private-header" in result)
                val masked = text(call("get_requests_by_color",
                    """{"colors":["RED"],"regex":"private-header","inScopeOnly":true,"redactSecrets":true}"""))
                check("private-header" !in masked && "[REDACTED]" in masked)
            }
            scenario("HTTP regex port paginates matches with native IDs and preserves raw opt-out") {
                val filtered = lines(call("get_proxy_http_history_regex",
                    """{"regex":"match-body","colors":["red"],"inScopeOnly":true,"includeStatic":false,"count":1}"""))
                check(filtered.single()["id"]!!.jsonPrimitive.int == 7)
                check("private-header" in filtered.toString())
                val raw = text(call("get_proxy_http_history_regex",
                    """{"regex":"match-body","colors":["red"],"inScopeOnly":true,"includeStatic":false,"redactSecrets":false,"compact":false}"""))
                check("private-header" in raw)
            }
            scenario("HTTP regex searches annotations and handles an empty result") {
                check(lines(call("get_proxy_http_history_regex", """{"regex":"note-match"}"""))
                    .single()["id"]!!.jsonPrimitive.int == 400)
                check(text(call("get_proxy_http_history_regex", """{"regex":"absent-match"}""")) == "Reached end of items")
            }
            scenario("WebSocket default scope preserves IDs, credentials and bounded valid JSON") {
                wsReads.clear()
                val result = lines(call("get_proxy_websocket_history"))
                check(result.map { it["id"]!!.jsonPrimitive.int } == listOf(32, 8))
                check(result.all { it["webSocketId"]!!.jsonPrimitive.int == 42 })
                check(wsReads == listOf(32, 8))
                check("ws-secret" in result.toString() && "query-secret" !in result.toString())
                check(result.first()["payload"]!!.jsonPrimitive.content.endsWith("... (truncated)"))
                check(result.all { it.toString().length <= 5000 })
            }
            scenario("WebSocket pagination precedes payload serialization") {
                wsReads.clear()
                val result = lines(call("get_proxy_websocket_history", """{"count":1,"offset":1}"""))
                check(result.single()["id"]!!.jsonPrimitive.int == 8)
                check(wsReads == listOf(8))
            }
            scenario("WebSocket regex applies scope first and can search annotations") {
                wsReads.clear()
                check(lines(call("get_proxy_websocket_history_regex", """{"regex":"note-match"}"""))
                    .single()["id"]!!.jsonPrimitive.int == 32)
                check(90 !in wsReads)
                val secretMatch = text(call("get_proxy_websocket_history_regex", """{"regex":"ws-secret","redactSecrets":true}"""))
                check("ws-secret" !in secretMatch && "REDACTED" in secretMatch)
            }
            scenario("WebSocket scope opt-out and raw opt-out are explicit") {
                val result = lines(call("get_proxy_websocket_history", """{"inScopeOnly":false,"redactSecrets":false}"""))
                check(result.map { it["id"]!!.jsonPrimitive.int } == listOf(90, 32, 8))
                check("ws-secret" in result.toString())
            }
            scenario("WebSocket native ID lookup exposes complete raw payload in Unicode-safe chunks") {
                val first = page(call("get_web_socket_message_by_index",
                    """{"index":32,"maxMessageChars":256}"""))
                check(first["id"]!!.jsonPrimitive.int == 32 && first["compact"]!!.jsonPrimitive.boolean == false)
                val firstPayload = first["payload"]!!.jsonObject
                check(firstPayload["text"]!!.jsonPrimitive.content.lastOrNull()?.isHighSurrogate() != true)
                val next = firstPayload["nextOffset"]!!.jsonPrimitive.int
                val second = page(call("get_web_socket_message_by_index",
                    """{"index":32,"contentOffset":$next,"maxMessageChars":256}"""))
                check(second["payload"]!!.jsonObject["offset"]!!.jsonPrimitive.int == next)
                val outside = page(call("get_web_socket_message_by_index", """{"index":90}"""))
                check(outside["payload"]!!.jsonObject["text"]!!.jsonPrimitive.content == "notice")
            }
            scenario("native history items open in Repeater and Intruder without reconstruction") {
                repeaterCalls.clear()
                intruderCalls.clear()
                check("#91" in text(call("create_repeater_tab_from_history", """{"index":91,"tabName":"captured"}""")))
                check("#7" in text(call("send_history_item_to_intruder", """{"index":7}""")))
                check(repeaterCalls.single().size == 2 && repeaterCalls.single()[0] === http[1].finalRequest() &&
                    repeaterCalls.single()[1] == "captured")
                check(intruderCalls.single().size == 1 && intruderCalls.single()[0] === http[0].finalRequest())
                check(call("create_repeater_tab_from_history", """{"index":999}""").isError == true)
                check(call("send_history_item_to_intruder", """{"index":-1}""").isError == true)
            }
            scenario("single replay preserves captured request, approval path and raw response") {
                replayed.clear()
                val result = page(call("replay_history_item", """{"index":7}"""))
                check(replayed.single() === http[0].finalRequest())
                check(result["statusCode"]!!.jsonPrimitive.int == 201)
                check("replay-secret" in result["response"]!!.jsonObject["text"]!!.jsonPrimitive.content)
                check("private=query" !in result["endpoint"]!!.jsonPrimitive.content)
            }
            scenario("invalid regex, colors and page sizes fail before accessing history") {
                val before = proxyReads
                check(call("get_proxy_http_history_summary", """{"regex":"["}""").isError == true)
                check(call("get_proxy_http_history_regex", """{"regex":"x","colors":["bad"]}""").isError == true)
                check(call("get_proxy_websocket_history_regex", """{"regex":"["}""").isError == true)
                check(call("get_proxy_websocket_history", """{"count":101}""").isError == true)
                check(call("get_proxy_websocket_history", """{"offset":-1}""").isError == true)
                check(call("get_web_socket_message_by_index", """{"index":8,"maxMessageChars":1}""").isError == true)
                check(call("replay_history_item", """{"index":7,"maxMessageChars":1}""").isError == true)
                check(before == proxyReads)
            }
            scenario("all filtered readers respect denial before reading scope, history or payloads") {
                config.requireDataAccessApproval = true
                val previous = DataAccessSecurity.approvalHandler
                val requested = mutableListOf<DataAccessType>()
                DataAccessSecurity.approvalHandler = object : DataAccessApprovalHandler {
                    override suspend fun requestDataAccess(accessType: DataAccessType, config: McpConfig): Boolean {
                        requested += accessType
                        return false
                    }
                }
                try {
                    val before = listOf(proxyReads, scopeReads, bodyReads.size, wsReads.size)
                    check(call("get_proxy_http_history_summary", """{"regex":"x","inScopeOnly":true}""").isError == true)
                    check(call("get_requests_by_color", """{"colors":["red"],"regex":"x"}""").isError == true)
                    check(call("get_proxy_http_history_regex", """{"regex":"x"}""").isError == true)
                    check(call("get_proxy_websocket_history").isError == true)
                    check(call("get_proxy_websocket_history_regex", """{"regex":"x","inScopeOnly":false}""").isError == true)
                    check(call("get_web_socket_message_by_index", """{"index":8}""").isError == true)
                    check(before == listOf(proxyReads, scopeReads, bodyReads.size, wsReads.size))
                    check(call("create_repeater_tab_from_history", """{"index":7}""").isError == true)
                    check(call("send_history_item_to_intruder", """{"index":7}""").isError == true)
                    check(call("replay_history_item", """{"index":7}""").isError == true)
                    check(requested.count { it == DataAccessType.HTTP_HISTORY } == 6)
                    check(requested.count { it == DataAccessType.WEBSOCKET_HISTORY } == 3)
                } finally { DataAccessSecurity.approvalHandler = previous }
            }
        } finally {
            runBlocking { server.close() }
            config.cleanup()
        }
        println("Filtered history: $passed scenarios passed")
    }

    private fun annotations(color: HighlightColor, notes: String) = fake<Annotations> { method, _ -> when (method) {
        "highlightColor" -> color; "notes" -> notes; else -> error(method)
    } }

    private fun httpFixture(id: Int, color: HighlightColor, url: String, body: String, notes: String = "", read: () -> Unit): ProxyHttpRequestResponse {
        val annotations = annotations(color, notes)
        val service = fake<HttpService> { method, _ -> when (method) {
            "host" -> "scope.invalid"; "port" -> 443; "secure" -> true; else -> error(method)
        } }
        val request = fake<HttpRequest> { method, _ -> when (method) {
            "url" -> url; "method" -> "GET"; "httpVersion" -> "HTTP/2"; "parameters" -> emptyList<Any>()
            "httpService" -> service
            "toString" -> { read(); "GET / HTTP/2\r\nAuthorization: Bearer private-header\r\n\r\n$body" }
            else -> error(method)
        } }
        val response = fake<HttpResponse> { method, _ -> when (method) {
            "statusCode" -> 200.toShort(); "toString" -> { read(); "HTTP/2 200 OK\r\n\r\n$body" }; else -> error(method)
        } }
        return fake { method, _ -> when (method) {
            "id" -> id; "annotations" -> annotations; "finalRequest", "request" -> request
            "response" -> response; "timingData" -> null; else -> error(method)
        } }
    }

    private fun wsFixture(id: Int, url: String, payload: String, notes: String = "", read: () -> Unit): ProxyWebSocketMessage {
        val annotations = annotations(HighlightColor.NONE, notes)
        val request = fake<HttpRequest> { method, _ -> when (method) { "url" -> url; else -> error(method) } }
        val bytes = fake<burp.api.montoya.core.ByteArray> { method, _ -> when (method) { "toString" -> payload; else -> error(method) } }
        return fake { method, _ -> when (method) {
            "id" -> id; "webSocketId" -> 42; "annotations" -> annotations; "upgradeRequest" -> request
            "direction" -> Direction.SERVER_TO_CLIENT; "payload" -> { read(); bytes }; else -> error(method)
        } }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> fake(crossinline handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            handler(method.name, args ?: emptyArray())
        } as T
}
