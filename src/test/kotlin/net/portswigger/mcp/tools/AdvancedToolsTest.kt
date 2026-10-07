package net.portswigger.mcp.tools

import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.http.HttpService
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

    @Test
    fun `MCP-created Repeater title is associated with an unchanged request`() {
        val service = mockk<HttpService>()
        every { service.host() } returns "example.com"
        every { service.port() } returns 443
        every { service.secure() } returns true
        val request = mockk<HttpRequest>()
        every { request.httpService() } returns service
        every { request.toString() } returns "GET /finding HTTP/2\r\nHost: example.com\r\n\r\n"

        TrafficStore.registerRepeaterTab(request, "FIND_024")

        val association = TrafficStore.resolveRepeaterTab(request)
        assertEquals("FIND_024", association?.title)
        assertEquals("MCP_REQUEST_FINGERPRINT", association?.source)
    }

    @Test
    fun `conflicting Repeater titles for the same request are reported as ambiguous`() {
        val request = mockk<HttpRequest>(relaxed = true)
        every { request.toString() } returns "GET /same HTTP/1.1\r\nHost: example.com\r\n\r\n"

        TrafficStore.registerRepeaterTab(request, "FIND_024")
        TrafficStore.registerRepeaterTab(request, "CONTROL_024")

        val association = TrafficStore.resolveRepeaterTab(request)
        assertEquals(null, association?.title)
        assertEquals("AMBIGUOUS_MCP_REQUEST_FINGERPRINT", association?.source)
    }

    @Test
    fun `live selected Repeater title follows its request message ID`() {
        TrafficStore.registerPendingRepeaterTitle(247, "FIND_024", "BURP_SWING_SELECTED_AT_REQUEST")

        val association = TrafficStore.consumePendingRepeaterTitle(247)
        assertEquals("FIND_024", association?.title)
        assertEquals("BURP_SWING_SELECTED_AT_REQUEST", association?.source)
        assertEquals(null, TrafficStore.consumePendingRepeaterTitle(247), "association must be consumed once")
    }

    @Test
    fun `direct MCP exchanges receive persistent IDs and chunk metadata`() {
        val request = mockk<HttpRequest>(relaxed = true)
        val response = mockk<HttpResponse>(relaxed = true)
        every { request.toString() } returns "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"
        every { response.statusCode() } returns 200

        val exchange = TrafficStore.recordMcp(request, response, "HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(1000))
        val result = directExchangeResult(exchange, 256)

        assertTrue(exchange.exchangeId.startsWith("mcp-"))
        assertTrue(exchange.messageId < 0)
        assertSame(exchange, TrafficStore.byExchangeId(exchange.exchangeId))
        assertTrue(result.contains("\"nextOffset\":256"), result)
    }

    @Test
    fun `Collaborator payload links automatically to originating exchange and trace ID`() {
        CollaboratorCorrelationStore.clear()
        CollaboratorCorrelationStore.register(
            payloadId = "abc123",
            payload = "abc123.example.test",
            customData = "case-1",
            originSource = null,
            originId = null,
            originExchangeId = null,
            traceId = null
        )
        val request = mockk<HttpRequest>(relaxed = true)
        every { request.toString() } returns "GET /?u=abc123.example.test HTTP/1.1\r\n" +
            "Host: target.example\r\nTraceparent: 00-feedface-cafebabe-01\r\n\r\n"
        val exchange = TrafficStore.recordMcp(request, null, "")

        val correlated = CollaboratorCorrelationStore.correlate("abc123", "interaction-1", "{}", "case-1")

        assertEquals(exchange.exchangeId, correlated?.originExchangeId)
        assertEquals(exchange.messageId, correlated?.originId)
        assertEquals("00-feedface-cafebabe-01", correlated?.traceId)
    }

    @Test
    fun `outbound request gate rejects excess concurrency`() {
        OutboundRequestGate.configure(1)
        try {
            OutboundRequestGate.withPermit {
                val error = assertThrows(ToolBusyException::class.java) {
                    OutboundRequestGate.withPermit { error("permit should not be granted") }
                }
                assertTrue(error.message!!.contains("concurrency limit (1)"))
            }
            assertTrue(OutboundRequestGate.metrics().toString().contains("\"rejected\":1"))
        } finally {
            OutboundRequestGate.configure(2)
        }
    }
    @AfterEach
    fun cleanup() {
        TrafficStore.shutdown()
        ToolAuditLog.clear()
        CollaboratorCorrelationStore.clear()
    }

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
    fun `tool metrics aggregate output cost and failures`() {
        ToolAuditLog.add("search_http_history", true, 10, resultChars = 120)
        ToolAuditLog.add("search_http_history", false, 5, error = "busy")
        ToolAuditLog.add("get_http_exchange", true, 20, resultChars = 900)

        val search = ToolAuditLog.metrics().first { it.tool == "search_http_history" }
        assertEquals(2, search.calls)
        assertEquals(1, search.successes)
        assertEquals(1, search.failures)
        assertEquals(15, search.totalDurationMillis)
        assertEquals(120, search.totalResultChars)
        assertEquals(120, search.maxResultChars)
    }

    @Test
    fun `server instructions require preview and forbid implicit batches`() {
        assertTrue(SERVER_INSTRUCTIONS.contains("Preview mutations before sending"))
        assertTrue(SERVER_INSTRUCTIONS.contains("Do not turn a single mutation into a batch"))
        assertTrue(SERVER_INSTRUCTIONS.contains("returned unchanged by default"))
    }
}
