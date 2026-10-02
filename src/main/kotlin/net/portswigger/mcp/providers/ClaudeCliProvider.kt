package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import net.portswigger.mcp.config.McpConfig
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Path

/** Prepare a Claude CLI command; the user chooses when to run it. */
class ClaudeCliProvider(
    private val logging: Logging,
    private val proxyJarManager: ProxyJarManager,
    private val copyToClipboard: (String) -> Unit = { command ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(command), null)
    }
) : Provider {
    override val name = "Claude CLI"
    override val installButtonText = "Copy Claude CLI command"
    override val confirmationText: String? = null

    override fun install(config: McpConfig): String {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val command = claudeInstallCommand(
            Path.of("claude"), proxyJarManager.getProxyJar(), config.host, config.port
        )
        copyToClipboard(terminalCommand(command, windows))
        logging.logToOutput("Copied Claude CLI setup command to clipboard")
        val terminal = if (windows) "PowerShell" else "your terminal (zsh/bash)"
        return "Command copied. Paste it into $terminal and press Enter to add or replace the user-level burp MCP entry. " +
            "Then restart Claude Code."
    }
}

internal fun claudeInstallCommand(claude: Path, proxy: Path, host: String, port: Int): List<String> {
    require(port in 1..65535) { "Invalid MCP server port" }
    val clientHost = when (host) {
        "0.0.0.0" -> "127.0.0.1"
        "::", "[::]" -> "::1"
        else -> host
    }
    val url = URI("http", null, clientHost, port, null, null, null).toASCIIString()
    return listOf(
        claude.toString(), "mcp", "add", "--scope", "user", "burp", "--", "java",
        "-jar", proxy.toString(), "--sse-url", url
    )
}
