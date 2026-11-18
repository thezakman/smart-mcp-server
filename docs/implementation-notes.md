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

## Local UI navigation (1.13.0)

`select_repeater_tab`, `select_organizer_item` and `select_burp_tool` change only local selection. They are
registered in Core/Full and excluded from Read-only. UI writes run through one serialized EDT dispatcher;
verification happens on the next EDT turn, and the waiting MCP call has a 1.5-second timeout. A timed-out
queued operation is cancelled before selection; an operation already executing may have changed the UI.
Unload invalidates pending operations. Navigation is never invoked from editor-provider callbacks.

Repeater tabs are resolved by session ID and component identity, with optional exact-title checking. Explicit
navigation stops the startup binding walk. Organizer rows are resolved by native ID through the current sorted
and filtered table view; ambiguous or hidden entries fail without changing collections or filters. Traversal and
row scans are bounded, and unsupported Swing layouts fail explicitly.

The response confirms selection, not rendered request/response content or foreground focus. Capture stays in the
separate screenshot MCP after checking the intended Burp window and content. This release has automated Swing
fixture and MCP protocol tests; live navigation in the installed Burp instance still requires reloading the new JAR.

## Managed WebSockets (1.14.0)

Montoya 2026.7's `WebSockets.createWebSocket(HttpRequest)` and `ExtensionWebSocket` APIs provide the complete
managed lifecycle. `open_web_socket` constructs one upgrade request for an explicit service after target approval.
The returned opaque session ID is required for text/binary sends, stable forward polling and close. A bounded handler
records messages received from the application; sends are recorded in the same ordered session timeline.

`replay_web_socket_message` resolves exactly one native Proxy WebSocket history ID after project-data approval and
sends its payload once after target approval. The frame type is caller-supplied because Proxy history exposes payload
bytes and direction but not the original text/binary type. Server-to-client sources require an explicit override.

Sessions and messages are memory-only. Active/retained session, per-session message, stored-payload and outbound-frame
limits prevent an unbounded queue. Stopping the MCP closes sockets, deregisters handlers and clears the store. The
Read-only profile exposes session metadata/messages only and cannot keep sessions across a server restart.

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

## Screenshot companion handoff (1.14.1)

A read-only `get_evidence_capture_guide` tool is registered in all catalogs. Initialization instructions route screenshot requests to it. Navigation responses include `nextStep`, while preserving `contentVerified=false`: no rendering or capture claim is added. This is client-orchestrated composition, with no new HTTP client, process, filesystem capture or platform dependency in Burp. The companion owns the interactive capture contract. Reload the rebuilt extension to expose the new guide; reconnect clients to refresh discovery.

The companion is strictly optional. No connection/configuration setting or runtime dependency is introduced; standalone Burp operation and all catalogs work without a screenshot service. Only explicit image requests activate the guidance.

The guide includes a versioned JSON contract and recovery paths for stale windows/previews, unavailable images, cancellation/timeouts and selection-only results. Client timeouts must be reconciled before retrying a capture. MCP protocol tests parse the complete guide in Core, Full and Read-only; a serialization regression test checks native tab identity and the explicit unverified-content flag. These checks do not replace live preview/PNG inspection.

Organizer navigation recognizes both Method/URL and Method/Host/Path tables, with a unique native # column. It retains the ID column identity across view reordering, rejects a replaced table model or removed ID column, and rechecks ID uniqueness before confirming selection. These are Swing fixture checks; installed Burp behavior still needs validation after reloading the rebuilt JAR.

Before reporting Organizer navigation success, the selected ID cell must intersect the visible table area. A row scrolled outside the viewport or a table entering cell-edit mode fails verification without committing or cancelling edits. This verifies Swing geometry and selection only; preview pixels remain mandatory.

Navigation releases its busy state before publishing success or failure, including errors transferred from the EDT. Organizer lookup stops after two matches prove ambiguity and caps ID reads at 10,000 across all candidate tables, rather than multiplying that work per table.

## Core reliability: numeric arguments, concurrency and WebSocket cleanup

Integral MCP arguments use exact decimal conversion. Large native IDs retain their value; fractional and out-of-range values reach the serializer unchanged and are rejected rather than rounded or saturated.

Outbound concurrency reconfiguration retains outstanding permits. Lowering the limit prevents new sends until existing work drains; diagnostics continue counting active requests and cannot become negative when older operations finish. Network operations run outside the accounting lock.

WebSocket cleanup detaches its session snapshot before closing sockets and deregistering handlers. A handler registered after its session was removed is deregistered immediately, and queued inbound callbacks for removed/closed sessions are ignored. Cleanup of the detached snapshot does not erase newly registered sessions. These regressions are exercised locally with mocked sockets, without target traffic.
