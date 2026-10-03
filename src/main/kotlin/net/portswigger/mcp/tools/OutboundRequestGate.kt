package net.portswigger.mcp.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/** Bounds target-bound requests independently from the MCP transport's own concurrency. */
internal object OutboundRequestGate {
    private data class State(val limit: Int, val semaphore: Semaphore)

    @Volatile private var state = State(2, Semaphore(2, true))
    private val active = AtomicInteger(0)
    private val rejected = AtomicInteger(0)

    @Synchronized
    fun configure(limit: Int) {
        val bounded = limit.coerceIn(1, 16)
        state = State(bounded, Semaphore(bounded, true))
        active.set(0)
        rejected.set(0)
    }

    fun <T> withPermit(block: () -> T): T {
        val current = state
        if (!current.semaphore.tryAcquire()) {
            rejected.incrementAndGet()
            throw ToolBusyException(
                "Outbound request concurrency limit (${current.limit}) reached; retry after an active request finishes"
            )
        }
        active.incrementAndGet()
        return try {
            block()
        } finally {
            active.decrementAndGet()
            current.semaphore.release()
        }
    }

    fun metrics() = buildJsonObject {
        put("limit", state.limit)
        put("active", active.get())
        put("rejected", rejected.get())
    }
}
