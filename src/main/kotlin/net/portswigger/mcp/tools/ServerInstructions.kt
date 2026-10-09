package net.portswigger.mcp.tools

internal val SERVER_INSTRUCTIONS = """
    This server exposes Burp Suite evidence and actions.

    Start with compact index tools and fetch full messages only by their returned native ID or stable key.
    Prefer search_http_history or get_proxy_http_history_summary, then list_site_map, list_organizer_items,
    get_repeater_traffic and get_intruder_traffic. Use get_proxy_http_history_regex for bounded content search
    and get_http_exchange for selected Proxy messages. Request only the fields and parts
    needed, keep count small, reuse nextCursor, and follow a message part's nextOffset when present.

    Captured request and response content is returned unchanged by default, including authentication data.
    Do not claim behavior that was not observed. Distinguish captured evidence, local tab creation and a request
    actually sent to a target. The Read-only investigation profile omits sending and mutation tools. In other
    profiles, use the sending tools carefully. Preview mutations before sending them. Every send remains subject to Burp's per-target approval and
    the configured concurrency limit. Do not turn a single mutation into a batch or retry it automatically.

    Direct HTTP sends return a persistent in-session exchangeId and messageId. Retrieve every response chunk with
    get_captured_exchange_by_id, then save it to Organizer when it should become project evidence.

    For WebSocket interaction, open one reviewed upgrade request with open_web_socket, use the returned sessionId,
    send only one reviewed frame at a time, and poll get_web_socket_session_messages with afterMessageId. Replay a
    captured frame only by its native Burp ID and explicit TEXT/BINARY type. Close sessions when finished. WebSocket
    handshakes and frames remain subject to target approval, data access approval where applicable, and concurrency limits.

    Use compare_http_exchanges for pairs and compare_auth_controls for anonymous, invalid-token and valid-token
    controls without generating traffic. Preserve native Burp IDs and MCP exchange IDs in notes and results so
    findings remain traceable. Site Map keys are content-derived lookup keys.

    Collaborator payloads are correlated with later MCP, Repeater or Intruder requests that contain them. Poll with
    the payloadId whenever possible so the result includes the origin exchange and Trace ID deterministically.
""".trimIndent()
