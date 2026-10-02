# Implementation notes

## Project lineage

This fork extends PortSwigger's MCP server while keeping the existing HTTP, configuration, Scanner,
Collaborator, editor, Proxy and Organizer tools compatible. The additional traffic workflow reviews ideas from:

- PortSwigger MCP Server PRs [#83](https://github.com/PortSwigger/mcp-server/pull/83) and
  [#85](https://github.com/PortSwigger/mcp-server/pull/85)
- [geozin/burp-mcp-colorstrike](https://github.com/geozin/burp-mcp-colorstrike)
- [r3verii/mcp-server customizations](https://github.com/r3verii/mcp-server/blob/main/CUSTOMIZATIONS.md)

## Client installation

The Codex installation action extracts the packaged proxy and copies a shell-safe command using `java` from the
terminal `PATH`. It does not run Codex or edit `config.toml` from Burp's GUI process. This keeps `PATH`,
`CODEX_HOME` and shell behavior under the user's terminal environment.

The extension remains an SSE server. Codex and other stdio-only clients run the embedded
`mcp-proxy-all.jar`, which connects to the configured local SSE endpoint.

## Evidence model

- Proxy and WebSocket tools retain Montoya's native IDs.
- Site Map has no native entry ID, so entries use a SHA-256 key derived from request and service content.
- Compact index tools omit bodies. Detail tools retrieve selected items in bounded Unicode-safe chunks.
- Proxy pagination returns `snapshotMaxId`; reusing it excludes newer arrivals while a client pages.
- Request, response, cookie and authentication content remains unchanged by default. Optional masking is an
  explicit argument only on tools that advertise it.
- Regex work has expression, input, read-count and elapsed-time limits.

## Repeater and Intruder capture

Montoya cannot enumerate existing Repeater tabs or completed Intruder attacks. One HTTP handler records exchanges
from those tools after the MCP server starts. Each tool has an independent 1,000-entry in-memory ring buffer.
Registrations and buffers are cleared when the server or extension stops.

## Mutation workflow

Mutations are explicit and single-request only:

1. `preview_request_mutation` reconstructs the request and returns its SHA-256.
2. The caller reviews the complete bounded request.
3. `send_mutated_request` reconstructs the same mutation and requires the preview hash.
4. A mismatch fails before transmission; a match still passes through target approval.

Supported locations are method, path, header, complete body, query parameter, body parameter, cookie and JSON
parameter. There is no automatic payload batch, retry loop or offensive system prompt.

## Diagnostics and audit

`get_mcp_diagnostics` reports the configured endpoint, server state, Burp/JVM information, embedded-proxy presence
and capture counts. `get_mcp_action_log` reads a 500-event in-memory ring containing tool name, timestamp,
duration, success and bounded error text. Arguments and results are never copied into this log.

## UI and packaging

UI colors follow Burp/Swing theme values. Alternating target rows are derived from the active list background with
a small foreground tint, avoiding white stripes in dark mode.

Gradle always emits `build/libs/burp-mcp-all.jar`. The stdio proxy is a declared archive input; the build does not
rewrite a ZIP after packaging. Archives preserve reproducible ordering and timestamps.

## Verification

Run with a complete Java 21 JDK:

```sh
./gradlew test embedProxyJar
```

The current regression suite contains 109 tests with MCP SSE and packaged stdio-proxy end-to-end coverage.
The generated JAR passes ZIP integrity checks and contains one embedded proxy matching `libs/mcp-proxy-all.jar`.
