package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType

internal fun Server.registerHistoryResources(api: MontoyaApi, config: McpConfig) {
    addResourceTemplate(
        uriTemplate = "burp://proxy/{id}/{part}",
        name = "Burp Proxy HTTP message",
        description = "Read a complete raw request or response by native Burp Proxy history ID.",
        mimeType = "message/http"
    ) { request, parameters ->
        check(DataAccessSecurity.checkDataAccessPermission(DataAccessType.HTTP_HISTORY, config)) {
            "HTTP history access denied by Burp Suite"
        }
        val id = parameters["id"]?.toIntOrNull()
            ?: throw IllegalArgumentException("Resource ID must be a native numeric Burp history ID")
        val part = parameters["part"]?.lowercase()
            ?: throw IllegalArgumentException("Resource part must be request or response")
        require(part == "request" || part == "response") { "Resource part must be request or response" }
        val item = api.proxy().history().firstOrNull { it.id() == id }
            ?: error("No HTTP history item with Burp ID $id; it may have been removed")
        val text = when (part) {
            "request" -> item.finalRequest().toString()
            else -> item.response()?.toString() ?: error("HTTP history item $id has no response")
        }
        ReadResourceResult(
            contents = listOf(TextResourceContents(text = text, uri = request.params.uri, mimeType = "message/http"))
        )
    }
}
