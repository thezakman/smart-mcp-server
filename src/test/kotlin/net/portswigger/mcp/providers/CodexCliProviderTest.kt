package net.portswigger.mcp.providers

import org.junit.jupiter.api.Test

class CodexCliProviderTest {
    @Test
    fun `Codex installer regressions`() = CodexCliChecks.runAll()
}
