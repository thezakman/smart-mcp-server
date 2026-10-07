package net.portswigger.mcp.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JPanel
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
}
