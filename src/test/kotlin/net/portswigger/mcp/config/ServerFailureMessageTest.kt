package net.portswigger.mcp.config

import org.junit.jupiter.api.Test
import java.net.BindException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.CompletionException
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ServerFailureMessageTest {
    @Test
    fun `nested port conflict explains endpoint and recovery`() {
        val error = CompletionException(BindException("Address already in use"))
        val message = serverFailureMessage(error, "127.0.0.1", 9876)
        assertContains(message, "127.0.0.1:9876")
        assertContains(message, "Unload the previous MCP extension")
        assertContains(message, "reinstall the client configuration")
    }

    @Test
    fun `nested unresolved address identifies the configured host`() {
        assertEquals("Unable to resolve server address: invalid.local",
            serverFailureMessage(CompletionException(UnresolvedAddressException()), "invalid.local", 9876))
    }

    @Test
    fun `other errors retain the original message`() {
        assertEquals("Unexpected failure", serverFailureMessage(IllegalStateException("Unexpected failure"), "localhost", 9876))
        assertEquals("IllegalStateException", serverFailureMessage(IllegalStateException(), "localhost", 9876))
    }

    @Test
    fun `cyclic causes do not hang error rendering`() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)
        assertEquals("first", serverFailureMessage(first, "localhost", 9876))
    }
}
