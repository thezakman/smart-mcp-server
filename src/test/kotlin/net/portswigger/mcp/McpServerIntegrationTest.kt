package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.core.Registration
import burp.api.montoya.core.ByteArray as MontoyaByteArray
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest as MontoyaHttpRequest
import burp.api.montoya.websocket.WebSockets
import burp.api.montoya.websocket.extension.ExtensionWebSocket
import burp.api.montoya.websocket.extension.ExtensionWebSocketCreation
import burp.api.montoya.websocket.extension.ExtensionWebSocketCreationStatus
import burp.api.montoya.proxy.ProxyWebSocketMessage
import burp.api.montoya.websocket.Direction
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional

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
    fun setup(testInfo: TestInfo) {
        if (testInfo.testMethod.get().name == "read only profile excludes navigation tools") {
            every { persistedObject.getString("_toolProfile") } returns "READ_ONLY"
        }
        serverManager.start(config) { state ->
            if (state is ServerState.Running) {
                serverStarted = true
            }
        }
        
        runBlocking {
            var attempts = 0
            while (!serverStarted && attempts < 50) {
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
            assertTrue(toolNames.contains("list_repeater_tabs"), "Server should expose live Repeater tab titles")
            assertTrue(toolNames.contains("compare_http_exchanges"), "Server should expose read-only comparison")
            assertTrue(toolNames.contains("compare_auth_controls"), "Server should expose three-control comparison")
            assertTrue(toolNames.contains("preview_request_mutation"), "Server should expose mutation preview")
            assertTrue(toolNames.contains("send_mutated_request"), "Server should expose single mutation send")
            assertTrue(toolNames.contains("get_mcp_diagnostics"), "Server should expose diagnostics")
            assertTrue(toolNames.contains("search_http_history"), "Core should expose structured history search")
            assertTrue(toolNames.contains("get_http_exchange"), "Core should expose bounded exchange detail")
            assertTrue(toolNames.contains("get_proxy_http_history_summary"), "Core should expose history summary")
            assertTrue(toolNames.contains("get_proxy_http_history_regex"), "Core should expose regex history search")
            assertFalse(toolNames.contains("url_encode"), "Core should hide legacy utility tools")
            assertTrue(tools.size in 31..49, "Core catalog should stay compact; got ${tools.size} tools")

            listOf("select_burp_tool", "select_repeater_tab", "select_organizer_item").forEach { name ->
                assertTrue(name in toolNames)
                assertEquals(false, tools.single { it.name == name }.annotations?.readOnlyHint)
                assertEquals(false, tools.single { it.name == name }.annotations?.openWorldHint)
            }
            listOf(
                "open_web_socket", "list_web_socket_sessions", "send_web_socket_message",
                "replay_web_socket_message", "get_web_socket_session_messages", "close_web_socket"
            ).forEach { name -> assertTrue(name in toolNames, "Core should expose $name") }

            val burpGatedTools = setOf(
                "send_http1_request",
                "send_http2_request",
                "replay_history_item",
                "send_mutated_request",
                "set_project_options",
                "set_user_options"
            )
            val targetBoundTools = burpGatedTools + setOf(
                "open_web_socket", "send_web_socket_message", "replay_web_socket_message"
            )
            targetBoundTools.forEach { name ->
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
    fun `navigation tools reject invalid targets through MCP without accessing UI`() = runBlocking {
        every { persistedObject.getBoolean("requireDataAccessApproval") } returns false
        every { api.organizer().items() } returns emptyList()
        client.connectToServer("http://127.0.0.1:$testPort")
        val organizer = client.callTool("select_organizer_item", mapOf("id" to 999))
        assertEquals(true, organizer?.isError)
        assertTrue(organizer.toString().contains("No Organizer item"))
        val repeater = client.callTool("select_repeater_tab", mapOf("tabId" to ""))
        assertEquals(true, repeater?.isError)
        verify(exactly = 0) { api.userInterface() }
    }

    @Test
    fun `websocket lifecycle works through MCP without external traffic`() = runBlocking {
        every { persistedObject.getBoolean("requireHttpRequestApproval") } returns false
        every { persistedObject.getBoolean("requireDataAccessApproval") } returns false
        val webSockets = mockk<WebSockets>()
        val creation = mockk<ExtensionWebSocketCreation>()
        val socket = mockk<ExtensionWebSocket>()
        val registration = mockk<Registration>()
        every { api.websockets() } returns webSockets
        every { webSockets.createWebSocket(any<burp.api.montoya.http.message.requests.HttpRequest>()) } returns creation
        every { creation.status() } returns ExtensionWebSocketCreationStatus.SUCCESS
        every { creation.webSocket() } returns Optional.of(socket)
        every { creation.upgradeResponse() } returns Optional.empty()
        every { socket.registerMessageHandler(any()) } returns registration
        every { socket.sendTextMessage(any()) } just runs
        every { socket.close() } just runs
        every { registration.isRegistered } returns true
        every { registration.deregister() } just runs

        val service = mockk<HttpService>()
        val request = mockk<MontoyaHttpRequest>()
        mockkStatic(HttpService::class)
        mockkStatic(MontoyaHttpRequest::class)
        every { HttpService.httpService("127.0.0.1", 80, false) } returns service
        every { MontoyaHttpRequest.httpRequest(service, any<String>()) } returns request
        every { request.url() } returns "ws://127.0.0.1/socket"

        try {
            client.connectToServer("http://127.0.0.1:$testPort")
            val opened = client.callTool("open_web_socket", mapOf(
                "targetHostname" to "127.0.0.1", "targetPort" to 80, "usesHttps" to false,
                "upgradeRequest" to "GET /socket HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
            ))
            assertEquals(false, opened?.isError, opened.toString())
            val sessionId = Regex("ws-[0-9a-f-]{36}").find(opened.toString())?.value
            assertNotNull(sessionId, opened.toString())

            val sent = client.callTool("send_web_socket_message", mapOf(
                "sessionId" to sessionId!!, "type" to "TEXT", "payload" to "hello"
            ))
            assertEquals(false, sent?.isError, sent.toString())
            verify(exactly = 1) { socket.sendTextMessage("hello") }

            val malformedBinary = client.callTool("send_web_socket_message", mapOf(
                "sessionId" to sessionId, "type" to "BINARY", "payload" to "not-base64!"
            ))
            assertEquals(true, malformedBinary?.isError)

            val sourcePayload = mockk<MontoyaByteArray>()
            every { sourcePayload.bytes } returns "server-frame".toByteArray()
            every { sourcePayload.toString() } returns "server-frame"
            val source = mockk<ProxyWebSocketMessage>()
            every { source.id() } returns 71
            every { source.direction() } returns Direction.SERVER_TO_CLIENT
            every { source.payload() } returns sourcePayload
            every { api.proxy().webSocketHistory() } returns listOf(source)
            val guardedReplay = client.callTool("replay_web_socket_message", mapOf(
                "sessionId" to sessionId, "sourceId" to 71, "type" to "TEXT"
            ))
            assertEquals(true, guardedReplay?.isError)
            val replayed = client.callTool("replay_web_socket_message", mapOf(
                "sessionId" to sessionId, "sourceId" to 71, "type" to "TEXT",
                "allowServerToClientSource" to true
            ))
            assertEquals(false, replayed?.isError, replayed.toString())
            verify(exactly = 1) { socket.sendTextMessage("server-frame") }

            val closed = client.callTool("close_web_socket", mapOf("sessionId" to sessionId))
            assertEquals(false, closed?.isError, closed.toString())
            verify(exactly = 1) { socket.close() }
        } finally {
            unmockkStatic(MontoyaHttpRequest::class)
            unmockkStatic(HttpService::class)
        }
    }

    @Test
    fun `read only profile excludes navigation tools`() = runBlocking {
        // The profile was set before server startup in setup.
        client.connectToServer("http://127.0.0.1:$testPort")
        val names = client.listTools().map { it.name }
        assertTrue("list_repeater_tabs" in names)
        listOf(
            "select_burp_tool", "select_repeater_tab", "select_organizer_item",
            "open_web_socket", "send_web_socket_message", "replay_web_socket_message", "close_web_socket"
        ).forEach {
            assertFalse(it in names, "$it must be omitted in READ_ONLY")
        }
        assertTrue("list_web_socket_sessions" in names)
        assertTrue("get_web_socket_session_messages" in names)
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
