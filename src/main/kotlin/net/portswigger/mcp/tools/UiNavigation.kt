package net.portswigger.mcp.tools

import kotlinx.serialization.Serializable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

@Serializable
internal data class UiSelectionResult(
    val tool: String,
    val tabId: String? = null,
    val tabTitle: String? = null,
    val organizerItemId: Int? = null,
    val windowTitle: String? = null,
    val selected: Boolean = true,
    val visible: Boolean,
    val verification: String = "SELECTION_RECHECKED_ON_EDT",
    val contentVerified: Boolean = false,
    val limitation: String = "Selection was checked after an EDT turn. Confirm displayed content before taking a screenshot; rendering and exchange content are not verified."
)

/** Only invoked by MCP navigation handlers, never from Montoya callbacks holding Burp locks. */
internal object UiNavigation {
    private val busy = AtomicBoolean(false)
    private val generation = AtomicLong()

    fun cancelPending() { generation.incrementAndGet() }

    // prepare performs one selection and returns a check to run on the following EDT turn.
    // No sleeps, invokeAndWait, locks held by editor callbacks, or automatic retries.
    fun run(timeoutMillis: Long = 1500, prepare: () -> (() -> UiSelectionResult)): UiSelectionResult {
        check(!SwingUtilities.isEventDispatchThread()) { "Navigation must be requested outside a Burp UI callback" }
        if (!busy.compareAndSet(false, true)) throw ToolBusyException("Another UI navigation is still pending")
        val result = CompletableFuture<UiSelectionResult>()
        val cancelled = AtomicBoolean(false)
        val epoch = generation.get()
        fun isCancelled() = cancelled.get() || generation.get() != epoch
        SwingUtilities.invokeLater {
            try {
                check(!isCancelled()) { "UI navigation was cancelled before selection" }
                val verify = prepare()
                SwingUtilities.invokeLater {
                    try {
                        check(!isCancelled()) { "UI navigation cancelled; selection may have changed" }
                        result.complete(verify())
                    } catch (error: Exception) {
                        result.completeExceptionally(error)
                    } finally {
                        busy.set(false)
                    }
                }
            } catch (error: Exception) {
                result.completeExceptionally(error)
                busy.set(false)
            }
        }
        try {
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            cancelled.set(true)
            throw IllegalStateException("UI navigation timed out; selection may have changed. Inspect current UI before retrying or capturing.", error)
        } catch (error: InterruptedException) {
            cancelled.set(true)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}
