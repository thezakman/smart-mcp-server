package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val MAX_CAPTURED_PER_TOOL = 1000

private inline fun <T> safeCapture(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

/** A bounded, in-memory record of Repeater and Intruder traffic observed after extension load. */
data class CapturedExchange(
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

    fun byId(messageId: Int): CapturedExchange? = buffers.values.asSequence()
        .flatMap { it.snapshot().asSequence() }
        .lastOrNull { it.messageId == messageId }

    fun counts(): Map<String, Int> = ToolType.entries
        .filter { it == ToolType.REPEATER || it == ToolType.INTRUDER }
        .associate { it.name to (buffers[it.name]?.snapshot()?.size ?: 0) }

    @Synchronized
    fun shutdown() {
        registration?.takeIf { it.isRegistered }?.deregister()
        registration = null
        buffers.values.forEach { it.clear() }
        buffers.clear()
    }
}

@Serializable
data class CapturedExchangeSummary(
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
