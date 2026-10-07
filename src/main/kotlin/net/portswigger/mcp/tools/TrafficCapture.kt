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
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue

private const val MAX_CAPTURED_PER_TOOL = 1000
private const val MAX_REPEATER_TAB_ASSOCIATIONS = 1000
private const val MAX_PENDING_REPEATER_REQUESTS = 1000

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
    val tabId: String? = null,
    val tabTitle: String? = null,
    val tabGroup: String? = null,
    val tabTitleSource: String? = null,
    val tabAssociationConfidence: String? = null,
    val request: String,
    val response: String
)

object TrafficStore {
    private val buffers = ConcurrentHashMap<String, ToolBuffer>()
    private val nextMcpMessageId = AtomicInteger(-1)
    private val repeaterTabTitles = LinkedHashMap<String, LinkedHashSet<String>>()
    private val pendingRepeaterTitles = LinkedHashMap<Int, RepeaterTabAssociation>()

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
            override fun handleHttpRequestToBeSent(requestToBeSent: HttpRequestToBeSent): RequestToBeSentAction {
                if (requestToBeSent.toolSource().toolType() == ToolType.REPEATER) {
                    val observed = safeCapture { RepeaterEditorObserver.resolve(requestToBeSent) }
                    val selected = safeCapture { RepeaterUiInspector.selectedTab(api) }?.tab
                    val association = when {
                        observed?.confidence == "EXACT" -> observed
                        selected != null -> RepeaterTabAssociation(
                            tabId = selected.id,
                            title = selected.title,
                            groupTitle = selected.groupTitle,
                            source = "BURP_SWING_ACTIVE_SELECTION_AT_REQUEST",
                            confidence = "PROBABLE"
                        )
                        else -> observed
                    }
                    if (association != null) registerPendingRepeaterAssociation(
                        requestToBeSent.messageId(), association
                    )
                }
                return RequestToBeSentAction.continueWith(requestToBeSent)
            }

            override fun handleHttpResponseReceived(responseReceived: HttpResponseReceived): ResponseReceivedAction {
                val tool = responseReceived.toolSource().toolType()
                if (tool == ToolType.REPEATER || tool == ToolType.INTRUDER) {
                    val request = responseReceived.initiatingRequest()
                    val tabAssociation = if (tool == ToolType.REPEATER) {
                        consumePendingRepeaterTitle(responseReceived.messageId()) ?: resolveRepeaterTab(request)
                    } else null
                    val exchange = CapturedExchange(
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
                            tabId = tabAssociation?.tabId,
                            tabTitle = tabAssociation?.title,
                            tabGroup = tabAssociation?.groupTitle,
                            tabTitleSource = tabAssociation?.source,
                            tabAssociationConfidence = tabAssociation?.confidence,
                            request = request.toString(),
                            response = responseReceived.toString()
                        )
                    buffers.computeIfAbsent(tool.name) { ToolBuffer() }.add(exchange)
                    CollaboratorCorrelationStore.linkRequest(exchange)
                }
                return ResponseReceivedAction.continueWith(responseReceived)
            }
        })
    }

    /**
     * Remember the title supplied when MCP creates a Repeater tab. Montoya does not expose tab IDs, titles or
     * existing tab enumeration, so later traffic can only be associated while its request remains unchanged.
     * Conflicting titles for identical requests are retained and reported as ambiguous instead of being guessed.
     */
    @Synchronized
    fun registerRepeaterTab(request: HttpRequest, tabName: String?) {
        val title = tabName?.takeIf { it.isNotBlank() } ?: return
        val fingerprint = requestFingerprint(request)
        repeaterTabTitles.getOrPut(fingerprint) { LinkedHashSet() }.add(title)
        while (repeaterTabTitles.size > MAX_REPEATER_TAB_ASSOCIATIONS) {
            repeaterTabTitles.remove(repeaterTabTitles.keys.first())
        }
    }

    @Synchronized
    internal fun resolveRepeaterTab(request: HttpRequest): RepeaterTabAssociation? {
        val titles = repeaterTabTitles[requestFingerprint(request)] ?: return null
        return if (titles.size == 1) {
            RepeaterTabAssociation(
                title = titles.first(),
                source = "MCP_REQUEST_FINGERPRINT",
                confidence = "PROBABLE"
            )
        } else {
            RepeaterTabAssociation(
                source = "AMBIGUOUS_MCP_REQUEST_FINGERPRINT",
                confidence = "AMBIGUOUS"
            )
        }
    }

    @Synchronized
    internal fun registerPendingRepeaterTitle(messageId: Int, title: String, source: String) {
        registerPendingRepeaterAssociation(
            messageId,
            RepeaterTabAssociation(title = title.trim(), source = source, confidence = "PROBABLE")
        )
    }

    @Synchronized
    internal fun registerPendingRepeaterAssociation(messageId: Int, association: RepeaterTabAssociation) {
        pendingRepeaterTitles[messageId] = association
        while (pendingRepeaterTitles.size > MAX_PENDING_REPEATER_REQUESTS) {
            pendingRepeaterTitles.remove(pendingRepeaterTitles.keys.first())
        }
    }

    @Synchronized
    internal fun consumePendingRepeaterTitle(messageId: Int): RepeaterTabAssociation? =
        pendingRepeaterTitles.remove(messageId)

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
        CollaboratorCorrelationStore.linkRequest(exchange)
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
        repeaterTabTitles.clear()
        pendingRepeaterTitles.clear()
        nextMcpMessageId.set(-1)
    }

    private fun requestFingerprint(request: HttpRequest): String {
        val canonical = buildString {
            append(safeCapture { request.httpService().host() }.orEmpty().lowercase()).append(':')
            append(safeCapture { request.httpService().port() } ?: -1).append(':')
            append(safeCapture { request.httpService().secure() } ?: false).append('\n')
            append(request.toString().replace("\r\n", "\n"))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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
    val tabId: String? = null,
    val tabTitle: String? = null,
    val tabGroup: String? = null,
    val tabTitleSource: String? = null,
    val tabAssociationConfidence: String? = null,
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
    tabId = tabId,
    tabTitle = tabTitle,
    tabGroup = tabGroup,
    tabTitleSource = tabTitleSource,
    tabAssociationConfidence = tabAssociationConfidence,
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
