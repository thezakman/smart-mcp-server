package net.portswigger.mcp.tools

import burp.api.montoya.core.ByteArray as MontoyaByteArray
import burp.api.montoya.core.Registration
import burp.api.montoya.websocket.BinaryMessage
import burp.api.montoya.websocket.TextMessage
import burp.api.montoya.websocket.extension.ExtensionWebSocket
import burp.api.montoya.websocket.extension.ExtensionWebSocketMessageHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID

internal enum class WebSocketPayloadType { TEXT, BINARY }

@Serializable
internal data class ManagedWebSocketMessage(
    val id: Long,
    val direction: String,
    val type: WebSocketPayloadType,
    val capturedAt: String,
    val originalBytes: Int,
    val storedBytes: Int,
    val truncated: Boolean,
    val payload: String,
    val encoding: String
)

@Serializable
internal data class ManagedWebSocketSession(
    val id: String,
    val endpoint: String,
    val hostname: String,
    val port: Int,
    val secure: Boolean,
    val createdAt: String,
    var open: Boolean = true,
    var closedAt: String? = null,
    var closeReason: String? = null,
    var sentCount: Long = 0,
    var receivedCount: Long = 0
)

/** Bounded in-memory state for WebSockets created explicitly by MCP. */
internal object WebSocketSessionStore {
    private const val MAX_ACTIVE_SESSIONS = 16
    private const val MAX_RETAINED_SESSIONS = 32
    private const val MAX_MESSAGES_PER_SESSION = 64
    private const val MAX_STORED_PAYLOAD_BYTES = 64 * 1024

    private data class State(
        val metadata: ManagedWebSocketSession,
        val socket: ExtensionWebSocket,
        var registration: Registration? = null,
        val messages: ArrayDeque<ManagedWebSocketMessage> = ArrayDeque(),
        var nextMessageId: Long = 1
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<String, State>()

    fun add(
        socket: ExtensionWebSocket,
        endpoint: String,
        hostname: String,
        port: Int,
        secure: Boolean
    ): ManagedWebSocketSession {
        val state = synchronized(lock) {
            check(sessions.values.count { it.metadata.open } < MAX_ACTIVE_SESSIONS) {
                "Active WebSocket session limit ($MAX_ACTIVE_SESSIONS) reached; close a session first"
            }
            evictClosedSessions()
            val metadata = ManagedWebSocketSession(
                id = "ws-${UUID.randomUUID()}", endpoint = endpoint, hostname = hostname,
                port = port, secure = secure, createdAt = Instant.now().toString()
            )
            State(metadata, socket).also { sessions[metadata.id] = it }
        }
        val registration = try {
            socket.registerMessageHandler(object : ExtensionWebSocketMessageHandler {
                override fun textMessageReceived(textMessage: TextMessage) {
                    record(state.metadata.id, textMessage.direction().name, WebSocketPayloadType.TEXT,
                        textMessage.payload().toByteArray(StandardCharsets.UTF_8), received = true)
                }

                override fun binaryMessageReceived(binaryMessage: BinaryMessage) {
                    record(state.metadata.id, binaryMessage.direction().name, WebSocketPayloadType.BINARY,
                        binaryMessage.payload().bytes, received = true)
                }

                override fun onClose() {
                    markClosed(state.metadata.id, "REMOTE_OR_PROTOCOL_CLOSE")
                }
            })
        } catch (error: Throwable) {
            synchronized(lock) { sessions.remove(state.metadata.id) }
            throw error
        }
        synchronized(lock) {
            sessions[state.metadata.id]?.registration = registration
        }
        return snapshot(state.metadata)
    }

    fun list(): List<ManagedWebSocketSession> = synchronized(lock) {
        sessions.values.map { snapshot(it.metadata) }.reversed()
    }

    fun metrics() = synchronized(lock) {
        buildJsonObject {
            put("active", sessions.values.count { it.metadata.open })
            put("retained", sessions.size)
            put("messages", sessions.values.sumOf { it.messages.size })
        }
    }

    fun metadata(id: String): ManagedWebSocketSession = synchronized(lock) {
        snapshot(requireState(id).metadata)
    }

    fun messages(id: String): List<ManagedWebSocketMessage> = synchronized(lock) {
        requireState(id).messages.toList()
    }

    fun sendText(id: String, payload: String): ManagedWebSocketMessage {
        val state = openState(id)
        state.socket.sendTextMessage(payload)
        return record(id, "CLIENT_TO_SERVER", WebSocketPayloadType.TEXT,
            payload.toByteArray(StandardCharsets.UTF_8), received = false)
    }

    fun sendBinary(id: String, payload: kotlin.ByteArray): ManagedWebSocketMessage {
        val state = openState(id)
        state.socket.sendBinaryMessage(MontoyaByteArray.byteArray(*payload))
        return record(id, "CLIENT_TO_SERVER", WebSocketPayloadType.BINARY, payload, received = false)
    }

    fun close(id: String): ManagedWebSocketSession {
        val state = synchronized(lock) { requireState(id) }
        val closeFailure = runCatching { if (state.metadata.open) state.socket.close() }.exceptionOrNull()
        synchronized(lock) {
            markClosedLocked(state, "MCP_CLOSE")
            runCatching { state.registration?.takeIf { it.isRegistered }?.deregister() }
            state.registration = null
        }
        closeFailure?.let { throw it }
        return synchronized(lock) { snapshot(state.metadata) }
    }

    fun closeAll() {
        val states = synchronized(lock) { sessions.values.toList() }
        states.forEach { state ->
            runCatching { if (state.metadata.open) state.socket.close() }
            runCatching { state.registration?.takeIf { it.isRegistered }?.deregister() }
        }
        synchronized(lock) { sessions.clear() }
    }

    private fun openState(id: String): State = synchronized(lock) {
        requireState(id).also { check(it.metadata.open) { "WebSocket session $id is closed" } }
    }

    private fun record(
        id: String,
        direction: String,
        type: WebSocketPayloadType,
        bytes: kotlin.ByteArray,
        received: Boolean
    ): ManagedWebSocketMessage = synchronized(lock) {
        val state = requireState(id)
        val stored = bytes.copyOfRange(0, minOf(bytes.size, MAX_STORED_PAYLOAD_BYTES))
        val message = ManagedWebSocketMessage(
            id = state.nextMessageId++, direction = direction, type = type,
            capturedAt = Instant.now().toString(), originalBytes = bytes.size,
            storedBytes = stored.size, truncated = stored.size != bytes.size,
            payload = if (type == WebSocketPayloadType.TEXT) stored.toString(StandardCharsets.UTF_8)
                else java.util.Base64.getEncoder().encodeToString(stored),
            encoding = if (type == WebSocketPayloadType.TEXT) "UTF8" else "BASE64"
        )
        state.messages.addLast(message)
        while (state.messages.size > MAX_MESSAGES_PER_SESSION) state.messages.removeFirst()
        if (received) state.metadata.receivedCount++ else state.metadata.sentCount++
        message
    }

    private fun markClosed(id: String, reason: String) = synchronized(lock) {
        sessions[id]?.let { markClosedLocked(it, reason) }
    }

    private fun markClosedLocked(state: State, reason: String) {
        if (!state.metadata.open) return
        state.metadata.open = false
        state.metadata.closedAt = Instant.now().toString()
        state.metadata.closeReason = reason
    }

    private fun requireState(id: String): State {
        require(id.matches(Regex("^ws-[0-9a-f-]{36}$"))) { "Use a sessionId returned by open_web_socket" }
        return sessions[id] ?: error("Unknown or expired WebSocket session: $id")
    }

    private fun evictClosedSessions() {
        while (sessions.size >= MAX_RETAINED_SESSIONS) {
            val oldestClosed = sessions.entries.firstOrNull { !it.value.metadata.open }
                ?: error("WebSocket session retention limit reached; close an active session first")
            runCatching { oldestClosed.value.registration?.takeIf { it.isRegistered }?.deregister() }
            sessions.remove(oldestClosed.key)
        }
    }

    private fun snapshot(value: ManagedWebSocketSession) = value.copy()
}
