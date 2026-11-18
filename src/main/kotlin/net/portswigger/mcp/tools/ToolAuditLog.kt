package net.portswigger.mcp.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

private const val MAX_AUDIT_EVENTS = 500

@Serializable
data class ToolAuditEvent(
    val timestamp: String,
    val tool: String,
    val succeeded: Boolean,
    val durationMillis: Long,
    val error: String? = null,
    val resultChars: Int = 0,
    val arguments: String? = null,
    val argumentsTruncated: Boolean = false
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
    @Volatile private var detailedLogging: () -> Boolean = { false }

    fun configureDetailedLogging(enabled: () -> Boolean) { detailedLogging = enabled }


    @Synchronized
    fun add(tool: String, succeeded: Boolean, durationMillis: Long, error: Exception? = null,
            resultChars: Int = 0, arguments: JsonObject? = null) {
        val detailed = detailedLogging()
        val rawArguments = if (detailed) arguments?.toString() else null
        val failure = error?.let {
            val category = "${toolErrorCode(it)} (${it.javaClass.simpleName})"
            if (detailed) "$category: ${it.message.orEmpty().take(2000)}" else category
        }
        events.addLast(ToolAuditEvent(Instant.now().toString(), tool, succeeded, durationMillis,
            failure, resultChars.coerceAtLeast(0), rawArguments?.take(16_000),
            argumentsTruncated = rawArguments != null && rawArguments.length > 16_000))
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
