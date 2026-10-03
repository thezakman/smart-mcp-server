package net.portswigger.mcp.tools

internal val SERVER_INSTRUCTIONS = """
    This server exposes Burp Suite evidence and actions.

    Start with compact index tools and fetch full messages only by their returned native ID or stable key.
    Prefer search_http_history, list_site_map, list_organizer_items, get_repeater_traffic and
    get_intruder_traffic. Use get_http_exchange for selected Proxy messages. Request only the fields and parts
    needed, keep count small, reuse nextCursor, and follow a message part's nextOffset when present.

    Captured request and response content is returned unchanged by default, including authentication data.
    Do not claim behavior that was not observed. Distinguish captured evidence, local tab creation and a request
    actually sent to a target. Preview mutations before sending them. Every send remains subject to Burp's
    per-target approval. Do not turn a single mutation into a batch or retry it automatically.

    Use compare_http_exchanges to analyze already captured responses without generating traffic. Preserve native
    Burp IDs in notes and results so findings remain traceable. Site Map keys are content-derived lookup keys.
""".trimIndent()
