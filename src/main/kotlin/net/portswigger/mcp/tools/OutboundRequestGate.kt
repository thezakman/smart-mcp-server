package net.portswigger.mcp.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounds target-bound requests independently from the MCP transport's own concurrency. */
internal object OutboundRequestGate {
    private var limit = 2
    private var active = 0
    private var rejected = 0L

    @Synchronized
    fun configure(limit: Int) {
        this.limit = limit.coerceIn(1, 16)
        // Existing requests still own their permits, even during a restart or limit change.
        rejected = 0
    }

    @Synchronized
    private fun acquire() {
        if (active >= limit) {
            rejected++
            throw ToolBusyException(
                "Outbound request concurrency limit ($limit) reached; retry after an active request finishes"
            )
        }
        active++
    }

    @Synchronized
    private fun release() { active-- }

    fun <T> withPermit(block: () -> T): T {
        acquire()
        return try {
            block()
        } finally {
            release()
        }
    }

    @Synchronized
    fun metrics() = buildJsonObject {
        put("limit", limit)
        put("active", active)
        put("rejected", rejected)
    }
}
