package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import net.portswigger.mcp.config.McpConfig
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Path

/** Prepare a terminal command; the user runs it in their own shell environment. */
class CodexCliProvider(
    private val logging: Logging,
    private val copyToClipboard: (String) -> Unit = { command ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(command), null)
    }
) : Provider {
    override val name = "Codex CLI"
    override val installButtonText = "Copy Codex CLI command"
    override val confirmationText: String? = null

    override fun install(config: McpConfig): String {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val command = codexInstallCommand(Path.of("codex"), config.host, config.port)
        copyToClipboard(terminalCommand(command, windows))
        logging.logToOutput("Copied Codex CLI setup command to clipboard")
        val terminal = if (windows) "PowerShell" else "your terminal (zsh/bash)"
        return "Command copied. Paste it into $terminal and press Enter to add or replace the burp MCP entry. " +
            "Then restart your Codex session."
    }
}

internal fun codexInstallCommand(codex: Path, host: String, port: Int): List<String> {
    require(port in 1..65535) { "Invalid MCP server port" }
    // A wildcard is a bind address, not an address a client should connect to.
    val clientHost = when (host) {
        "0.0.0.0" -> "127.0.0.1"
        "::", "[::]" -> "::1"
        else -> host
    }
    val url = URI("http", null, clientHost, port, "/mcp", null, null).toASCIIString()
    return listOf(codex.toString(), "mcp", "add", "burp", "--url", url)
}

/** Quote each argument for POSIX shells or PowerShell, without a trailing newline. */
internal fun terminalCommand(arguments: List<String>, powershell: Boolean = false): String =
    arguments.joinToString(" ") { argument ->
        require(argument.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "Cannot copy a single-line command containing line breaks or NUL characters"
        }
        when {
            argument.isNotEmpty() && argument.all { it.isLetterOrDigit() && it.code < 128 || it in "_./:=@%-" } -> argument
            powershell -> "'" + argument.replace("'", "''") + "'"
            else -> "'" + argument.replace("'", "'\"'\"'") + "'"
        }
    }
