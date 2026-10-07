package net.portswigger.mcp.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JPanel
import javax.swing.JLabel
import javax.swing.JTabbedPane

class RepeaterUiInspectorTest {
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
