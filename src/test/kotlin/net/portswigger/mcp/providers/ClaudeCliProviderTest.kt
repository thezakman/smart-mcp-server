package net.portswigger.mcp.providers

import org.junit.jupiter.api.Test
import java.nio.file.Path

class ClaudeCliProviderTest {
    @Test
    fun `Claude installer uses user scope and preserves proxy paths`() {
        val command = claudeInstallCommand(
            Path.of("claude"), Path.of("/a path/proxy;literal.jar"), "127.0.0.1", 9876
        )

        check(command == listOf(
            "claude", "mcp", "add", "--scope", "user", "burp", "--", "java",
            "-jar", "/a path/proxy;literal.jar", "--sse-url", "http://127.0.0.1:9876"
        ))
    }

    @Test
    fun `Claude installer normalizes wildcard and IPv6 addresses`() {
        fun url(host: String) = claudeInstallCommand(
            Path.of("claude"), Path.of("proxy.jar"), host, 9876
        ).last()

        check(url("0.0.0.0") == "http://127.0.0.1:9876")
        check(url("::") == "http://[::1]:9876")
        check(url("::1") == "http://[::1]:9876")
        check(runCatching {
            claudeInstallCommand(Path.of("claude"), Path.of("proxy.jar"), "localhost", 0)
        }.isFailure)
    }
}
