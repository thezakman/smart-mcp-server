package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import kotlinx.serialization.Serializable
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.util.IdentityHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import javax.swing.Timer
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

    @Volatile
    private var activeWalk: BindingWalk? = null

    // Explicit navigation wins over the one-time startup editor-discovery walk.
    private var navigationRequested = false

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

    /** Returns the live selected tab without treating a background binding walk as evidence for a user send. */
    fun selectedTab(api: MontoyaApi): Selection {
        // Burp may call extension handlers while holding editor locks. Never wait for the EDT from a callback.
        if (!SwingUtilities.isEventDispatchThread()) return Selection()
        return selectedTabOnEdt(api)
    }

    private fun selectedTabOnEdt(api: MontoyaApi): Selection {
        val panes = locatePanes(api)
        if (panes.isEmpty()) return Selection()
        val activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val activePanes = panes.filter { located ->
            activeWindow != null &&
                (located.owner === activeWindow || SwingUtilities.isDescendingFrom(located.pane, activeWindow))
        }
        val candidates = (activePanes.ifEmpty { panes.takeIf { it.size == 1 }.orEmpty() })
            .mapNotNull { located -> tabsFor(located).firstOrNull { it.selected } }

        return when (candidates.size) {
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

    /** Editor callbacks fired by the controlled walk have exact pane/index context. */
    fun editorBindingSelection(api: MontoyaApi): Selection {
        // A worker callback cannot prove which tab owns the message from the current UI selection.
        // In particular, it must not invokeAndWait while Burp holds a lock required by the EDT.
        if (!SwingUtilities.isEventDispatchThread()) return Selection()
        walkSelection?.let {
            return Selection(
                tab = it,
                source = "BURP_SWING_WALK_SELECTION",
                confidence = "EXACT"
            )
        }
        return selectedTabOnEdt(api)
    }

    /**
     * Selects each currently discoverable Repeater tab once and restores every selection. Burp lazily binds editor
     * providers as tabs become visible, so this gives the observer a chance to capture the current request/response
     * of tabs that existed before the extension loaded. It never clicks Send or changes message contents.
     */
    fun refreshEditorBindings(api: MontoyaApi): Int = onEventDispatchThread {
        if (navigationRequested) return@onEventDispatchThread 0
        activeWalk?.finish()
        val locations = locatePanes(api)
        val steps = locations.flatMap { located ->
            (0 until located.pane.tabCount).map { index -> BindingStep(located, index) }
        }
        if (steps.isEmpty()) return@onEventDispatchThread 0

        BindingWalk(steps).also {
            activeWalk = it
            it.start()
        }
        steps.size
    }

    internal fun prepareForNavigation() {
        check(SwingUtilities.isEventDispatchThread())
        navigationRequested = true
        activeWalk?.finish()
    }

    fun selectTab(api: MontoyaApi, tabId: String, expectedTitle: String?): UiSelectionResult = UiNavigation.run {
        require(tabId.isNotBlank() && tabId.length <= 100) { "Use a tabId from list_repeater_tabs" }
        val candidates = locatePanes(api).flatMap { located ->
            tabsFor(located).filter { it.id == tabId }.map { located to it }
        }
        check(candidates.size == 1) { "Repeater tab ID is missing or ambiguous; call list_repeater_tabs again" }
        val (located, tab) = candidates.single()
        val owner = located.owner ?: error("Repeater owner window is unavailable")
        BurpUiNavigator.requireVisibleWindow(owner)
        val content = located.pane.getComponentAt(tab.index)
        validateTarget(located.pane, content, expectedTitle)
        prepareForNavigation()
        located.activationPane?.let { activation ->
            val index = located.activationIndex ?: error("Repeater suite tab is unavailable")
            check(index in 0 until activation.tabCount && activation.isEnabledAt(index)) { "Repeater suite tab is disabled" }
            activation.selectedIndex = index
        }
        selectContent(located.pane, content, expectedTitle)
        val verify: () -> UiSelectionResult = {
            verifyContent(located.pane, content, expectedTitle)
            check(content.isShowing) { "Repeater tab is not visible; inspect current UI before capturing" }
            BurpUiNavigator.requireVisibleWindow(owner)
            UiSelectionResult(tool = "REPEATER", tabId = tabId,
                tabTitle = tabTitle(located.pane, located.pane.indexOfComponent(content)),
                visible = true, windowTitle = located.windowTitle)
        }
        verify
    }

    internal fun selectContent(pane: JTabbedPane, content: Component, expectedTitle: String?) {
        validateTarget(pane, content, expectedTitle)
        pane.selectedComponent = content
    }

    internal fun verifyContent(pane: JTabbedPane, content: Component, expectedTitle: String?) {
        validateTarget(pane, content, expectedTitle)
        check(pane.selectedComponent === content) { "Repeater selection changed before confirmation" }
    }

    private fun validateTarget(pane: JTabbedPane, content: Component, expectedTitle: String?) {
        val index = pane.indexOfComponent(content)
        check(index >= 0) { "Repeater tab was closed before selection" }
        check(pane.isEnabledAt(index)) { "Repeater tab is disabled" }
        check(expectedTitle == null || tabTitle(pane, index) == expectedTitle) {
            "Repeater title changed; refresh list_repeater_tabs before selecting"
        }
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

    internal fun clear() {
        // Unload callbacks can also run under Burp locks. Cleanup must not wait for the UI.
        if (SwingUtilities.isEventDispatchThread()) clearOnEdt() else SwingUtilities.invokeLater { clearOnEdt() }
    }

    private fun clearOnEdt() {
        activeWalk?.finish()
        synchronized(componentIds) {
            activeWalk = null
            componentIds.clear()
            nextComponentId.set(1)
            walkSelection = null
            navigationRequested = false
        }
    }

    private data class BindingStep(val located: LocatedPane, val index: Int)

    /**
     * Keep each selected tab active for one EDT cycle. Burp can defer editor-provider callbacks until after the
     * selection event returns, so selecting every tab in one synchronous loop can restore the UI before it binds.
     */
    private class BindingWalk(private val steps: List<BindingStep>) {
        private val paneSelections = IdentityHashMap<JTabbedPane, Int>()
        private val activationSelections = IdentityHashMap<JTabbedPane, Int>()
        private val timer = Timer(25) { advance() }
        private var next = 0
        private var finished = false

        init {
            steps.forEach { step ->
                paneSelections.putIfAbsent(step.located.pane, step.located.pane.selectedIndex)
                step.located.activationPane?.let { activation ->
                    activationSelections.putIfAbsent(activation, activation.selectedIndex)
                }
            }
            timer.isRepeats = true
        }

        fun start() {
            advance()
            if (!finished) timer.start()
        }

        private fun advance() {
            if (finished) return
            if (next >= steps.size) {
                finish()
                return
            }
            val step = steps[next++]
            val located = step.located
            located.activationPane?.let { activation ->
                val activationIndex = located.activationIndex
                if (activationIndex != null && activation.selectedIndex != activationIndex) {
                    activation.selectedIndex = activationIndex
                }
            }
            walkSelection = tabAt(located, step.index, selected = true)
            if (located.pane.selectedIndex != step.index) located.pane.selectedIndex = step.index
        }

        fun finish() {
            if (finished) return
            finished = true
            timer.stop()
            walkSelection = null
            paneSelections.entries.toList().asReversed().forEach { (pane, index) ->
                if (index in 0 until pane.tabCount && pane.selectedIndex != index) pane.selectedIndex = index
            }
            activationSelections.entries.toList().asReversed().forEach { (pane, index) ->
                if (index in 0 until pane.tabCount && pane.selectedIndex != index) pane.selectedIndex = index
            }
            if (activeWalk === this) activeWalk = null
        }
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
                // A numeric ordinary tab (for example, Burp's default title "1") must not be mistaken for a
                // group-count badge. Require a separate non-numeric label before reporting a group.
                return headerTexts.firstOrNull { value -> value.toIntOrNull() == null && value.isNotBlank() }
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

    internal fun <T> onEventDispatchThread(timeoutMillis: Long = 1500, block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        val task = FutureTask<T> { block() }
        SwingUtilities.invokeLater(task)
        try {
            return task.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            task.cancel(false)
            throw IllegalStateException("Burp UI did not respond within ${timeoutMillis}ms; retry when the interface is responsive", error)
        } catch (error: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: java.util.concurrent.ExecutionException) {
            throw error.cause ?: error
        }
    }
}
