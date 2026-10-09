package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.websocket.Direction
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val MAX_UPGRADE_REQUEST_CHARS = 128 * 1024
private const val MAX_OUTBOUND_FRAME_BYTES = 256 * 1024

@Serializable
internal data class OpenWebSocket(
    val upgradeRequest: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean,
    val maxResponseChars: Int = 10_000
) : HttpServiceParams

@Serializable
internal data class ListWebSocketSessions(val includeClosed: Boolean = true)

@Serializable
internal data class SendWebSocketMessage(
    val sessionId: String,
    val type: WebSocketPayloadType = WebSocketPayloadType.TEXT,
    val payload: String
)

@Serializable
internal data class ReplayWebSocketMessage(
    val sessionId: String,
    val sourceId: Int,
    val type: WebSocketPayloadType,
    val allowServerToClientSource: Boolean = false
)

@Serializable
internal data class GetWebSocketSessionMessages(
    val sessionId: String,
    val count: Int = 20,
    val afterMessageId: Long? = null
)

@Serializable
internal data class CloseWebSocket(val sessionId: String)

internal fun Server.registerWebSocketTools(api: MontoyaApi, config: McpConfig, readOnlyMode: Boolean) {
    val json = Json { encodeDefaults = true }

    mcpTool<ListWebSocketSessions>(
        "List WebSocket sessions opened by this MCP extension. Returns compact metadata only and sends no traffic.",
        READ_ONLY_TOOL
    ) {
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ManagedWebSocketSession.serializer()),
            WebSocketSessionStore.list().filter { includeClosed || it.open }
        )
    }

    mcpTool<GetWebSocketSessionMessages>(
        "Read bounded text/binary messages observed on one MCP-managed WebSocket session. Binary payloads are Base64. " +
            "Messages larger than 64 KiB are explicitly marked truncated. Use afterMessageId for stable forward polling. No traffic is sent.",
        READ_ONLY_TOOL
    ) {
        require(count in 1..100) { "count must be between 1 and 100" }
        val messages = WebSocketSessionStore.messages(sessionId)
            .asSequence().filter { afterMessageId == null || it.id > afterMessageId }.take(count).toList()
        buildJsonObject {
            put("session", Json.encodeToJsonElement(ManagedWebSocketSession.serializer(), WebSocketSessionStore.metadata(sessionId)))
            putJsonArray("messages") { messages.forEach { add(Json.encodeToJsonElement(ManagedWebSocketMessage.serializer(), it)) } }
            put("nextAfterMessageId", messages.lastOrNull()?.id?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
    }

    if (readOnlyMode) return

    mcpTool<OpenWebSocket>(
        "Open one WebSocket through Burp from a complete reviewed HTTP upgrade request. The handshake passes through " +
            "Burp's per-target approval and outbound concurrency limit. Returns an opaque sessionId. No retries.",
        BURP_GATED_TOOL
    ) {
        require(upgradeRequest.isNotBlank() && upgradeRequest.length <= MAX_UPGRADE_REQUEST_CHARS) {
            "upgradeRequest must contain 1..$MAX_UPGRADE_REQUEST_CHARS characters"
        }
        require(maxResponseChars in 256..50_000) { "maxResponseChars must be between 256 and 50000" }
        val normalized = normalizeHttpContent(upgradeRequest)
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(targetHostname, targetPort, config, normalized, api)
        }
        if (!allowed) return@mcpTool "Open WebSocket denied by Burp Suite"
        val request = HttpRequest.httpRequest(toMontoyaService(), normalized)
        val creation = OutboundRequestGate.withPermit { api.websockets().createWebSocket(request) }
        val response = creation.upgradeResponse().orElse(null)
        val socket = creation.webSocket().orElse(null)
        if (socket == null) {
            return@mcpTool buildJsonObject {
                put("opened", false)
                put("status", creation.status().name)
                put("upgradeResponse", messageWindow(response?.toString().orEmpty(), 0, maxResponseChars, false, false))
            }.toString()
        }
        val session = runCatching {
            WebSocketSessionStore.add(
                socket, request.url().substringBefore('?').substringBefore('#'),
                targetHostname, targetPort, usesHttps
            )
        }.getOrElse { error ->
            runCatching { socket.close() }
            throw error
        }
        buildJsonObject {
            put("opened", true)
            put("status", creation.status().name)
            put("session", Json.encodeToJsonElement(ManagedWebSocketSession.serializer(), session))
            put("upgradeResponse", messageWindow(response?.toString().orEmpty(), 0, maxResponseChars, false, false))
        }.toString()
    }

    mcpTool<SendWebSocketMessage>(
        "Send one text or Base64-encoded binary message to an MCP-managed WebSocket. Each frame passes through " +
            "Burp's per-target approval and concurrency limit. Maximum decoded payload is 256 KiB. No batching or retries.",
        BURP_GATED_TOOL
    ) {
        val bytes = decodePayload(type, payload)
        val session = WebSocketSessionStore.metadata(sessionId)
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(
                session.hostname, session.port, config,
                "WebSocket ${type.name} frame to ${session.endpoint}\n\n$payload", api
            )
        }
        if (!allowed) return@mcpTool "Send WebSocket message denied by Burp Suite"
        val message = OutboundRequestGate.withPermit {
            if (type == WebSocketPayloadType.TEXT) WebSocketSessionStore.sendText(sessionId, payload)
            else WebSocketSessionStore.sendBinary(sessionId, bytes)
        }
        Json.encodeToString(ManagedWebSocketMessage.serializer(), message)
    }

    mcpTool<ReplayWebSocketMessage>(
        "Replay one captured WebSocket history payload by native Burp message ID into an MCP-managed session. " +
            "The caller must specify TEXT or BINARY because Proxy history does not expose frame type. Server-to-client " +
            "sources are rejected unless allowServerToClientSource=true. Uses data-access and per-target approvals. No retries.",
        BURP_GATED_TOOL
    ) {
        require(sourceId >= 0) { "sourceId must be a non-negative native Burp ID" }
        check(runBlocking { DataAccessSecurity.checkDataAccessPermission(DataAccessType.WEBSOCKET_HISTORY, config) }) {
            "WebSocket history access denied by Burp Suite"
        }
        val source = api.proxy().webSocketHistory().firstOrNull { it.id() == sourceId }
            ?: error("No WebSocket history message with Burp ID $sourceId; it may have been removed")
        check(source.direction() == Direction.CLIENT_TO_SERVER || allowServerToClientSource) {
            "Source message is SERVER_TO_CLIENT; set allowServerToClientSource=true to send its payload to the server"
        }
        val bytes = source.payload().bytes
        validatePayloadSize(bytes)
        val session = WebSocketSessionStore.metadata(sessionId)
        val approvalPayload = if (type == WebSocketPayloadType.TEXT) source.payload().toString()
            else Base64.getEncoder().encodeToString(bytes)
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(
                session.hostname, session.port, config,
                "Replay WebSocket #$sourceId as ${type.name} to ${session.endpoint}\n\n$approvalPayload", api
            )
        }
        if (!allowed) return@mcpTool "Replay WebSocket message denied by Burp Suite"
        val message = OutboundRequestGate.withPermit {
            if (type == WebSocketPayloadType.TEXT) WebSocketSessionStore.sendText(sessionId, source.payload().toString())
            else WebSocketSessionStore.sendBinary(sessionId, bytes)
        }
        buildJsonObject {
            put("sourceId", sourceId)
            put("sourceDirection", source.direction().name)
            put("sent", Json.encodeToJsonElement(ManagedWebSocketMessage.serializer(), message))
        }.toString()
    }

    mcpTool<CloseWebSocket>(
        "Close one MCP-managed WebSocket session locally. Safe to call again; sends no application data.",
        LOCAL_MUTATION_TOOL.copy(idempotent = true)
    ) {
        Json.encodeToString(ManagedWebSocketSession.serializer(), WebSocketSessionStore.close(sessionId))
    }
}

private fun decodePayload(type: WebSocketPayloadType, payload: String): kotlin.ByteArray {
    val bytes = if (type == WebSocketPayloadType.TEXT) payload.toByteArray(StandardCharsets.UTF_8)
    else try {
        Base64.getDecoder().decode(payload)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Binary WebSocket payload must be valid standard Base64")
    }
    validatePayloadSize(bytes)
    return bytes
}

private fun validatePayloadSize(bytes: kotlin.ByteArray) {
    require(bytes.size <= MAX_OUTBOUND_FRAME_BYTES) {
        "Decoded WebSocket payload exceeds $MAX_OUTBOUND_FRAME_BYTES bytes"
    }
}
