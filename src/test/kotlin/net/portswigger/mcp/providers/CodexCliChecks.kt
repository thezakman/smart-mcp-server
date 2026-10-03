package net.portswigger.mcp.providers

import java.nio.file.Path
import java.util.concurrent.TimeUnit

object CodexCliChecks {
    @JvmStatic
    fun main(args: Array<String>) = runAll()

    fun runAll() {
        var count = 0
        fun scenario(name: String, block: () -> Unit) {
            block()
            count++
            println("PASS $name")
        }
        scenario("Codex command uses direct Streamable HTTP transport") {
            val command = codexInstallCommand(Path.of("codex"), "127.0.0.1", 9876)
            check(command == listOf("codex", "mcp", "add", "burp", "--url", "http://127.0.0.1:9876/mcp"))
        }
        scenario("IPv6 URLs and wildcard addresses become valid client endpoints") {
            fun url(host: String) = codexInstallCommand(Path.of("codex"), host, 9876).last()
            check(url("::1") == "http://[::1]:9876/mcp")
            check(url("0.0.0.0") == "http://127.0.0.1:9876/mcp")
            check(url("::") == "http://[::1]:9876/mcp")
            check(runCatching { codexInstallCommand(Path.of("codex"), "localhost", 0) }.isFailure)
        }
        scenario("terminal command has no trailing newline or unnecessary quoting") {
            check(terminalCommand(listOf("codex", "mcp", "add", "burp")) == "codex mcp add burp")
            check(terminalCommand(listOf("")) == "''")
        }
        scenario("PowerShell quotes spaces and apostrophes literally") {
            check(terminalCommand(listOf("C:\\a path\\java.exe", "a'b"), true) == "'C:\\a path\\java.exe' 'a''b'")
        }
        scenario("line breaks and NUL are rejected") {
            for (control in listOf('\n', '\r', '\u0000')) {
                check(runCatching { terminalCommand(listOf("a" + control + "b")) }.isFailure)
            }
        }
        if (!System.getProperty("os.name").startsWith("Windows")) {
            scenario("POSIX shell round trip preserves literal metacharacters without expansion") {
                val args = listOf("codex", "/path with spaces/java", "a'b", "\$HOME", "\$(echo expanded)", "`echo expanded`", "a;b", "", "http://[::1]:9876")
                val command = "set -- " + terminalCommand(args) + "; printf '%s\\0' \"\$@\""
                val process = ProcessBuilder("/bin/sh", "-c", command).start()
                check(process.waitFor(5, TimeUnit.SECONDS))
                check(process.exitValue() == 0)
                val actual = process.inputStream.readAllBytes().toString(Charsets.UTF_8).split('\u0000').dropLast(1)
                check(actual == args) { "Shell changed command arguments" }
            }
        }
        println("Codex clipboard command: $count scenarios passed")
    }
}
