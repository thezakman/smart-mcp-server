package net.portswigger.mcp.tools

import burp.api.montoya.core.HighlightColor
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.util.Locale
import java.util.concurrent.Semaphore

internal data class IndexedHttpHistory(
    val id: Int,
    val item: ProxyHttpRequestResponse,
    val color: HighlightColor,
    val url: String,
    val host: String,
    val path: String,
    val method: String,
    val statusCode: Int?,
    val mimeType: String?,
    val hasResponse: Boolean,
    val hasParameters: Boolean,
    val requestBytes: Int,
    val responseBytes: Int,
    val responseStartMillis: Long?,
    val headerNames: Set<String>,
    val headerValues: String,
    val traceIds: Set<String>
)

/** Caches metadata extraction while always retaining the newest Montoya wrapper for each native ID. */
internal object HistoryMetadataIndex {
    private val entries = LinkedHashMap<Int, IndexedHttpHistory>()
    private var refreshCount = 0L
    private var rebuiltEntries = 0L
    private var metadataCacheHits = 0L

    @Synchronized
    fun refresh(history: List<ProxyHttpRequestResponse>): List<IndexedHttpHistory> {
        refreshCount++
        val currentIds = HashSet<Int>(history.size)
        val snapshot = ArrayList<IndexedHttpHistory>(history.size)
        for (item in history) {
            val id = item.id()
            currentIds += id
            val color = item.annotations().highlightColor() ?: HighlightColor.NONE
            val hasResponse = item.response() != null
            val cached = entries[id]
            val indexed = if (cached != null && cached.color == color && cached.hasResponse == hasResponse) {
                metadataCacheHits++
                cached.copy(item = item)
            } else {
                rebuiltEntries++
                index(item, color)
            }
            entries[id] = indexed
            snapshot += indexed
        }
        entries.keys.retainAll(currentIds)
        return snapshot
    }

    @Synchronized
    fun metrics() = buildJsonObject {
        put("entries", entries.size)
        put("refreshes", refreshCount)
        put("rebuiltEntries", rebuiltEntries)
        put("metadataCacheHits", metadataCacheHits)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        refreshCount = 0
        rebuiltEntries = 0
        metadataCacheHits = 0
    }

    private fun index(item: ProxyHttpRequestResponse, color: HighlightColor): IndexedHttpHistory {
        val request = item.finalRequest()
        val response = item.response()
        val url = safeIndex { request.url() }.orEmpty()
        val headers = safeIndex { request.headers() }.orEmpty() + safeIndex { response?.headers() }.orEmpty()
        val headerNames = headers.mapNotNull { safeIndex { it.name().lowercase(Locale.ROOT) } }.toSet()
        val headerValues = headers.asSequence().mapNotNull { header ->
            safeIndex { "${header.name().lowercase(Locale.ROOT)}: ${header.value()}" }
        }.joinToString("\n").take(65_536)
        val traceIds = headers.asSequence().mapNotNull { header ->
            val name = safeIndex { header.name().lowercase(Locale.ROOT) } ?: return@mapNotNull null
            if (name == "traceparent" || name == "tracestate" || name == "x-request-id" ||
                name == "x-correlation-id" || name == "x-trace-id" || name == "request-id" ||
                name.contains("trace") || name.contains("correlation")) {
                safeIndex { header.value() }?.take(1024)
            } else null
        }.toSet()
        return IndexedHttpHistory(
            id = item.id(),
            item = item,
            color = color,
            url = url,
            host = safeIndex { request.httpService().host() }.orEmpty(),
            path = indexedHistoryPath(url),
            method = safeIndex { request.method().uppercase(Locale.ROOT) }.orEmpty(),
            statusCode = safeIndex { response?.statusCode()?.toInt() },
            mimeType = safeIndex { response?.mimeType()?.name?.uppercase(Locale.ROOT) },
            hasResponse = response != null,
            hasParameters = safeIndex { request.parameters().isNotEmpty() } ?: false,
            requestBytes = safeIndex { request.toByteArray().length() } ?: 0,
            responseBytes = safeIndex { response?.toByteArray()?.length() } ?: 0,
            responseStartMillis = safeIndex {
                item.timingData()?.timeBetweenRequestSentAndStartOfResponse()?.toMillis()
            },
            headerNames = headerNames,
            headerValues = headerValues,
            traceIds = traceIds
        )
    }
}

internal fun indexedHistoryPath(url: String): String = try {
    URI(url).rawPath?.ifBlank { "/" } ?: "/"
} catch (_: Exception) {
    "/" + url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#')
}

private inline fun <T> safeIndex(block: () -> T): T? = try {
    block()
} catch (_: Exception) {
    null
}

private val HISTORY_SEARCH_PERMITS = Semaphore(2, true)

internal inline fun <T> withHistorySearchPermit(block: () -> T): T {
    if (!HISTORY_SEARCH_PERMITS.tryAcquire()) {
        throw ToolBusyException("History search capacity is busy; retry this request shortly")
    }
    return try {
        block()
    } finally {
        HISTORY_SEARCH_PERMITS.release()
    }
}
