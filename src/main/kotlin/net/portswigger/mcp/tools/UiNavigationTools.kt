package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType

@Serializable
internal data class SelectRepeaterTab(val tabId: String, val expectedTitle: String? = null)
@Serializable
internal data class SelectOrganizerItem(val id: Int)
@Serializable
internal enum class BurpTool { DASHBOARD, TARGET, PROXY, LOGGER, REPEATER, INTRUDER, SEQUENCER, DECODER, COMPARER, ORGANIZER, MCP }
@Serializable
internal data class SelectBurpTool(val tool: BurpTool)

internal fun Server.registerUiNavigationTools(api: MontoyaApi, config: McpConfig) {
    fun requireAccess(type: DataAccessType) {
        check(runBlocking { DataAccessSecurity.checkDataAccessPermission(type, config) }) { "Project data access denied by Burp Suite" }
    }
    val json = Json { encodeDefaults = true }
    val behavior = ToolBehavior(readOnly = false, destructive = false, idempotent = true, openWorld = false)
    mcpTool<SelectRepeaterTab>(
        "Select an existing Repeater tab by session tabId from list_repeater_tabs. Optional expectedTitle rejects renames. " +
            "Activates Repeater and rechecks selection on the next UI turn. No sends or message edits. Confirm displayed content before screenshot capture.", behavior
    ) {
        requireAccess(DataAccessType.HTTP_HISTORY)
        expectedTitle?.let { require(it.length <= 500) { "expectedTitle must be at most 500 characters" } }
        json.encodeToString(UiSelectionResult.serializer(), RepeaterUiInspector.selectTab(api, tabId, expectedTitle))
    }
    mcpTool<SelectOrganizerItem>(
        "Select and scroll to a native Organizer item ID in the current collection/Contents and filters. " +
            "Fails when absent, hidden or ambiguous; does not change collections or filters. No traffic or data edits. " +
            "Rechecks row selection; confirm displayed content before screenshot capture.", behavior
    ) {
        require(id >= 0) { "Use a native Organizer item ID" }
        requireAccess(DataAccessType.ORGANIZER)
        check(api.organizer().items().any { it.id() == id }) { "No Organizer item with ID $id" }
        json.encodeToString(UiSelectionResult.serializer(), BurpUiNavigator.selectOrganizerItem(api, id))
    }
    mcpTool<SelectBurpTool>(
        "Show one attached Burp suite tool tab by exact tool name. Does not select inner history rows, Repeater tabs, " +
            "collections or editor views. Rechecks visibility without sending traffic, starting scans or editing data. " +
            "Use select_repeater_tab/select_organizer_item for specific content, then the separate screenshot MCP.", behavior
    ) {
        requireAccess(if (tool == BurpTool.ORGANIZER) DataAccessType.ORGANIZER else DataAccessType.HTTP_HISTORY)
        json.encodeToString(UiSelectionResult.serializer(), BurpUiNavigator.selectTool(api, tool.name))
    }
}
