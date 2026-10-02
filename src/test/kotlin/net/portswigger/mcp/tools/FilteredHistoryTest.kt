package net.portswigger.mcp.tools

import org.junit.jupiter.api.Test

class FilteredHistoryTest {
    @Test
    fun `filtered history regressions`() = FilteredHistoryChecks.runAll()
}
