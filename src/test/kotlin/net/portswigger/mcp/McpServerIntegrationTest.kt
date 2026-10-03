package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class McpServerIntegrationTest {
    private val client = TestSseMcpClient()
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val serverManager = KtorServerManager(api)
    private val testPort = findAvailablePort()
    private val persistedObject = mockk<PersistedObject>()
    private var serverStarted = false

    init {
        every { persistedObject.getBoolean(any()) } returns true
        every { persistedObject.getString(any()) } returns "127.0.0.1"
        every { persistedObject.getInteger("port") } returns testPort
        every { persistedObject.getInteger("_maxConcurrentRequests") } returns 2
        every { persistedObject.setBoolean(any(), any()) } returns Unit
        every { persistedObject.setString(any(), any()) } returns Unit
        every { persistedObject.setInteger(any(), any()) } returns Unit
    }

    private val mockLogging = mockk<Logging>().apply {
        every { logToError(any<String>()) } returns Unit
        every { logToOutput(any<String>()) } returns Unit
    }

    private val config = McpConfig(persistedObject, mockLogging)

    @BeforeEach
    fun setup() {
        serverManager.start(config) { state ->
            if (state is ServerState.Running) {
                serverStarted = true
            }
        }
        
        runBlocking {
            var attempts = 0
            while (!serverStarted && attempts < 10) {
                delay(100)
                attempts++
            }
            
            if (!serverStarted) {
                throw IllegalStateException("Server failed to start after timeout")
            }
        }
    }

    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            if (client.isConnected()) {
                client.close()
            }
        }
        serverManager.stop {}
    }

    @Test
    fun `server should accept connections and list tools`() = runBlocking {
        try {
            client.connectToServer("http://127.0.0.1:${testPort}")
            assertTrue(client.isConnected(), "Client should be connected to server")
            
            val tools = client.listTools()
            assertFalse(tools.isEmpty(), "Server should have registered tools")
            
            val toolNames = tools.map { it.name }
            assertTrue(toolNames.contains("output_project_options"), "Server should have output_project_options tool")
            assertTrue(toolNames.contains("output_user_options"), "Server should have output_user_options tool")
            assertTrue(toolNames.contains("list_site_map"), "Server should expose the compact Site Map index")
            assertTrue(toolNames.contains("list_organizer_items"), "Server should expose the compact Organizer index")
            assertTrue(toolNames.contains("get_repeater_traffic"), "Server should expose captured Repeater traffic")
            assertTrue(toolNames.contains("compare_http_exchanges"), "Server should expose read-only comparison")
            assertTrue(toolNames.contains("preview_request_mutation"), "Server should expose mutation preview")
            assertTrue(toolNames.contains("send_mutated_request"), "Server should expose single mutation send")
            assertTrue(toolNames.contains("get_mcp_diagnostics"), "Server should expose diagnostics")
            assertTrue(toolNames.contains("search_http_history"), "Core should expose structured history search")
            assertTrue(toolNames.contains("get_http_exchange"), "Core should expose bounded exchange detail")
            assertTrue(toolNames.contains("get_proxy_http_history_summary"), "Core should expose history summary")
            assertTrue(toolNames.contains("get_proxy_http_history_regex"), "Core should expose regex history search")
            assertFalse(toolNames.contains("url_encode"), "Core should hide legacy utility tools")
            assertTrue(tools.size in 25..35, "Core catalog should stay compact; got ${tools.size} tools")

            val burpGatedTools = setOf(
                "send_http1_request",
                "send_http2_request",
                "replay_history_item",
                "send_mutated_request",
                "set_project_options",
                "set_user_options"
            )
            burpGatedTools.forEach { name ->
                assertNull(
                    tools.single { it.name == name }.annotations,
                    "$name must defer approval to the Burp UI instead of requesting client approval"
                )
            }
            assertEquals(
                true,
                tools.single { it.name == "search_http_history" }.annotations?.readOnlyHint,
                "Read-only hints should remain available to MCP clients"
            )
            verify(atLeast = 1) { api.proxy().history() }
            
            val pingResult = client.ping()
            assertNotNull(pingResult, "Ping should return a result")
        } catch (e: Exception) {
            fail("Connection failed: ${e.message}")
        }
    }

    @Test
    fun `streamable HTTP endpoint accepts direct MCP initialize`() {
        val http = HttpClient.newHttpClient()
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"direct-test","version":"1.0"}}}"""
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$testPort/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("serverInfo"), response.body())
        assertTrue(response.body().contains("resources"), response.body())
        val sessionId = response.headers().firstValue("Mcp-Session-Id").orElseThrow()

        fun directPost(json: String): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$testPort/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", "2025-03-26")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        val initialized = directPost("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertTrue(initialized.statusCode() in setOf(200, 202, 204))
        val templates = directPost("""{"jsonrpc":"2.0","id":2,"method":"resources/templates/list","params":{}}""")
        assertEquals(200, templates.statusCode())
        assertTrue(templates.body().contains("burp://proxy/{id}/{part}"), templates.body())
    }
}
