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
    val error: String? = null,
    val resultChars: Int = 0
)

@Serializable
data class ToolMetricSummary(
    val tool: String,
    val calls: Int,
    val successes: Int,
    val failures: Int,
    val totalDurationMillis: Long,
    val totalResultChars: Long,
    val maxResultChars: Int
)

object ToolAuditLog {
    private val events = ArrayDeque<ToolAuditEvent>()

    @Synchronized
    fun add(tool: String, succeeded: Boolean, durationMillis: Long, error: String? = null, resultChars: Int = 0) {
        events.addLast(ToolAuditEvent(Instant.now().toString(), tool, succeeded, durationMillis,
            error?.take(1000), resultChars.coerceAtLeast(0)))
        while (events.size > MAX_AUDIT_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun snapshot(newestFirst: Boolean): List<ToolAuditEvent> =
        ArrayList(events).let { if (newestFirst) it.asReversed() else it }

    @Synchronized
    fun metrics(): List<ToolMetricSummary> = events.groupBy { it.tool }.map { (tool, toolEvents) ->
        ToolMetricSummary(
            tool = tool,
            calls = toolEvents.size,
            successes = toolEvents.count { it.succeeded },
            failures = toolEvents.count { !it.succeeded },
            totalDurationMillis = toolEvents.sumOf { it.durationMillis },
            totalResultChars = toolEvents.sumOf { it.resultChars.toLong() },
            maxResultChars = toolEvents.maxOfOrNull { it.resultChars } ?: 0
        )
    }.sortedByDescending { it.totalResultChars }

    @Synchronized
    fun clear() = events.clear()
}
