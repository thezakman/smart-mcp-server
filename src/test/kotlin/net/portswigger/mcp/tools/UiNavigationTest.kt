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
import javax.swing.JViewport
import java.awt.Dimension
import java.awt.Point
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

class UiNavigationTest {
    private fun onEdt(block: () -> Unit) = RepeaterUiInspector.onEventDispatchThread(block = block)
    private fun result() = UiSelectionResult(tool = "REPEATER", visible = true)

    @Test
    fun `failed preparation or verification releases navigation and preserves the cause`() {
        for (inVerification in listOf(false, true)) {
            for (failure in listOf(IllegalStateException("fixture"), LinkageError("fixture"))) {
                val actual = assertThrows(failure.javaClass) {
                    UiNavigation.run {
                        if (!inVerification) throw failure
                        val verify: () -> UiSelectionResult = { throw failure }
                        verify
                    }
                }
                assertSame(failure, actual)
                assertTrue(UiNavigation.run { { result() } }.selected)
            }
        }
    }

    @Test
    fun `completed navigation immediately permits the next request`() {
        repeat(100) {
            assertTrue(UiNavigation.run { { result() } }.selected)
        }
    }

    private fun countedTable(size: Int, idAt: (Int) -> Int, onRead: () -> Unit) =
        JTable(object : AbstractTableModel() {
            override fun getRowCount() = size
            override fun getColumnCount() = 3
            override fun getColumnName(column: Int) = arrayOf("#", "Method", "URL")[column]
            override fun getValueAt(row: Int, column: Int): Any = when (column) {
                0 -> { onRead(); idAt(row) }
                1 -> "GET"
                else -> "/local/$row"
            }
        })

    @Test
    fun `organizer bounds total inspected rows across multiple candidate tables`() = onEdt {
        var reads = 0
        val root = JPanel().apply {
            repeat(2) { add(countedTable(6000, { it + 1000 }) { reads++ }) }
        }
        reads = 0
        val error = assertThrows(IllegalStateException::class.java) {
            BurpUiNavigator.findOrganizerRow(root, 20)
        }
        assertTrue(error.message!!.contains("combined table scan limit"))
        assertEquals(10_000, reads)
    }

    @Test
    fun `organizer stops scanning as soon as ambiguity is proven`() = onEdt {
        var reads = 0
        val table = countedTable(6000, { 20 }) { reads++ }
        reads = 0
        assertThrows(IllegalStateException::class.java) { BurpUiNavigator.findOrganizerRow(table, 20) }
        assertEquals(2, reads)
        assertEquals(-1, table.selectedRow)
    }

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
    fun `organizer supports split Host Path layout with sorted native ids`() = onEdt {
        val table = JTable(DefaultTableModel(
            arrayOf(arrayOf<Any>(20, "POST", "example.invalid", "/a"), arrayOf<Any>(4, "GET", "example.invalid", "/b")),
            arrayOf("#", "Method", "Host", "Path")
        ))
        table.autoCreateRowSorter = true
        table.rowSorter.toggleSortOrder(0)
        val row = BurpUiNavigator.findOrganizerRow(table, 20)
        row.select(); row.verify()
        assertEquals(20, table.getValueAt(table.selectedRow, 0))
    }

    @Test
    fun `organizer tracks the id column when reordered after lookup`() = onEdt {
        val table = table(20, 4)
        val row = BurpUiNavigator.findOrganizerRow(table, 20)
        table.moveColumn(0, 2)
        row.select()
        table.moveColumn(2, 1)
        row.verify()
        assertEquals(20, table.getValueAt(table.selectedRow, 1))
    }

    @Test
    fun `organizer rejects removed id column and replaced table model`() = onEdt {
        val table = table(20)
        val row = BurpUiNavigator.findOrganizerRow(table, 20)
        table.removeColumn(table.columnModel.getColumn(0))
        assertThrows(IllegalStateException::class.java) { row.select() }
        assertEquals(-1, table.selectedRow)
        val other = table(20)
        val otherRow = BurpUiNavigator.findOrganizerRow(other, 20)
        other.model = DefaultTableModel(arrayOf(arrayOf<Any>(20, "GET", "/different")), arrayOf("#", "Method", "URL"))
        assertThrows(IllegalStateException::class.java) { otherRow.select() }
        assertEquals(-1, other.selectedRow)
    }

    @Test
    fun `organizer rejects duplicate id introduced before verification`() = onEdt {
        val table = table(20, 4)
        val row = BurpUiNavigator.findOrganizerRow(table, 20)
        row.select()
        table.model.setValueAt(20, 1, 0)
        assertThrows(IllegalStateException::class.java) { row.verify() }
    }

    @Test
    fun `suite navigation rejects ambiguous layouts`() = onEdt {
        fun suite() = JTabbedPane().apply { addTab("Proxy", JPanel()); addTab("Organizer", JPanel()) }
        val first = suite()
        assertEquals("Organizer", BurpUiNavigator.locateTool(first, "Organizer").title)
        val root = JPanel().apply { add(first); add(suite()) }
        assertThrows(IllegalStateException::class.java) { BurpUiNavigator.locateTool(root, "Organizer") }
    }

    @Test
    fun `organizer rejects editing started after selection without committing it`() =
        // Creating the first native text editor can exceed the navigation deadline on macOS.
        // This timeout covers fixture initialization; UiNavigation keeps its 1500 ms deadline.
        RepeaterUiInspector.onEventDispatchThread(timeoutMillis = 5000) {
        val table = table(20)
        val row = BurpUiNavigator.findOrganizerRow(table, 20)
        row.select()
        assertTrue(table.editCellAt(0, 2))
        assertThrows(IllegalStateException::class.java) { row.verify() }
        assertTrue(table.isEditing, "Verification must not commit or cancel user edits")
        table.cellEditor.cancelCellEditing()
        row.verify()
    }

    @Test
    fun `organizer rejects selected row scrolled out of view and can reselect it`() = onEdt {
        val table = table(*(1..100).toList().toIntArray())
        val viewport = JViewport().apply {
            setSize(300, 80)
            view = table
            viewSize = Dimension(300, table.rowHeight * table.rowCount)
        }
        val row = BurpUiNavigator.findOrganizerRow(table, 100)
        row.select()
        row.verifyVisibleRow()
        viewport.viewPosition = Point(0, 0)
        row.verify() // Selection survives scrolling, but the screenshot would miss its ID.
        assertThrows(IllegalStateException::class.java) { row.verifyVisibleRow() }
        row.select()
        row.verifyVisibleRow()
        assertEquals(100, table.getValueAt(table.selectedRow, 0))
    }

    @Test
    fun `organizer rejects horizontally hidden id cell and zero size viewport`() = onEdt {
        val table = table(20)
        val viewport = JViewport().apply {
            setSize(60, 80)
            view = table
            viewSize = Dimension(300, 80)
        }
        val row = BurpUiNavigator.findOrganizerRow(table, 20)
        row.select()
        row.verifyVisibleRow()
        viewport.viewPosition = Point(150, 0)
        assertThrows(IllegalStateException::class.java) { row.verifyVisibleRow() }
        viewport.viewPosition = Point(0, 0)
        viewport.setSize(0, 0)
        assertThrows(IllegalStateException::class.java) { row.verifyVisibleRow() }
    }
}
