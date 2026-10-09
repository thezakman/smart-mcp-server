package net.portswigger.mcp.tools

import burp.api.montoya.core.ByteArray as MontoyaByteArray
import burp.api.montoya.core.Registration
import burp.api.montoya.websocket.BinaryMessage
import burp.api.montoya.websocket.Direction
import burp.api.montoya.websocket.TextMessage
import burp.api.montoya.websocket.extension.ExtensionWebSocket
import burp.api.montoya.websocket.extension.ExtensionWebSocketMessageHandler
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebSocketSessionStoreTest {
    private val socket = mockk<ExtensionWebSocket>()
    private val registration = mockk<Registration>()
    private val handler = slot<ExtensionWebSocketMessageHandler>()

    init {
        every { socket.registerMessageHandler(capture(handler)) } returns registration
        every { socket.sendTextMessage(any()) } just runs
        every { socket.sendBinaryMessage(any()) } just runs
        every { socket.close() } just runs
        every { registration.isRegistered } returns true
        every { registration.deregister() } just runs
    }

    @AfterEach
    fun cleanup() = WebSocketSessionStore.closeAll()

    @Test
    fun `session records sent text and received binary with bounded metadata`() {
        val session = WebSocketSessionStore.add(socket, "wss://example.invalid/socket", "example.invalid", 443, true)
        val sent = WebSocketSessionStore.sendText(session.id, "hello")

        val binary = mockk<BinaryMessage>()
        every { binary.direction() } returns Direction.SERVER_TO_CLIENT
        val binaryPayload = mockk<MontoyaByteArray>()
        every { binaryPayload.bytes } returns byteArrayOf(1, 2, 3)
        every { binary.payload() } returns binaryPayload
        handler.captured.binaryMessageReceived(binary)

        val messages = WebSocketSessionStore.messages(session.id)
        assertEquals(2, messages.size)
        assertEquals("hello", sent.payload)
        assertEquals("AQID", messages[1].payload)
        assertEquals("BASE64", messages[1].encoding)
        assertEquals(1, WebSocketSessionStore.metadata(session.id).sentCount)
        assertEquals(1, WebSocketSessionStore.metadata(session.id).receivedCount)
    }

    @Test
    fun `remote close prevents later sends and local close is idempotent`() {
        val session = WebSocketSessionStore.add(socket, "ws://example.invalid/socket", "example.invalid", 80, false)
        handler.captured.onClose()

        assertFalse(WebSocketSessionStore.metadata(session.id).open)
        assertThrows(IllegalStateException::class.java) { WebSocketSessionStore.sendText(session.id, "late") }
        assertFalse(WebSocketSessionStore.close(session.id).open)
        assertFalse(WebSocketSessionStore.close(session.id).open)
    }

    @Test
    fun `message retention is bounded and uses stable forward ids`() {
        val session = WebSocketSessionStore.add(socket, "wss://example.invalid/socket", "example.invalid", 443, true)
        repeat(70) { index -> WebSocketSessionStore.sendText(session.id, "m$index") }

        val messages = WebSocketSessionStore.messages(session.id)
        assertEquals(64, messages.size)
        assertEquals(7, messages.first().id)
        assertEquals(70, messages.last().id)
    }

    @Test
    fun `oversized received message is explicitly truncated`() {
        val session = WebSocketSessionStore.add(socket, "wss://example.invalid/socket", "example.invalid", 443, true)
        val text = mockk<TextMessage>()
        every { text.direction() } returns Direction.SERVER_TO_CLIENT
        every { text.payload() } returns "x".repeat(70_000)
        handler.captured.textMessageReceived(text)

        val message = WebSocketSessionStore.messages(session.id).single()
        assertTrue(message.truncated)
        assertEquals(70_000, message.originalBytes)
        assertEquals(65_536, message.storedBytes)
    }

    @Test
    fun `close all closes sockets deregisters handlers and clears sessions`() {
        WebSocketSessionStore.add(socket, "wss://example.invalid/socket", "example.invalid", 443, true)
        WebSocketSessionStore.closeAll()

        verify(exactly = 1) { socket.close() }
        verify(exactly = 1) { registration.deregister() }
        assertTrue(WebSocketSessionStore.list().isEmpty())
    }

    @Test
    fun `failed handler registration does not retain a phantom session`() {
        every { socket.registerMessageHandler(any()) } throws IllegalStateException("registration failed")

        assertThrows(IllegalStateException::class.java) {
            WebSocketSessionStore.add(socket, "wss://example.invalid/socket", "example.invalid", 443, true)
        }
        assertTrue(WebSocketSessionStore.list().isEmpty())
    }
}
