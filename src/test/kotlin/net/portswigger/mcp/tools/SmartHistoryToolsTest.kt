package net.portswigger.mcp.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmartHistoryToolsTest {
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
