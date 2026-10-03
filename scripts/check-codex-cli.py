#!/usr/bin/env python3
"""Exercise the installed Codex CLI using a temporary config; never modifies the real user config.

Requires Python 3.11+ and codex on PATH. Does not start an MCP server or send target traffic.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import tomllib


def main():
    with tempfile.TemporaryDirectory(prefix="burp-codex-cli-") as directory:
        config = Path(directory) / "config.toml"
        config.write_text(
            '# retain this comment\nmodel = "gpt-5"\n'
            '[mcp_servers.local-fixture]\ncommand = "echo"\nargs = ["ok"]\n',
            encoding="utf-8",
        )
        environment = dict(os.environ, CODEX_HOME=directory)
        command = ["codex", "mcp", "add", "burp", "--url", "http://127.0.0.1:9876/mcp"]
        for _ in range(2):
            subprocess.run(command, env=environment, capture_output=True, check=True, timeout=30)
            parsed = tomllib.loads(config.read_text(encoding="utf-8"))
            assert parsed["model"] == "gpt-5"
            assert parsed["mcp_servers"]["local-fixture"]["command"] == "echo"
            assert parsed["mcp_servers"]["burp"]["url"] == command[-1]
            assert "# retain this comment" in config.read_text(encoding="utf-8")
        result = subprocess.run(
            ["codex", "mcp", "get", "burp", "--json"], env=environment,
            capture_output=True, text=True, check=True, timeout=30,
        )
        server = json.loads(result.stdout)
        assert server["name"] == "burp"
        print("PASS: isolated direct HTTP install, repeat install, unrelated config/comments, CLI readback")


if __name__ == "__main__":
    main()
