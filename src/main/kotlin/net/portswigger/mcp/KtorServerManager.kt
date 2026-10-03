package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.tools.SERVER_INSTRUCTIONS
import net.portswigger.mcp.tools.TrafficStore
import net.portswigger.mcp.tools.HistoryMetadataIndex
import net.portswigger.mcp.tools.OutboundRequestGate
import net.portswigger.mcp.tools.registerHistoryResources
import net.portswigger.mcp.tools.registerTools
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KtorServerManager(private val api: MontoyaApi) : ServerManager {

    private var server: EmbeddedServer<*, *>? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun start(config: McpConfig, callback: (ServerState) -> Unit) {
        publish(ServerState.Starting, callback)

        executor.submit {
            try {
                server?.stop(1000, 5000)
                server = null

                val mcpServer = Server(
                    serverInfo = Implementation(
                        "burp-suite",
                        KtorServerManager::class.java.`package`.implementationVersion ?: "development"
                    ), options = ServerOptions(
                        capabilities = ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = false),
                            resources = ServerCapabilities.Resources(listChanged = false, subscribe = false)
                        )
                    ), instructions = SERVER_INSTRUCTIONS
                )

                OutboundRequestGate.configure(config.maxConcurrentRequests)
                val warmupStarted = System.nanoTime()
                val existingHistory = runCatching { api.proxy().history() }.getOrNull().orEmpty()
                val indexedEntries = HistoryMetadataIndex.refresh(existingHistory).size
                val warmupMillis = (System.nanoTime() - warmupStarted) / 1_000_000
                api.logging().logToOutput(
                    "Indexed $indexedEntries existing Proxy history entries in ${warmupMillis}ms"
                )

                mcpServer.registerTools(api, config)
                mcpServer.registerHistoryResources(api, config)
                RuntimeDiagnostics.updateCatalog(
                    count = mcpServer.tools.size,
                    schemaChars = mcpServer.tools.values.sumOf { Json.encodeToString(it.tool).length }
                )

                server = embeddedServer(Netty, port = config.port, host = config.host) {
                    install(CORS) {
                        allowHost("localhost:${config.port}")
                        allowHost("127.0.0.1:${config.port}")

                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowMethod(HttpMethod.Delete)

                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Accept)
                        allowHeader("Last-Event-ID")
                        allowHeader("Mcp-Session-Id")
                        allowHeader("MCP-Protocol-Version")

                        allowCredentials = false
                        allowNonSimpleContentTypes = true
                        maxAgeInSeconds = 3600
                    }

                    intercept(ApplicationCallPipeline.Call) {
                        val origin = call.request.header("Origin")
                        val host = call.request.header("Host")
                        val referer = call.request.header("Referer")
                        val userAgent = call.request.header("User-Agent")

                        if (origin != null && !isValidOrigin(origin)) {
                            api.logging().logToOutput("Blocked DNS rebinding attack from origin: $origin")
                            call.respond(HttpStatusCode.Forbidden)
                            return@intercept
                        } else if (isBrowserRequest(userAgent)) {
                            api.logging().logToOutput("Blocked browser request without Origin header")
                            call.respond(HttpStatusCode.Forbidden)
                            return@intercept
                        }

                        if (host != null && !isValidHost(host, config.port)) {
                            api.logging().logToOutput("Blocked DNS rebinding attack from host: $host")
                            call.respond(HttpStatusCode.Forbidden)
                            return@intercept
                        }

                        if (referer != null && !isValidReferer(referer)) {
                            api.logging().logToOutput("Blocked suspicious request from referer: $referer")
                            call.respond(HttpStatusCode.Forbidden)
                            return@intercept
                        }

                        call.response.header("X-Frame-Options", "DENY")
                        call.response.header("X-Content-Type-Options", "nosniff")
                        call.response.header("Referrer-Policy", "same-origin")
                        call.response.header("Content-Security-Policy", "default-src 'none'")
                    }

                    mcpStreamableHttp(
                        path = "/mcp",
                        enableDnsRebindingProtection = true,
                        allowedHosts = listOf("localhost:${config.port}", "127.0.0.1:${config.port}"),
                        allowedOrigins = listOf("http://localhost:${config.port}", "http://127.0.0.1:${config.port}")
                    ) {
                        mcpServer
                    }
                    routing {
                        mcp {
                            mcpServer
                        }
                    }
                }
                // Retain the engine before starting so a partial startup can be cleaned up on failure.
                server!!.start(wait = false)

                api.logging().logToOutput("Started MCP server on ${config.host}:${config.port}")
                publish(ServerState.Running, callback)

            } catch (e: Exception) {
                val failedServer = server
                server = null
                try {
                    failedServer?.stop(0, 1000)
                } catch (cleanupError: Exception) {
                    e.addSuppressed(cleanupError)
                }
                TrafficStore.shutdown()
                HistoryMetadataIndex.clear()
                api.logging().logToError(e)
                publish(ServerState.Failed(e), callback)
            }
        }
    }

    override fun stop(callback: (ServerState) -> Unit) {
        publish(ServerState.Stopping, callback)

        executor.submit {
            try {
                server?.stop(1000, 5000)
                server = null
                TrafficStore.shutdown()
                HistoryMetadataIndex.clear()
                api.logging().logToOutput("Stopped MCP server")
                publish(ServerState.Stopped, callback)
            } catch (e: Exception) {
                api.logging().logToError(e)
                publish(ServerState.Failed(e), callback)
            }
        }
    }

    override fun shutdown() {
        server?.stop(1000, 5000)
        server = null
        TrafficStore.shutdown()
        HistoryMetadataIndex.clear()

        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)
        RuntimeDiagnostics.update(ServerState.Stopped)
    }

    private fun publish(state: ServerState, callback: (ServerState) -> Unit) {
        RuntimeDiagnostics.update(state)
        callback(state)
    }

    private fun isValidOrigin(origin: String): Boolean {
        try {
            val url = URI(origin).toURL()
            val hostname = url.host.lowercase()

            val allowedHosts = setOf("localhost", "127.0.0.1")

            return hostname in allowedHosts
        } catch (_: Exception) {
            return false
        }
    }

    private fun isBrowserRequest(userAgent: String?): Boolean {
        if (userAgent == null) return false

        val userAgentLower = userAgent.lowercase()
        val browserIndicators = listOf(
            "mozilla/", "chrome/", "safari/", "webkit/", "gecko/", "firefox/", "edge/", "opera/", "browser"
        )

        return browserIndicators.any { userAgentLower.contains(it) }
    }

    private fun isValidHost(host: String, expectedPort: Int): Boolean {
        try {
            val parts = host.split(":")
            val hostname = parts[0].lowercase()
            val port = if (parts.size > 1) parts[1].toIntOrNull() else null

            val allowedHosts = setOf("localhost", "127.0.0.1")
            if (hostname !in allowedHosts) {
                return false
            }

            if (port != null && port != expectedPort) {
                return false
            }

            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun isValidReferer(referer: String): Boolean {
        try {
            val url = URI(referer).toURL()
            val hostname = url.host.lowercase()

            val allowedHosts = setOf("localhost", "127.0.0.1")
            return hostname in allowedHosts

        } catch (_: Exception) {
            return false
        }
    }
}
