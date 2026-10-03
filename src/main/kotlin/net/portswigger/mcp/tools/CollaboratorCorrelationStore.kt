package net.portswigger.mcp.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CollaboratorCorrelation(
    val payloadId: String,
    val payload: String,
    val generatedAt: String,
    val customData: String? = null,
    val originSource: String? = null,
    val originId: Int? = null,
    val originExchangeId: String? = null,
    val traceId: String? = null
)

/** In-session correlation between Collaborator payloads, originating exchanges and received interactions. */
internal object CollaboratorCorrelationStore {
    private val records = ConcurrentHashMap<String, CollaboratorCorrelation>()

    fun register(
        payloadId: String,
        payload: String,
        customData: String?,
        originSource: String?,
        originId: Int?,
        originExchangeId: String?,
        traceId: String?
    ): CollaboratorCorrelation {
        val record = CollaboratorCorrelation(
            payloadId = payloadId,
            payload = payload,
            generatedAt = Instant.now().toString(),
            customData = customData,
            originSource = originSource,
            originId = originId,
            originExchangeId = originExchangeId,
            traceId = traceId
        )
        records[payloadId] = record
        return record
    }

    fun linkRequest(exchange: CapturedExchange) {
        val raw = exchange.request
        val traceId = extractTraceId(raw)
        records.entries.forEach { (payloadId, current) ->
            if (raw.contains(current.payload, ignoreCase = true) || raw.contains(payloadId, ignoreCase = true)) {
                records[payloadId] = current.copy(
                    originSource = exchange.tool,
                    originId = exchange.messageId,
                    originExchangeId = exchange.exchangeId,
                    traceId = traceId ?: current.traceId
                )
            }
        }
    }

    fun correlate(
        requestedPayloadId: String?, interactionId: String, serializedInteraction: String, customData: String?
    ): CollaboratorCorrelation? {
        requestedPayloadId?.let { records[it]?.let { record -> return record } }
        records[interactionId]?.let { return it }
        return records.values.firstOrNull { record ->
            interactionId.contains(record.payloadId, ignoreCase = true) ||
                serializedInteraction.contains(record.payload, ignoreCase = true) ||
                (!customData.isNullOrBlank() && customData == record.customData)
        }
    }

    fun correlatedInteraction(
        interaction: kotlinx.serialization.json.JsonElement,
        requestedPayloadId: String?,
        interactionId: String,
        customData: String?
    ) = buildJsonObject {
        put("interaction", interaction)
        val record = correlate(requestedPayloadId, interactionId, interaction.toString(), customData)
        put("correlation", record?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
        put("correlated", record != null)
    }

    fun metrics() = buildJsonObject {
        put("payloads", records.size)
        put("linkedOrigins", records.values.count { it.originId != null || it.originExchangeId != null })
        put("withTraceId", records.values.count { !it.traceId.isNullOrBlank() })
    }

    fun clear() = records.clear()
}

private val TRACE_HEADER = Regex(
    "(?im)^(?:traceparent|tracestate|x-request-id|x-correlation-id|x-trace-id|request-id)\\s*:\\s*([^\\r\\n]+)"
)

internal fun extractTraceId(rawRequest: String): String? =
    TRACE_HEADER.find(rawRequest)?.groupValues?.getOrNull(1)?.trim()?.take(1024)
