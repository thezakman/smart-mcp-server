package net.portswigger.mcp.tools

import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.MimeType
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RepeaterEditorObserverTest {
    @AfterEach
    fun cleanup() {
        RepeaterEditorObserver.shutdown()
    }

    @Test
    fun `message serialization never holds the observer index lock`() {
        val raw = "GET /lock-check HTTP/1.1\r\nHost: example.test\r\n\r\n"
        val exchange = exchange(raw, "body")
        every { exchange.request().toString() } answers {
            assertFalse(Thread.holdsLock(RepeaterEditorObserver))
            raw
        }
        every { exchange.response().toString() } answers {
            assertFalse(Thread.holdsLock(RepeaterEditorObserver))
            "HTTP/1.1 200 OK\r\n\r\nbody"
        }
        RepeaterEditorObserver.observeForTest(
            exchange, RepeaterTabAssociation("tab-lock", "LOCK", source = "TEST", confidence = "EXACT")
        )
        val unseen = exchange(raw, "body")
        every { unseen.request().toString() } answers {
            assertFalse(Thread.holdsLock(RepeaterEditorObserver))
            raw
        }
        assertEquals("PROBABLE", RepeaterEditorObserver.resolve(unseen.request())?.confidence)
        assertEquals(1, RepeaterEditorObserver.history("tab-lock", null, true).size)
    }

    @Test
    fun `request identity resolves exact tab and observed exchange is retrievable`() {
        val exchange = exchange("GET /one HTTP/1.1\r\nHost: example.test\r\n\r\n", "A")
        val association = RepeaterTabAssociation(
            tabId = "repeater-tab-7",
            title = "FIND_024",
            source = "BURP_EDITOR_BINDING",
            confidence = "EXACT"
        )

        RepeaterEditorObserver.observeForTest(exchange, association)

        val resolved = RepeaterEditorObserver.resolve(exchange.request())
        assertEquals("FIND_024", resolved?.title)
        assertEquals("BURP_EDITOR_REQUEST_IDENTITY", resolved?.source)
        assertEquals("EXACT", resolved?.confidence)
        val history = RepeaterEditorObserver.history("repeater-tab-7", null, newestFirst = true)
        assertEquals(1, history.size)
        assertNotNull(RepeaterEditorObserver.bySnapshotId(history.single().snapshotId))
    }

    @Test
    fun `identical request bytes in different tabs are ambiguous without identity`() {
        val first = exchange("GET /same HTTP/1.1\r\nHost: example.test\r\n\r\n", "A")
        val second = exchange("GET /same HTTP/1.1\r\nHost: example.test\r\n\r\n", "B")
        val unseen = exchange("GET /same HTTP/1.1\r\nHost: example.test\r\n\r\n", "C")
        RepeaterEditorObserver.observeForTest(
            first,
            RepeaterTabAssociation("tab-1", "FIRST", source = "TEST", confidence = "EXACT")
        )
        RepeaterEditorObserver.observeForTest(
            second,
            RepeaterTabAssociation("tab-2", "SECOND", source = "TEST", confidence = "EXACT")
        )

        val resolved = RepeaterEditorObserver.resolve(unseen.request())

        assertEquals(null, resolved?.title)
        assertEquals("AMBIGUOUS_EDITOR_REQUEST_FINGERPRINT", resolved?.source)
        assertEquals("AMBIGUOUS", resolved?.confidence)
    }

    @Test
    fun `renaming the same stable tab updates its label without creating ambiguity`() {
        val first = exchange("GET /rename HTTP/1.1\r\nHost: example.test\r\n\r\n", "A")
        val unseen = exchange("GET /rename HTTP/1.1\r\nHost: example.test\r\n\r\n", "B")
        RepeaterEditorObserver.observeForTest(
            first,
            RepeaterTabAssociation("tab-1", "OLD", source = "TEST", confidence = "EXACT")
        )
        RepeaterEditorObserver.observeForTest(
            first,
            RepeaterTabAssociation("tab-1", "NEW", source = "TEST", confidence = "EXACT")
        )

        val resolved = RepeaterEditorObserver.resolve(unseen.request())

        assertEquals("NEW", resolved?.title)
        assertEquals("PROBABLE", resolved?.confidence)
    }

    private fun exchange(rawRequest: String, body: String): HttpRequestResponse {
        val service = mockk<HttpService>()
        every { service.host() } returns "example.test"
        every { service.port() } returns 443
        every { service.secure() } returns true

        val request = mockk<HttpRequest>()
        every { request.httpService() } returns service
        every { request.method() } returns "GET"
        every { request.path() } returns "/same"
        every { request.toString() } returns rawRequest

        val response = mockk<HttpResponse>()
        every { response.statusCode() } returns 200
        every { response.mimeType() } returns MimeType.HTML
        every { response.toString() } returns "HTTP/1.1 200 OK\r\n\r\n$body"

        val exchange = mockk<HttpRequestResponse>()
        every { exchange.request() } returns request
        every { exchange.response() } returns response
        return exchange
    }
}
