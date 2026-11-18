package net.portswigger.mcp.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolAuditLogTest {
    @Test
    fun `detailed entries are bounded and mark truncated arguments`() {
        ToolAuditLog.clear()
        ToolAuditLog.configureDetailedLogging { true }
        try {
            ToolAuditLog.add("fixture", false, 1, IllegalArgumentException("x".repeat(5000)),
                arguments = buildJsonObject { put("payload", "y".repeat(20_000)) })
            val event = ToolAuditLog.snapshot(false).single()
            assertEquals(16_000, event.arguments!!.length)
            assertTrue(event.argumentsTruncated)
            assertTrue(event.error!!.length < 2100)
        } finally {
            ToolAuditLog.clear()
            ToolAuditLog.configureDetailedLogging { false }
        }
    }
}
