# Implementation notes

## Project lineage

This fork extends PortSwigger's MCP server while keeping the existing HTTP, configuration, Scanner,
Collaborator, editor, Proxy and Organizer tools compatible. The additional traffic workflow reviews ideas from:

- PortSwigger MCP Server PRs [#83](https://github.com/PortSwigger/mcp-server/pull/83) and
  [#85](https://github.com/PortSwigger/mcp-server/pull/85)
- [geozin/burp-mcp-colorstrike](https://github.com/geozin/burp-mcp-colorstrike)
- [r3verii/mcp-server customizations](https://github.com/r3verii/mcp-server/blob/main/CUSTOMIZATIONS.md)

## Client installation

The Codex installation action copies a shell-safe `codex mcp add --url` command for the direct Streamable HTTP
endpoint. It does not run Codex or edit `config.toml` from Burp's GUI process, so `CODEX_HOME` and shell behavior
remain under the user's terminal environment.

The extension serves Streamable HTTP at `/mcp` and retains the original SSE endpoint. Stdio-only clients run the
embedded `mcp-proxy-all.jar`, which connects to the configured local SSE endpoint.

## Evidence model

- Proxy and WebSocket tools retain Montoya's native IDs.
- Site Map has no native entry ID, so entries use a SHA-256 key derived from request and service content.
- Compact index tools omit bodies. Detail tools retrieve selected items in bounded Unicode-safe chunks.
- Structured Proxy search caches extracted metadata for existing native IDs and bounds concurrent heavy searches.
- Complete Proxy request/response messages are also available through `burp://proxy/{id}/{part}` resources.
- Proxy pagination returns `snapshotMaxId`; reusing it excludes newer arrivals while a client pages.
- Request, response, cookie and authentication content remains unchanged by default. Optional masking is an
  explicit argument only on tools that advertise it.
- Regex work has expression, input, read-count and elapsed-time limits.

## Repeater and Intruder capture

Montoya 2026.7 cannot enumerate existing Repeater tabs. The extension uses the public `SwingUtils.suiteFrame()`
entry point and read-only traversal of the Swing component tree to find Repeater's `JTabbedPane`. This exposes live
manual and MCP-created titles without reflecting into Burp's obfuscated implementation classes. The layout is not a
Montoya compatibility contract, so discovery fails closed with an explicit unavailable result if Burp changes it.

One HTTP handler records Repeater and Intruder exchanges after the MCP server starts. At the start of an ordinary
Repeater send, the selected Swing title is stored under the Montoya message ID and attached to the matching response.
Group sends do not expose the originating member tab, so that case remains best-effort. Each tool has an independent
1,000-entry in-memory ring buffer. Registrations, title associations and buffers are cleared when the server or
extension stops.

## Mutation workflow

Mutations are explicit and single-request only:

1. `preview_request_mutation` reconstructs the request and returns its SHA-256.
2. The caller reviews the complete bounded request.
3. `send_mutated_request` reconstructs the same mutation and requires the preview hash.
4. A mismatch fails before transmission; a match still passes through target approval.

Supported locations are method, path, header, complete body, query parameter, body parameter, cookie and JSON
parameter. There is no automatic payload batch, retry loop or offensive system prompt.

## Diagnostics and audit

`get_mcp_diagnostics` reports both endpoints, server state, tool/profile/schema size, indexed-history reuse,
per-tool duration/output totals, Burp/JVM information, embedded-proxy presence and capture counts.
`get_mcp_action_log` reads a 500-event in-memory ring containing tool name, timestamp, duration, success,
returned character count and bounded error text. Arguments and result bodies are never copied into this log.

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

The regression suite covers MCP SSE, direct Streamable HTTP initialization and packaged stdio-proxy end-to-end
operation. The generated JAR passes ZIP integrity checks and contains one embedded proxy matching
`libs/mcp-proxy-all.jar`.
