package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue

private const val MAX_CAPTURED_PER_TOOL = 1000

private inline fun <T> safeCapture(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

/** A bounded, in-memory record of Repeater and Intruder traffic observed after extension load. */
data class CapturedExchange(
    val exchangeId: String,
    val messageId: Int,
    val tool: String,
    val capturedAt: String,
    val method: String?,
    val host: String?,
    val port: Int?,
    val secure: Boolean?,
    val path: String?,
    val statusCode: Int?,
    val mimeType: String?,
    val request: String,
    val response: String
)

object TrafficStore {
    private val buffers = ConcurrentHashMap<String, ToolBuffer>()
    private val nextMcpMessageId = AtomicInteger(-1)

    @Volatile
    private var registration: Registration? = null

    private class ToolBuffer {
        private val items = ArrayDeque<CapturedExchange>()

        @Synchronized
        fun add(exchange: CapturedExchange) {
            items.addLast(exchange)
            while (items.size > MAX_CAPTURED_PER_TOOL) items.removeFirst()
        }

        @Synchronized
        fun snapshot(): List<CapturedExchange> = ArrayList(items)

        @Synchronized
        fun clear() = items.clear()
    }

    @Synchronized
    fun ensureRegistered(api: MontoyaApi) {
        if (registration?.isRegistered == true) return

        registration = api.http().registerHttpHandler(object : HttpHandler {
            override fun handleHttpRequestToBeSent(requestToBeSent: HttpRequestToBeSent): RequestToBeSentAction =
                RequestToBeSentAction.continueWith(requestToBeSent)

            override fun handleHttpResponseReceived(responseReceived: HttpResponseReceived): ResponseReceivedAction {
                val tool = responseReceived.toolSource().toolType()
                if (tool == ToolType.REPEATER || tool == ToolType.INTRUDER) {
                    val request = responseReceived.initiatingRequest()
                    buffers.computeIfAbsent(tool.name) { ToolBuffer() }.add(
                        CapturedExchange(
                            exchangeId = "${tool.name.lowercase()}-${responseReceived.messageId()}",
                            messageId = responseReceived.messageId(),
                            tool = tool.name,
                            capturedAt = Instant.now().toString(),
                            method = safeCapture { request.method() },
                            host = safeCapture { request.httpService().host() },
                            port = safeCapture { request.httpService().port() },
                            secure = safeCapture { request.httpService().secure() },
                            path = safeCapture { request.path() },
                            statusCode = safeCapture { responseReceived.statusCode().toInt() },
                            mimeType = safeCapture { responseReceived.mimeType().name },
                            request = request.toString(),
                            response = responseReceived.toString()
                        )
                    )
                }
                return ResponseReceivedAction.continueWith(responseReceived)
            }
        })
    }

    fun snapshot(tool: ToolType, newestFirst: Boolean): List<CapturedExchange> {
        val items = buffers[tool.name]?.snapshot().orEmpty()
        return if (newestFirst) items.asReversed() else items
    }

    fun snapshot(source: String, newestFirst: Boolean): List<CapturedExchange> {
        val items = buffers[source.uppercase()]?.snapshot().orEmpty()
        return if (newestFirst) items.asReversed() else items
    }

    fun recordMcp(request: HttpRequest, response: HttpResponse?, responseText: String): CapturedExchange {
        val messageId = nextMcpMessageId.getAndDecrement()
        val exchange = CapturedExchange(
            exchangeId = "mcp-${messageId.toLong().absoluteValue.toString().padStart(8, '0')}",
            messageId = messageId,
            tool = "MCP",
            capturedAt = Instant.now().toString(),
            method = safeCapture { request.method() },
            host = safeCapture { request.httpService().host() },
            port = safeCapture { request.httpService().port() },
            secure = safeCapture { request.httpService().secure() },
            path = safeCapture { request.path() },
            statusCode = safeCapture { response?.statusCode()?.toInt() },
            mimeType = safeCapture { response?.mimeType()?.name },
            request = request.toString(),
            response = responseText
        )
        buffers.computeIfAbsent("MCP") { ToolBuffer() }.add(exchange)
        return exchange
    }

    fun byId(messageId: Int): CapturedExchange? = buffers.values.asSequence()
        .flatMap { it.snapshot().asSequence() }
        .lastOrNull { it.messageId == messageId }

    fun byExchangeId(exchangeId: String): CapturedExchange? = buffers.values.asSequence()
        .flatMap { it.snapshot().asSequence() }
        .lastOrNull { it.exchangeId.equals(exchangeId.trim(), ignoreCase = true) }

    fun counts(): Map<String, Int> = listOf(ToolType.REPEATER.name, ToolType.INTRUDER.name, "MCP")
        .associateWith { buffers[it]?.snapshot()?.size ?: 0 }

    @Synchronized
    fun shutdown() {
        registration?.takeIf { it.isRegistered }?.deregister()
        registration = null
        buffers.values.forEach { it.clear() }
        buffers.clear()
        nextMcpMessageId.set(-1)
    }
}

@Serializable
data class CapturedExchangeSummary(
    val exchangeId: String,
    val messageId: Int,
    val tool: String,
    val capturedAt: String,
    val method: String?,
    val host: String?,
    val port: Int?,
    val secure: Boolean?,
    val path: String?,
    val statusCode: Int?,
    val mimeType: String?,
    val requestLength: Int,
    val responseLength: Int
)

internal fun CapturedExchange.summary() = CapturedExchangeSummary(
    exchangeId = exchangeId,
    messageId = messageId,
    tool = tool,
    capturedAt = capturedAt,
    method = method,
    host = host,
    port = port,
    secure = secure,
    path = path,
    statusCode = statusCode,
    mimeType = mimeType,
    requestLength = request.length,
    responseLength = response.length
)

internal fun directExchangeResult(exchange: CapturedExchange, maxMessageChars: Int): String {
    validateMessageWindow(0, maxMessageChars)
    return buildJsonObject {
        put("source", "CAPTURED")
        put("exchangeId", exchange.exchangeId)
        put("messageId", exchange.messageId)
        put("statusCode", exchange.statusCode?.let(::JsonPrimitive) ?: JsonNull)
        put("summary", kotlinx.serialization.json.Json.encodeToJsonElement(exchange.summary()))
        put("response", messageWindow(exchange.response, 0, maxMessageChars, false, false))
        put("retrieval", "Use get_captured_exchange_by_id with exchangeId or messageId and contentOffset to retrieve every chunk")
    }.toString()
}
