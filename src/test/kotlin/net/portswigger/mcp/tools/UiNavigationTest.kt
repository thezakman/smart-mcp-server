package net.portswigger.mcp.tools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class UiNavigationTest {
    private fun onEdt(block: () -> Unit) = RepeaterUiInspector.onEventDispatchThread(block = block)
    private fun result() = UiSelectionResult(tool = "REPEATER", visible = true)

    @Test
    fun `selection is confirmed on a later EDT turn`() {
        val eventDelivered = AtomicBoolean(false)
        val actual = UiNavigation.run {
            assertTrue(SwingUtilities.isEventDispatchThread())
            SwingUtilities.invokeLater { eventDelivered.set(true) }
            val verify: () -> UiSelectionResult = {
                assertTrue(SwingUtilities.isEventDispatchThread())
                assertTrue(eventDelivered.get())
                result()
            }
            verify
        }
        assertTrue(actual.selected)
        assertFalse(actual.contentVerified)
    }

    @Test
    fun `timed out queued action cannot select later and rejects concurrent navigation`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val changed = AtomicBoolean(false)
        SwingUtilities.invokeLater { entered.countDown(); release.await() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val error = assertThrows(IllegalStateException::class.java) {
                UiNavigation.run(40) { changed.set(true); { result() } }
            }
            assertTrue(error.message!!.contains("timed out"))
            assertThrows(ToolBusyException::class.java) { UiNavigation.run { { result() } } }
        } finally {
            release.countDown()
            SwingUtilities.invokeAndWait { }
        }
        assertFalse(changed.get())
        assertTrue(UiNavigation.run { { result() } }.selected)
    }

    @Test
    fun `navigation cannot block inside a UI callback`() = onEdt {
        assertThrows(IllegalStateException::class.java) { UiNavigation.run { { result() } } }
    }

    @Test
    fun `unload cancels navigation still waiting for the EDT`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val changed = AtomicBoolean(false)
        SwingUtilities.invokeLater { entered.countDown(); release.await() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // Timeout leaves the queued action pending, which unload also invalidates.
            worker.submit { assertThrows(IllegalStateException::class.java) {
                UiNavigation.run(40) { changed.set(true); { result() } }
            } }.get(2, TimeUnit.SECONDS)
            UiNavigation.cancelPending()
        } finally {
            release.countDown()
            SwingUtilities.invokeAndWait { }
            worker.shutdownNow()
        }
        assertFalse(changed.get())
    }

    @Test
    fun `repeater selection follows component identity after reordering`() = onEdt {
        val pane = JTabbedPane()
        val first = JPanel(); val target = JPanel()
        pane.addTab("First", first); pane.addTab("Evidence", target)
        pane.remove(target); pane.insertTab("Evidence", null, target, null, 0)
        RepeaterUiInspector.selectContent(pane, target, "Evidence")
        RepeaterUiInspector.verifyContent(pane, target, "Evidence")
        assertSame(target, pane.selectedComponent)
    }

    @Test
    fun `closed disabled or renamed repeater tabs fail without selecting another tab`() = onEdt {
        val pane = JTabbedPane()
        val first = JPanel(); val target = JPanel()
        pane.addTab("First", first); pane.addTab("Evidence", target)
        assertThrows(IllegalStateException::class.java) { RepeaterUiInspector.selectContent(pane, target, "Old name") }
        assertSame(first, pane.selectedComponent)
        pane.setEnabledAt(1, false)
        assertThrows(IllegalStateException::class.java) { RepeaterUiInspector.selectContent(pane, target, null) }
        pane.remove(target)
        assertThrows(IllegalStateException::class.java) { RepeaterUiInspector.selectContent(pane, target, null) }
        assertSame(first, pane.selectedComponent)
    }

    @Test
    fun `selection verification detects a user switching tabs`() = onEdt {
        val pane = JTabbedPane()
        val first = JPanel(); val target = JPanel()
        pane.addTab("First", first); pane.addTab("Evidence", target)
        RepeaterUiInspector.selectContent(pane, target, null)
        pane.selectedComponent = first
        assertThrows(IllegalStateException::class.java) { RepeaterUiInspector.verifyContent(pane, target, null) }
    }

    private fun table(vararg ids: Int) = JTable(DefaultTableModel(
        ids.map { arrayOf<Any>(it, "GET", "/local/$it") }.toTypedArray(), arrayOf("#", "Method", "URL")
    ))

    @Test
    fun `organizer selection uses native ID through sorted view and reordered columns`() = onEdt {
        val table = table(20, 4, 9)
        table.autoCreateRowSorter = true
        table.rowSorter.toggleSortOrder(0)
        table.moveColumn(0, 2)
        val row = BurpUiNavigator.findOrganizerRow(JPanel().apply { add(table) }, 20)
        row.select(); row.verify()
        assertEquals(20, table.getValueAt(table.selectedRow, 2))
    }

    @Test
    fun `organizer filtered out missing and duplicate IDs do not change selection`() = onEdt {
        val table = table(20, 4, 9)
        val sorter = TableRowSorter(table.model)
        table.rowSorter = sorter
        sorter.rowFilter = RowFilter.numberFilter(RowFilter.ComparisonType.BEFORE, 10, 0)
        table.setRowSelectionInterval(0, 0)
        val root = JPanel().apply { add(table) }
        assertThrows(IllegalStateException::class.java) { BurpUiNavigator.findOrganizerRow(root, 20) }
        assertEquals(4, table.getValueAt(table.selectedRow, 0))
        assertThrows(IllegalStateException::class.java) { BurpUiNavigator.findOrganizerRow(root, 99) }
        val duplicate = JPanel().apply { add(table(4, 4)) }
        assertThrows(IllegalStateException::class.java) { BurpUiNavigator.findOrganizerRow(duplicate, 4) }
    }

    @Test
    fun `organizer excludes hidden cards and unselected inner tabs`() = onEdt {
        val hidden = table(20).apply { isVisible = false }
        val visible = table(20)
        val root = JPanel().apply { add(hidden); add(visible) }
        assertSame(visible, BurpUiNavigator.findOrganizerRow(root, 20).table)
        val tabs = JTabbedPane().apply {
            addTab("Contents", JPanel().apply { add(visible) })
            addTab("Other", JPanel().apply { add(table(20)) })
        }
        assertSame(visible, BurpUiNavigator.findOrganizerRow(tabs, 20).table)
    }

    @Test
    fun `organizer checks current selection rather than stale row offset`() = onEdt {
        val table = table(20, 4)
        val row = BurpUiNavigator.findOrganizerRow(table, 20)
        row.select()
        table.setRowSelectionInterval(1, 1)
        assertThrows(IllegalStateException::class.java) { row.verify() }
    }

    @Test
    fun `unrecognized tables do not receive row selection`() = onEdt {
        val table = JTable(DefaultTableModel(arrayOf(arrayOf<Any>(20, "parameter")), arrayOf("#", "Name")))
        assertThrows(IllegalStateException::class.java) { BurpUiNavigator.findOrganizerRow(table, 20) }
        assertEquals(-1, table.selectedRow)
    }

    @Test
    fun `suite navigation rejects ambiguous layouts`() = onEdt {
        fun suite() = JTabbedPane().apply { addTab("Proxy", JPanel()); addTab("Organizer", JPanel()) }
        val first = suite()
        assertEquals("Organizer", BurpUiNavigator.locateTool(first, "Organizer").title)
        val root = JPanel().apply { add(first); add(suite()) }
        assertThrows(IllegalStateException::class.java) { BurpUiNavigator.locateTool(root, "Organizer") }
    }
}
