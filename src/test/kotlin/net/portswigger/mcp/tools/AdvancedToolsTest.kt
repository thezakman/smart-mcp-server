package net.portswigger.mcp.tools

import burp.api.montoya.http.message.requests.HttpRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdvancedToolsTest {
    @AfterEach
    fun cleanup() = ToolAuditLog.clear()

    @Test
    fun `method mutation is explicit and returns transformed request`() {
        val source = mockk<HttpRequest>()
        val transformed = mockk<HttpRequest>()
        every { source.withMethod("PATCH") } returns transformed

        assertSame(transformed, mutateRequest(source, "method", null, "PATCH"))
        verify(exactly = 1) { source.withMethod("PATCH") }
    }

    @Test
    fun `header mutation updates an existing header`() {
        val source = mockk<HttpRequest>()
        val transformed = mockk<HttpRequest>()
        every { source.hasHeader("X-Test") } returns true
        every { source.withUpdatedHeader("X-Test", "one") } returns transformed

        assertSame(transformed, mutateRequest(source, "HEADER", "X-Test", "one"))
        verify(exactly = 1) { source.withUpdatedHeader("X-Test", "one") }
    }

    @Test
    fun `path mutation rejects an ambiguous relative value`() {
        val source = mockk<HttpRequest>()
        assertThrows(IllegalArgumentException::class.java) {
            mutateRequest(source, "PATH", null, "admin")
        }
    }

    @Test
    fun `audit log stays bounded and contains no tool arguments`() {
        repeat(550) { ToolAuditLog.add("tool_$it", true, it.toLong()) }
        val events = ToolAuditLog.snapshot(newestFirst = false)

        assertEquals(500, events.size)
        assertEquals("tool_50", events.first().tool)
        assertEquals("tool_549", events.last().tool)
        assertTrue(events.all { it.error == null })
    }

    @Test
    fun `server instructions require preview and forbid implicit batches`() {
        assertTrue(SERVER_INSTRUCTIONS.contains("Preview mutations before sending"))
        assertTrue(SERVER_INSTRUCTIONS.contains("Do not turn a single mutation into a batch"))
        assertTrue(SERVER_INSTRUCTIONS.contains("returned unchanged by default"))
    }
}
