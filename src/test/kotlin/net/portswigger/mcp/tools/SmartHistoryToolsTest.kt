package net.portswigger.mcp.tools

import burp.api.montoya.core.HighlightColor
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartHistoryToolsTest {
    private fun indexed(id: Int, latency: Long = 0, responseBytes: Int = 0) = IndexedHttpHistory(
        id = id,
        item = mockk(relaxed = true),
        color = HighlightColor.NONE,
        url = "https://example.com/$id",
        host = "example.com",
        path = "/$id",
        method = "GET",
        statusCode = 200,
        mimeType = "JSON",
        hasResponse = true,
        hasParameters = false,
        requestBytes = 10,
        responseBytes = responseBytes,
        responseStartMillis = latency,
        headerNames = emptySet(),
        headerValues = "",
        traceIds = emptySet()
    )

    @Test
    fun `keyset boundary remains stable when earlier entries disappear`() {
        assertTrue(isAfterHistoryCursor(indexed(9), 10, 10, "NEWEST"))
        assertFalse(isAfterHistoryCursor(indexed(11), 10, 10, "NEWEST"))
        assertTrue(isAfterHistoryCursor(indexed(9, latency = 99), 10, 100, "SLOWEST"))
        assertTrue(isAfterHistoryCursor(indexed(9, latency = 100), 10, 100, "SLOWEST"))
        assertFalse(isAfterHistoryCursor(indexed(11, latency = 100), 10, 100, "SLOWEST"))
    }

    @Test
    fun `history index extracts headers and trace identifiers without retaining bodies`() {
        val request = mockk<HttpRequest>(relaxed = true)
        val response = mockk<HttpResponse>(relaxed = true)
        val item = mockk<ProxyHttpRequestResponse>(relaxed = true)
        val traceHeader = mockk<HttpHeader>()
        every { traceHeader.name() } returns "traceparent"
        every { traceHeader.value() } returns "00-abc123-def456-01"
        every { request.headers() } returns listOf(traceHeader)
        every { response.headers() } returns emptyList()
        every { request.url() } returns "https://example.com/api"
        every { item.id() } returns 7
        every { item.finalRequest() } returns request
        every { item.response() } returns response
        every { item.annotations().highlightColor() } returns HighlightColor.NONE

        HistoryMetadataIndex.clear()
        val indexed = HistoryMetadataIndex.refresh(listOf(item)).single()

        assertTrue("traceparent" in indexed.headerNames)
        assertTrue(indexed.headerValues.contains("abc123"))
        assertEquals(setOf("00-abc123-def456-01"), indexed.traceIds)
    }

    @Test
    fun `JSON search matches dotted paths and scalar content`() {
        val request = mockk<HttpRequest>()
        val item = mockk<ProxyHttpRequestResponse>()
        every { request.bodyToString() } returns """{"data":{"user":{"id":42,"name":"Alice"}}}"""
        every { item.finalRequest() } returns request
        every { item.response() } returns null

        assertTrue(matchesJsonContent(item, "data.user.id", "Alice"))
        assertTrue(matchesJsonContent(item, "id", "42"))
        assertFalse(matchesJsonContent(item, "missing", null))
    }

    @Test
    fun `history projection returns only requested non-null fields`() {
        val summary = buildJsonObject {
            put("id", 42)
            put("method", "GET")
            put("statusCode", JsonNull)
        }

        val projected = projectHistorySummary(summary, setOf("id", "statusCode"), omitNulls = true)

        assertEquals(setOf("id"), projected.keys)
    }

    @Test
    fun `history cursor is compact opaque text`() {
        val cursor = encodeHistoryCursor(snapshotMaxId = 321, offset = 40)

        assertTrue(cursor.isNotBlank())
        assertFalse(cursor.contains(':'))
        assertTrue(cursor.length < 40)
    }
}
