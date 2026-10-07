package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import kotlinx.serialization.Serializable
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.lang.reflect.InvocationTargetException
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * Best-effort, read-only inspection of Burp's live Repeater UI.
 *
 * Montoya 2026.7 can create a Repeater tab but cannot enumerate existing tabs or expose their identity. This class
 * keeps every unsupported Swing detail in one place, runs all traversal on the EDT and returns an explicit unavailable
 * or ambiguous result instead of silently choosing an arbitrary pane.
 */
internal object RepeaterUiInspector {
    private val nextComponentId = AtomicInteger(1)
    private val componentIds = IdentityHashMap<Component, String>()

    @Volatile
    private var walkSelection: Tab? = null

    private val auxiliaryTabTitles = setOf(
        "beautify", "custom actions", "headers", "hex", "inspector", "params", "pretty", "raw", "render"
    )

    @Serializable
    data class Tab(
        val id: String,
        val containerId: String,
        val source: String,
        val index: Int,
        val title: String,
        val groupTitle: String? = null,
        val selected: Boolean,
        val enabled: Boolean,
        val windowTitle: String? = null
    )

    @Serializable
    data class Snapshot(
        val available: Boolean,
        val source: String? = null,
        val selectedIndex: Int? = null,
        val tabs: List<Tab> = emptyList(),
        val containers: Int = 0,
        val limitation: String? = null
    )

    @Serializable
    data class Selection(
        val tab: Tab? = null,
        val ambiguous: Boolean = false,
        val candidateIds: List<String> = emptyList(),
        val source: String? = null,
        val confidence: String? = null
    )

    private data class LocatedPane(
        val pane: JTabbedPane,
        val source: String,
        val windowTitle: String?,
        val owner: Window?,
        val activationPane: JTabbedPane? = null,
        val activationIndex: Int? = null
    )

    private data class AttachedLocation(
        val pane: JTabbedPane,
        val suiteTabs: JTabbedPane,
        val repeaterIndex: Int
    )

    fun snapshot(api: MontoyaApi): Snapshot = onEventDispatchThread {
        val panes = locatePanes(api)
        if (panes.isEmpty()) {
            Snapshot(
                available = false,
                limitation = "Repeater tab strip was not found in this Burp UI layout"
            )
        } else {
            val tabs = panes.flatMap(::tabsFor)
            Snapshot(
                available = true,
                source = panes.map { it.source }.distinct().singleOrNull() ?: "BURP_SWING_MULTIPLE",
                selectedIndex = panes.singleOrNull()?.pane?.selectedIndex?.takeIf { it >= 0 },
                tabs = tabs,
                containers = panes.size,
                limitation = "Titles and current selection are live UI state. Native back/forward Repeater history " +
                    "is not exposed by Montoya 2026.7."
            )
        }
    }

    /**
     * Returns the selected tab only when its source pane can be identified without choosing arbitrarily.
     * A startup binding walk has exact pane/index context. Normal sends prefer the currently active Burp window.
     */
    fun selectedTab(api: MontoyaApi): Selection = onEventDispatchThread {
        walkSelection?.let {
            return@onEventDispatchThread Selection(
                tab = it,
                source = "BURP_SWING_WALK_SELECTION",
                confidence = "EXACT"
            )
        }

        val panes = locatePanes(api)
        if (panes.isEmpty()) return@onEventDispatchThread Selection()
        val activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val activePanes = panes.filter { located ->
            activeWindow != null &&
                (located.owner === activeWindow || SwingUtilities.isDescendingFrom(located.pane, activeWindow))
        }
        val candidates = (activePanes.ifEmpty { panes.takeIf { it.size == 1 }.orEmpty() })
            .mapNotNull { located -> tabsFor(located).firstOrNull { it.selected } }

        when (candidates.size) {
            0 -> Selection()
            1 -> Selection(
                tab = candidates.single(),
                source = "BURP_SWING_ACTIVE_SELECTION",
                confidence = "PROBABLE"
            )
            else -> Selection(
                ambiguous = true,
                candidateIds = candidates.map { it.id },
                source = "BURP_SWING_MULTIPLE_SELECTIONS",
                confidence = "AMBIGUOUS"
            )
        }
    }

    /**
     * Selects each currently discoverable Repeater tab once and restores every selection. Burp lazily binds editor
     * providers as tabs become visible, so this gives the observer a chance to capture the current request/response
     * of tabs that existed before the extension loaded. It never clicks Send or changes message contents.
     */
    fun refreshEditorBindings(api: MontoyaApi): Int = onEventDispatchThread {
        var visited = 0
        locatePanes(api).forEach { located ->
            val pane = located.pane
            val original = pane.selectedIndex
            val originalActivation = located.activationPane?.selectedIndex
            try {
                if (located.activationPane != null && located.activationIndex != null &&
                    located.activationPane.selectedIndex != located.activationIndex
                ) {
                    located.activationPane.selectedIndex = located.activationIndex
                }
                for (index in 0 until pane.tabCount) {
                    walkSelection = tabAt(located, index, selected = true)
                    if (pane.selectedIndex != index) pane.selectedIndex = index
                    visited++
                }
            } finally {
                if (original in 0 until pane.tabCount) pane.selectedIndex = original
                if (located.activationPane != null && originalActivation != null &&
                    originalActivation in 0 until located.activationPane.tabCount
                ) {
                    located.activationPane.selectedIndex = originalActivation
                }
                walkSelection = null
            }
        }
        visited
    }

    internal fun findAttachedRepeaterPane(root: Container): JTabbedPane? {
        return findAttachedRepeaterLocation(root)?.pane
    }

    private fun findAttachedRepeaterLocation(root: Container): AttachedLocation? {
        val suiteTabs = descendants(root)
            .filterIsInstance<JTabbedPane>()
            .firstOrNull { pane ->
                (0 until pane.tabCount).any { pane.getTitleAt(it).equals("Repeater", ignoreCase = true) }
            } ?: return null

        val repeaterIndex = (0 until suiteTabs.tabCount)
            .firstOrNull { suiteTabs.getTitleAt(it).equals("Repeater", ignoreCase = true) }
            ?: return null
        val pane = findPrimaryRepeaterPane(suiteTabs.getComponentAt(repeaterIndex)) ?: return null
        return AttachedLocation(pane, suiteTabs, repeaterIndex)
    }

    internal fun snapshot(pane: JTabbedPane, source: String = "TEST"): Snapshot {
        val located = LocatedPane(pane, source, null, SwingUtilities.getWindowAncestor(pane))
        return Snapshot(
            available = true,
            source = source,
            selectedIndex = pane.selectedIndex.takeIf { it >= 0 },
            tabs = tabsFor(located),
            containers = 1
        )
    }

    internal fun clear() = synchronized(componentIds) {
        componentIds.clear()
        nextComponentId.set(1)
        walkSelection = null
    }

    private fun locatePanes(api: MontoyaApi): List<LocatedPane> {
        val suiteFrame = api.userInterface().swingUtils().suiteFrame()
        val found = ArrayList<LocatedPane>()
        findAttachedRepeaterLocation(suiteFrame)?.let { attached ->
            found += LocatedPane(
                attached.pane,
                "BURP_SWING_ATTACHED",
                suiteFrame.title,
                suiteFrame,
                attached.suiteTabs,
                attached.repeaterIndex
            )
        }

        Window.getWindows().asSequence()
            .filter { it !== suiteFrame && it.isShowing }
            .filterIsInstance<Frame>()
            .filter { frame ->
                frame.title.equals("Repeater", ignoreCase = true) ||
                    frame.title.equals("Burp Repeater", ignoreCase = true)
            }
            .forEach { frame ->
                findPrimaryRepeaterPane(frame)?.let {
                    found += LocatedPane(it, "BURP_SWING_DETACHED", frame.title, frame)
                }
            }

        return found.distinctBy { System.identityHashCode(it.pane) }
    }

    private fun findPrimaryRepeaterPane(root: Component): JTabbedPane? {
        if (root is JTabbedPane && isRepeaterTabPane(root)) return root
        return descendants(root).filterIsInstance<JTabbedPane>().firstOrNull(::isRepeaterTabPane)
    }

    private fun isRepeaterTabPane(pane: JTabbedPane): Boolean {
        if (pane.tabCount <= 0) return false
        val titled = (0 until pane.tabCount).map { tabTitle(pane, it).lowercase() }.filter { it.isNotBlank() }
        return titled.isNotEmpty() && titled.any { it !in auxiliaryTabTitles }
    }

    private fun tabsFor(located: LocatedPane): List<Tab> = (0 until located.pane.tabCount).map { index ->
        tabAt(located, index, selected = index == located.pane.selectedIndex)
    }

    private fun tabAt(located: LocatedPane, index: Int, selected: Boolean): Tab {
        val pane = located.pane
        val containerId = componentId(pane, "repeater-pane")
        val content = pane.getComponentAt(index)
        return Tab(
            id = componentId(content, "repeater-tab"),
            containerId = containerId,
            source = located.source,
            index = index,
            title = tabTitle(pane, index),
            groupTitle = groupTitle(pane, index),
            selected = selected,
            enabled = pane.isEnabledAt(index),
            windowTitle = located.windowTitle
        )
    }

    private fun groupTitle(pane: JTabbedPane, selectedIndex: Int): String? {
        for (index in selectedIndex - 1 downTo 0) {
            val headerTexts = displayTexts(pane.getTabComponentAt(index))
            val childCount = headerTexts.firstNotNullOfOrNull { it.toIntOrNull() } ?: continue
            if (childCount > 0 && selectedIndex <= index + childCount) {
                return headerTexts.firstOrNull { value -> value.toIntOrNull() == null && value.isNotBlank() }
                    ?: tabTitle(pane, index).takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun tabTitle(pane: JTabbedPane, index: Int): String {
        pane.getTitleAt(index)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        pane.getToolTipTextAt(index)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return displayTexts(pane.getTabComponentAt(index)).firstOrNull().orEmpty()
    }

    private fun displayTexts(root: Component?): List<String> {
        if (root == null) return emptyList()
        return descendants(root).mapNotNull { component ->
            when (component) {
                is JLabel -> component.text
                is AbstractButton -> component.text
                is JTextComponent -> component.text
                is JComponent -> component.toolTipText
                else -> component.accessibleContext?.accessibleName
            }?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct().toList()
    }

    private fun componentId(component: Component, prefix: String): String = synchronized(componentIds) {
        componentIds.getOrPut(component) { "$prefix-${nextComponentId.getAndIncrement()}" }
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
