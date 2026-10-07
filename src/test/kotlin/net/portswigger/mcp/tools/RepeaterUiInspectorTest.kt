package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

class RepeaterUiInspectorTest {
    @Test
    fun `editor callback holding a Burp lock never waits for the EDT`() {
        val api = mockk<MontoyaApi>()
        val burpLock = Any()
        val edtAttemptingLock = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                synchronized(burpLock) {
                    SwingUtilities.invokeLater {
                        edtAttemptingLock.countDown()
                        synchronized(burpLock) { }
                    }
                    check(edtAttemptingLock.await(2, TimeUnit.SECONDS))
                    val binding = RepeaterUiInspector.editorBindingSelection(api)
                    val sending = RepeaterUiInspector.selectedTab(api)
                    binding.tab == null && sending.tab == null
                }
            }
            assertTrue(result.get(2, TimeUnit.SECONDS))
        } finally {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait { }
        }
    }

    @Test
    fun `UI query times out and cancels queued traversal when EDT is blocked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val traversed = AtomicBoolean(false)
        SwingUtilities.invokeLater {
            entered.countDown()
            release.await()
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val error = assertThrows(IllegalStateException::class.java) {
                RepeaterUiInspector.onEventDispatchThread(timeoutMillis = 50) { traversed.set(true) }
            }
            assertTrue(error.message!!.contains("Burp UI did not respond"))
        } finally {
            release.countDown()
            SwingUtilities.invokeAndWait { }
        }
        assertFalse(traversed.get())
    }

    @Test
    fun `finds Repeater pane and reads manual tab titles`() {
        val repeater = JTabbedPane().apply {
            addTab("1", JPanel())
            addTab("TESTANDO 1", JPanel())
            addTab("FIND_024", JPanel())
            selectedIndex = 2
        }
        val suite = JTabbedPane().apply {
            addTab("Proxy", JPanel())
            addTab("Repeater", JPanel().apply { add(repeater) })
            addTab("MCP", JPanel())
        }
        val root = JPanel().apply { add(suite) }

        val found = RepeaterUiInspector.findAttachedRepeaterPane(root)
        assertSame(repeater, found)

        val snapshot = RepeaterUiInspector.snapshot(found!!)
        assertTrue(snapshot.available)
        assertEquals(2, snapshot.selectedIndex)
        assertEquals(listOf("1", "TESTANDO 1", "FIND_024"), snapshot.tabs.map { it.title })
        assertEquals(null, snapshot.tabs[1].groupTitle)
        assertFalse(snapshot.tabs[0].selected)
        assertTrue(snapshot.tabs[2].selected)
    }

    @Test
    fun `returns null when Repeater tool is absent`() {
        val root = JPanel().apply {
            add(JTabbedPane().apply { addTab("Proxy", JPanel()) })
        }

        assertEquals(null, RepeaterUiInspector.findAttachedRepeaterPane(root))
    }

    @Test
    fun `uses stable session ids and reads group headers`() {
        val repeater = JTabbedPane().apply {
            addTab("Payments", JPanel())
            addTab("LIST_01", JPanel())
            addTab("UPDATE_02", JPanel())
            setTabComponentAt(0, JPanel().apply {
                add(JLabel("Payments"))
                add(JLabel("2"))
            })
            selectedIndex = 2
        }

        val first = RepeaterUiInspector.snapshot(repeater)
        val second = RepeaterUiInspector.snapshot(repeater)

        assertEquals(first.tabs.map { it.id }, second.tabs.map { it.id })
        assertEquals("Payments", first.tabs[2].groupTitle)
        assertEquals(1, first.containers)
    }
}
