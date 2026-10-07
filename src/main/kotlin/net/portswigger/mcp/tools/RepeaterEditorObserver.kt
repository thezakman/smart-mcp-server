package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.ui.Selection
import burp.api.montoya.ui.editor.extension.EditorCreationContext
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor
import kotlinx.serialization.Serializable
import java.awt.Component
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.Timer

private const val MAX_REPEATER_OBSERVATIONS = 1000
private const val MAX_ASSOCIATIONS_PER_FINGERPRINT = 8

@Serializable
data class RepeaterTabAssociation(
    val tabId: String? = null,
    val title: String? = null,
    val groupTitle: String? = null,
    val source: String,
    val confidence: String
)

@Serializable
data class RepeaterObservedExchange(
    val snapshotId: String,
    val tabId: String,
    val tabTitle: String,
    val tabGroup: String? = null,
    val associationSource: String,
    val associationConfidence: String,
    val observedAt: String,
    val method: String?,
    val host: String?,
    val port: Int?,
    val secure: Boolean?,
    val path: String?,
    val statusCode: Int?,
    val mimeType: String?,
    val request: String,
    val response: String
)

@Serializable
data class RepeaterObservedExchangeSummary(
    val snapshotId: String,
    val tabId: String,
    val tabTitle: String,
    val tabGroup: String? = null,
    val associationSource: String,
    val associationConfidence: String,
    val observedAt: String,
    val method: String?,
    val host: String?,
    val port: Int?,
    val secure: Boolean?,
    val path: String?,
    val statusCode: Int?,
    val mimeType: String?,
    val requestLength: Int,
    val responseLength: Int
)

internal fun RepeaterObservedExchange.summary() = RepeaterObservedExchangeSummary(
    snapshotId = snapshotId,
    tabId = tabId,
    tabTitle = tabTitle,
    tabGroup = tabGroup,
    associationSource = associationSource,
    associationConfidence = associationConfidence,
    observedAt = observedAt,
    method = method,
    host = host,
    port = port,
    secure = secure,
    path = path,
    statusCode = statusCode,
    mimeType = mimeType,
    requestLength = request.length,
    responseLength = response.length
)

/**
 * Observes Repeater editor bindings without adding a visible editor tab.
 *
 * Burp asks registered editor providers whether they support each request/response bound to an editor. Returning
 * false keeps the observer invisible, while the callback provides the current message. The association is stored by
 * request object identity first and request bytes second; ambiguous byte-identical tabs are never guessed.
 */
internal object RepeaterEditorObserver {
    private data class IdentityObservation(
        val request: WeakReference<HttpRequest>,
        val association: RepeaterTabAssociation
    )

    private val registered = AtomicBoolean(false)
    private val registrations = ArrayList<Registration>()
    private val byIdentity = LinkedHashMap<Int, ArrayDeque<IdentityObservation>>()
    private val byFingerprint = LinkedHashMap<String, LinkedHashSet<RepeaterTabAssociation>>()
    private val exchanges = LinkedHashMap<String, RepeaterObservedExchange>()

    @Volatile
    private var api: MontoyaApi? = null

    @Synchronized
    fun register(api: MontoyaApi) {
        if (!registered.compareAndSet(false, true)) return
        this.api = api
        registrations += api.userInterface().registerHttpRequestEditorProvider { context -> RequestObserver(context) }
        registrations += api.userInterface().registerHttpResponseEditorProvider { context -> ResponseObserver(context) }
        scheduleRefresh(api, 500)
        scheduleRefresh(api, 1_800)
    }

    private fun scheduleRefresh(api: MontoyaApi, delayMillis: Int) {
        Timer(delayMillis) {
            runCatching { RepeaterUiInspector.refreshEditorBindings(api) }
                .onFailure { api.logging().logToError("Repeater binding refresh failed: ${it.message}") }
        }.apply {
            isRepeats = false
            start()
        }
    }

    @Synchronized
    fun resolve(request: HttpRequest): RepeaterTabAssociation? {
        val identityBucket = byIdentity[System.identityHashCode(request)]
        if (identityBucket != null) {
            identityBucket.removeIf { it.request.get() == null }
            val exact = distinctTargets(identityBucket.filter { it.request.get() === request }.map { it.association })
            if (exact.size == 1) return exact.single().copy(
                source = "BURP_EDITOR_REQUEST_IDENTITY",
                confidence = exact.single().confidence
            )
            if (exact.size > 1) return RepeaterTabAssociation(
                source = "AMBIGUOUS_EDITOR_REQUEST_IDENTITY",
                confidence = "AMBIGUOUS"
            )
        }

        val candidates = distinctTargets(byFingerprint[requestFingerprint(request)].orEmpty())
        return when (candidates.size) {
            0 -> null
            1 -> candidates.first().copy(source = "BURP_EDITOR_REQUEST_FINGERPRINT", confidence = "PROBABLE")
            else -> RepeaterTabAssociation(
                source = "AMBIGUOUS_EDITOR_REQUEST_FINGERPRINT",
                confidence = "AMBIGUOUS"
            )
        }
    }

    @Synchronized
    fun history(
        tabId: String?,
        tabTitle: String?,
        newestFirst: Boolean
    ): List<RepeaterObservedExchange> {
        val filtered = exchanges.values.filter { exchange ->
            (tabId.isNullOrBlank() || exchange.tabId == tabId) &&
                (tabTitle.isNullOrBlank() || exchange.tabTitle.equals(tabTitle, ignoreCase = true))
        }
        return if (newestFirst) filtered.asReversed() else filtered
    }

    @Synchronized
    fun bySnapshotId(snapshotId: String): RepeaterObservedExchange? = exchanges[snapshotId.trim()]

    internal fun observeForTest(requestResponse: HttpRequestResponse, association: RepeaterTabAssociation) {
        val request = requestResponse.request()
        rememberAssociation(request, association)
        rememberExchange(requestResponse, association)
    }

    @Synchronized
    fun shutdown() {
        registrations.forEach { registration -> runCatching { if (registration.isRegistered) registration.deregister() } }
        registrations.clear()
        byIdentity.clear()
        byFingerprint.clear()
        exchanges.clear()
        api = null
        registered.set(false)
    }

    private fun observe(context: EditorCreationContext, requestResponse: HttpRequestResponse) {
        if (runCatching { context.toolSource().toolType() }.getOrNull() != ToolType.REPEATER) return
        val currentApi = api ?: return
        val selection = runCatching { RepeaterUiInspector.selectedTab(currentApi) }.getOrNull() ?: return
        val tab = selection.tab ?: return
        if (tab.title.isBlank()) return

        val association = RepeaterTabAssociation(
            tabId = tab.id,
            title = tab.title,
            groupTitle = tab.groupTitle,
            source = selection.source ?: "BURP_EDITOR_BINDING",
            confidence = selection.confidence ?: "PROBABLE"
        )
        val request = runCatching { requestResponse.request() }.getOrNull() ?: return
        rememberAssociation(request, association)
        if (association.confidence == "EXACT") rememberExchange(requestResponse, association)
    }

    @Synchronized
    private fun rememberAssociation(request: HttpRequest, association: RepeaterTabAssociation) {
        val identityKey = System.identityHashCode(request)
        val identities = byIdentity.getOrPut(identityKey) { ArrayDeque() }
        identities.removeIf { it.request.get() == null }
        identities.removeIf { it.request.get() === request && sameTarget(it.association, association) }
        identities.addLast(IdentityObservation(WeakReference(request), association))
        while (identities.size > MAX_ASSOCIATIONS_PER_FINGERPRINT) identities.removeFirst()

        val fingerprint = requestFingerprint(request)
        val fingerprintAssociations = byFingerprint.getOrPut(fingerprint) { LinkedHashSet() }
        fingerprintAssociations.removeIf { sameTarget(it, association) }
        fingerprintAssociations.add(association)
        while (byFingerprint.size > MAX_REPEATER_OBSERVATIONS) byFingerprint.remove(byFingerprint.keys.first())
    }

    @Synchronized
    private fun rememberExchange(requestResponse: HttpRequestResponse, association: RepeaterTabAssociation) {
        val response = runCatching { requestResponse.response() }.getOrNull() ?: return
        val request = runCatching { requestResponse.request() }.getOrNull() ?: return
        val requestText = request.toString()
        val responseText = response.toString()
        val tabId = association.tabId ?: return
        val snapshotId = "repeater-snapshot-${sha256("$tabId\n$requestText\n$responseText").take(20)}"
        exchanges[snapshotId] = RepeaterObservedExchange(
            snapshotId = snapshotId,
            tabId = tabId,
            tabTitle = association.title.orEmpty(),
            tabGroup = association.groupTitle,
            associationSource = association.source,
            associationConfidence = association.confidence,
            observedAt = Instant.now().toString(),
            method = runCatching { request.method() }.getOrNull(),
            host = runCatching { request.httpService().host() }.getOrNull(),
            port = runCatching { request.httpService().port() }.getOrNull(),
            secure = runCatching { request.httpService().secure() }.getOrNull(),
            path = runCatching { request.path() }.getOrNull(),
            statusCode = runCatching { response.statusCode().toInt() }.getOrNull(),
            mimeType = runCatching { response.mimeType().name }.getOrNull(),
            request = requestText,
            response = responseText
        )
        while (exchanges.size > MAX_REPEATER_OBSERVATIONS) exchanges.remove(exchanges.keys.first())
    }

    private abstract class BaseObserver(private val context: EditorCreationContext) {
        private val invisible = JPanel()
        protected var current: HttpRequestResponse? = null

        open fun caption(): String = "MCP Repeater Observer"
        open fun uiComponent(): Component = invisible
        open fun selectedData(): Selection? = null
        open fun isModified(): Boolean = false

        open fun setRequestResponse(requestResponse: HttpRequestResponse) {
            current = requestResponse
            observe(context, requestResponse)
        }

        open fun isEnabledFor(requestResponse: HttpRequestResponse): Boolean {
            observe(context, requestResponse)
            return false
        }
    }

    private fun distinctTargets(associations: Collection<RepeaterTabAssociation>): List<RepeaterTabAssociation> =
        associations.groupBy { Triple(it.tabId, it.title, it.groupTitle) }.values.map { sameTarget ->
            sameTarget.maxByOrNull { if (it.confidence == "EXACT") 1 else 0 } ?: sameTarget.first()
        }

    private fun sameTarget(left: RepeaterTabAssociation, right: RepeaterTabAssociation): Boolean =
        left.tabId == right.tabId && left.title == right.title && left.groupTitle == right.groupTitle

    private class RequestObserver(context: EditorCreationContext) : BaseObserver(context),
        ExtensionProvidedHttpRequestEditor {
        override fun caption() = super.caption()
        override fun uiComponent() = super.uiComponent()
        override fun selectedData() = super.selectedData()
        override fun isModified() = super.isModified()
        override fun setRequestResponse(requestResponse: HttpRequestResponse) = super.setRequestResponse(requestResponse)
        override fun isEnabledFor(requestResponse: HttpRequestResponse) = super.isEnabledFor(requestResponse)
        override fun getRequest(): HttpRequest? = current?.request()
    }

    private class ResponseObserver(context: EditorCreationContext) : BaseObserver(context),
        ExtensionProvidedHttpResponseEditor {
        override fun caption() = super.caption()
        override fun uiComponent() = super.uiComponent()
        override fun selectedData() = super.selectedData()
        override fun isModified() = super.isModified()
        override fun setRequestResponse(requestResponse: HttpRequestResponse) = super.setRequestResponse(requestResponse)
        override fun isEnabledFor(requestResponse: HttpRequestResponse) = super.isEnabledFor(requestResponse)
        override fun getResponse() = current?.response()
    }
}

private fun requestFingerprint(request: HttpRequest): String {
    val canonical = buildString {
        append(runCatching { request.httpService().host() }.getOrNull().orEmpty().lowercase()).append(':')
        append(runCatching { request.httpService().port() }.getOrNull() ?: -1).append(':')
        append(runCatching { request.httpService().secure() }.getOrNull() ?: false).append('\n')
        append(request.toString().replace("\r\n", "\n"))
    }
    return sha256(canonical)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
