package net.portswigger.mcp.tools

import org.junit.jupiter.api.Test

class HistoryTriageTest {
    @Test
    fun `read-only triage regressions`() = HistoryTriageChecks.runAll()
}
