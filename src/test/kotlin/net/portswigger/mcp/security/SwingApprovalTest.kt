package net.portswigger.mcp.security

import io.mockk.*
import kotlinx.coroutines.*
import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class SwingApprovalTest {
    private val config = mockk<McpConfig>(relaxed = true)

    @BeforeEach
    fun setup() {
        mockkObject(Dialogs)
        mockkStatic("net.portswigger.mcp.security.SecurityUtilsKt")
        every { findBurpFrame() } returns null
    }

    @AfterEach
    fun cleanup() {
        unmockkObject(Dialogs)
        unmockkStatic("net.portswigger.mcp.security.SecurityUtilsKt")
    }

    private fun requests(): List<suspend () -> Boolean> = listOf(
        { SwingUserApprovalHandler().requestApproval("example.invalid", 443, config, null, null) },
        { SwingDataAccessApprovalHandler().requestDataAccess(DataAccessType.ORGANIZER, config) }
    )

    private fun verifyNoPersistentApproval() {
        verify(exactly = 0) { config.addAutoApproveTarget(any()) }
        verify(exactly = 0) { config.alwaysAllowOrganizer = any() }
    }

    @Test
    fun `dialog failures reach the caller instead of leaving approval suspended`() {
        val failure = IllegalStateException("dialog fixture failure")
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any()) } throws failure
        for (request in requests()) {
            val actual = assertThrows(IllegalStateException::class.java) {
                runBlocking { withTimeout(3000) { request() } }
            }
            // Coroutine stack-trace recovery may copy the exception across dispatchers.
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
        }
        verifyNoPersistentApproval()
    }

    @Test
    fun `cancelled queued approvals never open a dialog`() {
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any()) } returns 1
        for (request in requests()) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            SwingUtilities.invokeLater { entered.countDown(); release.await() }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                runBlocking {
                    val pending = launch(start = CoroutineStart.UNDISPATCHED) { request() }
                    pending.cancel()
                    release.countDown()
                    withTimeout(3000) { pending.join() }
                    assertTrue(pending.isCancelled)
                }
            } finally {
                release.countDown()
                SwingUtilities.invokeAndWait { }
            }
        }
        verify(exactly = 0) { Dialogs.showOptionDialog(any(), any(), any(), any(), any()) }
        verifyNoPersistentApproval()
    }

    @Test
    fun `cancellation during a dialog cannot persist its subsequent allow choice`() {
        for (request in requests()) {
            val parent = Job()
            every { Dialogs.showOptionDialog(any(), any(), any(), any(), any()) } answers {
                assertTrue(SwingUtilities.isEventDispatchThread())
                parent.cancel()
                1
            }
            assertThrows(CancellationException::class.java) { runBlocking(parent) { request() } }
        }
        verifyNoPersistentApproval()
    }

    @Test
    fun `active allow and deny choices retain their original behavior`() = runBlocking {
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any()) } returns 0
        requests().forEach { assertTrue(it()) }
        verifyNoPersistentApproval()
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any()) } returns -1
        requests().forEach { assertFalse(it()) }
        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any()) } returns 1
        requests().forEach { assertTrue(it()) }
        verify(exactly = 1) { config.addAutoApproveTarget("example.invalid") }
        verify(exactly = 1) { config.alwaysAllowOrganizer = true }
    }
}
