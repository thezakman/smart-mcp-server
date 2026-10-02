package net.portswigger.mcp.tools

import kotlinx.serialization.Serializable
import java.time.Instant

private const val MAX_AUDIT_EVENTS = 500

@Serializable
data class ToolAuditEvent(
    val timestamp: String,
    val tool: String,
    val succeeded: Boolean,
    val durationMillis: Long,
    val error: String? = null
)

object ToolAuditLog {
    private val events = ArrayDeque<ToolAuditEvent>()

    @Synchronized
    fun add(tool: String, succeeded: Boolean, durationMillis: Long, error: String? = null) {
        events.addLast(ToolAuditEvent(Instant.now().toString(), tool, succeeded, durationMillis, error?.take(1000)))
        while (events.size > MAX_AUDIT_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun snapshot(newestFirst: Boolean): List<ToolAuditEvent> =
        ArrayList(events).let { if (newestFirst) it.asReversed() else it }

    @Synchronized
    fun clear() = events.clear()
}
