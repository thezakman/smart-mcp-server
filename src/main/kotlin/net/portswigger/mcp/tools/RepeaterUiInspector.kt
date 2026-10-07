package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import kotlinx.serialization.Serializable
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.Window
import java.lang.reflect.InvocationTargetException
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * Read-only inspection of Burp's Repeater tab strip.
 *
 * Montoya currently exposes only sendToRepeater(), but SwingUtils exposes the suite frame. Burp's own Repeater UI
 * is a JTabbedPane, so its live captions can be read without reflecting into obfuscated Burp classes or changing the
 * UI. This is intentionally isolated because the component hierarchy is not part of Montoya's compatibility contract.
 */
internal object RepeaterUiInspector {
    @Serializable
    data class Tab(
        val index: Int,
        val title: String,
        val selected: Boolean,
        val enabled: Boolean
    )

    @Serializable
    data class Snapshot(
        val available: Boolean,
        val source: String? = null,
        val selectedIndex: Int? = null,
        val tabs: List<Tab> = emptyList(),
        val limitation: String? = null
    )

    fun snapshot(api: MontoyaApi): Snapshot = onEventDispatchThread {
        val suiteFrame = api.userInterface().swingUtils().suiteFrame()
        findAttachedRepeaterPane(suiteFrame)?.let { pane -> snapshot(pane, "BURP_SWING_ATTACHED") }
            ?: findDetachedRepeaterPane(suiteFrame)?.let { pane -> snapshot(pane, "BURP_SWING_DETACHED") }
            ?: Snapshot(
                available = false,
                limitation = "Repeater tab strip was not found in this Burp UI layout"
            )
    }

    fun selectedTab(api: MontoyaApi): Tab? = snapshot(api).tabs.firstOrNull { it.selected }

    internal fun findAttachedRepeaterPane(root: Container): JTabbedPane? {
        val suiteTabs = descendants(root)
            .filterIsInstance<JTabbedPane>()
            .firstOrNull { pane ->
                (0 until pane.tabCount).any { pane.getTitleAt(it).equals("Repeater", ignoreCase = true) }
            } ?: return null

        val repeaterIndex = (0 until suiteTabs.tabCount)
            .firstOrNull { suiteTabs.getTitleAt(it).equals("Repeater", ignoreCase = true) }
            ?: return null
        val repeaterComponent = suiteTabs.getComponentAt(repeaterIndex)

        if (repeaterComponent is JTabbedPane) return repeaterComponent
        return descendants(repeaterComponent).filterIsInstance<JTabbedPane>().firstOrNull()
    }

    private fun findDetachedRepeaterPane(suiteFrame: Frame): JTabbedPane? = Window.getWindows()
        .asSequence()
        .filter { it !== suiteFrame && it.isShowing }
        .filterIsInstance<Frame>()
        .filter { it.title.equals("Repeater", ignoreCase = true) || it.title.equals("Burp Repeater", ignoreCase = true) }
        .mapNotNull { frame ->
            val panes = descendants(frame).filterIsInstance<JTabbedPane>().toList()
            // Detached Repeater currently wraps the actual request tabs in one outer pane.
            panes.firstOrNull { candidate ->
                panes.none { other -> other !== candidate && SwingUtilities.isDescendingFrom(candidate, other) }
            }?.let { outer -> descendants(outer).filterIsInstance<JTabbedPane>().firstOrNull { it !== outer } }
                ?: panes.firstOrNull()
        }
        .firstOrNull()

    internal fun snapshot(pane: JTabbedPane, source: String = "TEST"): Snapshot {
        val selected = pane.selectedIndex.takeIf { it >= 0 }
        val tabs = (0 until pane.tabCount).map { index ->
            Tab(
                index = index,
                title = tabTitle(pane, index),
                selected = index == selected,
                enabled = pane.isEnabledAt(index)
            )
        }
        return Snapshot(true, source, selected, tabs)
    }

    private fun tabTitle(pane: JTabbedPane, index: Int): String {
        pane.getTitleAt(index)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val header = pane.getTabComponentAt(index) ?: return ""
        return descendants(header)
            .filterIsInstance<JTextComponent>()
            .map { it.text.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    private fun descendants(root: Component): Sequence<Component> = sequence {
        val queue = ArrayDeque<Component>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            yield(current)
            if (current is Container) current.components.forEach(queue::addLast)
        }
    }

    private fun <T> onEventDispatchThread(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        try {
            SwingUtilities.invokeAndWait { result = runCatching(block) }
        } catch (error: InvocationTargetException) {
            throw error.cause ?: error
        }
        return result?.getOrThrow() ?: error("Burp UI inspection did not return a result")
    }
}
