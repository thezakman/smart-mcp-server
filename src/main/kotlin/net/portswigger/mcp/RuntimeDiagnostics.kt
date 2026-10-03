package net.portswigger.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.tools.HistoryMetadataIndex
import net.portswigger.mcp.tools.ToolAuditLog
import java.time.Instant

object RuntimeDiagnostics {
    @Volatile private var state: String = "STOPPED"
    @Volatile private var changedAt: String = Instant.now().toString()
    @Volatile private var startedAt: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var toolCount: Int = 0
    @Volatile private var toolSchemaChars: Int = 0

    fun updateCatalog(count: Int, schemaChars: Int) {
        toolCount = count
        toolSchemaChars = schemaChars
    }

    fun update(serverState: ServerState) {
        state = when (serverState) {
            ServerState.Starting -> "STARTING"
            ServerState.Running -> "RUNNING"
            ServerState.Stopping -> "STOPPING"
            ServerState.Stopped -> "STOPPED"
            is ServerState.Failed -> "FAILED"
        }
        changedAt = Instant.now().toString()
        when (serverState) {
            ServerState.Running -> {
                startedAt = changedAt
                lastError = null
            }
            is ServerState.Failed -> lastError = buildErrorChain(serverState.exception)
            else -> Unit
        }
    }

    fun snapshot(config: McpConfig) = buildJsonObject {
        put("state", state)
        put("legacySseEndpoint", "http://${config.host}:${config.port}")
        put("streamableHttpEndpoint", "http://${config.host}:${config.port}/mcp")
        put("changedAt", changedAt)
        put("startedAt", startedAt?.let(::JsonPrimitive) ?: JsonNull)
        put("lastError", lastError?.let(::JsonPrimitive) ?: JsonNull)
        put("requestApprovalRequired", config.requireHttpRequestApproval)
        put("dataAccessApprovalRequired", config.requireDataAccessApproval)
        put("toolProfile", config.toolProfile.name)
        put("toolCount", toolCount)
        put("toolSchemaChars", toolSchemaChars)
        put("historyIndex", HistoryMetadataIndex.metrics())
        putJsonArray("toolMetrics") {
            ToolAuditLog.metrics().forEach { add(Json.encodeToJsonElement(net.portswigger.mcp.tools.ToolMetricSummary.serializer(), it)) }
        }
    }

    private fun buildErrorChain(error: Throwable): String = generateSequence(error) { it.cause }
        .take(8)
        .joinToString(" -> ") { cause ->
            cause::class.simpleName + (cause.message?.let { ": $it" } ?: "")
        }
}
