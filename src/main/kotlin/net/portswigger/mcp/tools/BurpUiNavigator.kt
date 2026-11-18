package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.Window
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.table.TableColumn

/** Best-effort Swing navigation isolated from the data API; never invokes buttons or edits messages. */
internal object BurpUiNavigator {
    private val toolTitles = mapOf(
        "DASHBOARD" to "Dashboard", "TARGET" to "Target", "PROXY" to "Proxy", "LOGGER" to "Logger",
        "REPEATER" to "Repeater", "INTRUDER" to "Intruder", "SEQUENCER" to "Sequencer",
        "DECODER" to "Decoder", "COMPARER" to "Comparer", "ORGANIZER" to "Organizer", "MCP" to "MCP"
    )
    internal data class ToolLocation(val pane: JTabbedPane, val content: Component, val title: String)

    fun selectTool(api: MontoyaApi, tool: String): UiSelectionResult {
        val title = toolTitles[tool] ?: throw IllegalArgumentException("Unsupported Burp tool: $tool")
        return UiNavigation.run {
            val frame = api.userInterface().swingUtils().suiteFrame()
            requireVisibleWindow(frame)
            val target = locateTool(frame, title)
            RepeaterUiInspector.prepareForNavigation()
            activate(target)
            val verify: () -> UiSelectionResult = {
                check(target.pane.selectedComponent === target.content && target.content.isShowing) {
                    "Burp tool selection changed or is not visible; inspect UI before capturing"
                }
                requireVisibleWindow(frame)
                UiSelectionResult(tool = tool, visible = true, windowTitle = frame.title)
            }
            verify
        }
    }

    fun selectOrganizerItem(api: MontoyaApi, id: Int): UiSelectionResult = UiNavigation.run {
        val frame = api.userInterface().swingUtils().suiteFrame()
        requireVisibleWindow(frame)
        val target = locateTool(frame, "Organizer")
        // Search only the currently selected collection/contents card, even when the suite tab is hidden.
        // No guessing across collections, changing filters, invoking context menus or rebuilding item contents.
        val selection = findOrganizerRow(target.content, id)
        RepeaterUiInspector.prepareForNavigation()
        activate(target)
        selection.select()
        val verify: () -> UiSelectionResult = {
            check(target.pane.selectedComponent === target.content && target.content.isShowing) {
                "Organizer is no longer visible; inspect UI before capturing"
            }
            check(selection.table.isShowing) { "Organizer item table is not visible" }
            selection.verifyVisibleRow()
            requireVisibleWindow(frame)
            UiSelectionResult(tool = "ORGANIZER", organizerItemId = id, visible = true, windowTitle = frame.title)
        }
        verify
    }

    internal fun locateTool(root: Component, title: String): ToolLocation {
        val matches = descendants(root).filterIsInstance<JTabbedPane>().filter { pane ->
            // A native tool bar has multiple known suite titles; an inner editor pane does not.
            (0 until pane.tabCount).count { i -> pane.getTitleAt(i) in toolTitles.values } >= 2
        }.flatMap { pane ->
            (0 until pane.tabCount).filter { pane.getTitleAt(it) == title }.map {
                ToolLocation(pane, pane.getComponentAt(it), title)
            }
        }.toList()
        check(matches.size == 1) { "Expected one attached $title tool pane; found ${matches.size}. Detached or unsupported layouts require manual navigation." }
        return matches.single()
    }

    private fun activate(target: ToolLocation) {
        val index = target.pane.indexOfComponent(target.content)
        check(index >= 0 && target.pane.isEnabledAt(index)) { "Burp tool was closed or is disabled" }
        target.pane.selectedComponent = target.content
    }

    internal fun requireVisibleWindow(window: Window) {
        check(window.isShowing && (window !is Frame || window.extendedState and Frame.ICONIFIED == 0)) {
            "Restore the Burp window before navigating for a screenshot"
        }
    }

    internal data class OrganizerRow(val table: JTable, val idColumn: TableColumn, val id: Int) {
        private val sourceModel = table.model
        private fun currentIdColumn(): Int {
            check(table.model === sourceModel) { "Organizer table model changed; locate the item again" }
            val column = (0 until table.columnCount).singleOrNull { table.columnModel.getColumn(it) === idColumn }
            check(column != null && table.getColumnName(column).trim() == "#") {
                "Organizer ID column changed or was removed; locate the item again"
            }
            return column
        }
        private fun matchingRows(): List<Int> {
            check(table.rowCount <= 10_000) { "Organizer table exceeds the navigation scan limit; narrow its filters first" }
            val column = currentIdColumn()
            return (0 until table.rowCount).asSequence()
                .filter { row -> nativeId(table.getValueAt(row, column)) == id }.take(2).toList()
        }
        fun select() {
            check(!table.isEditing) { "Finish editing the Organizer table before navigating" }
            val rows = matchingRows()
            check(rows.size == 1) { "Organizer item changed or was filtered out before selection" }
            table.setRowSelectionInterval(rows.single(), rows.single())
            table.scrollRectToVisible(table.getCellRect(rows.single(), currentIdColumn(), true))
        }
        fun verify() {
            check(!table.isEditing) { "Organizer table entered editing; finish editing before capturing" }
            val matches = matchingRows()
            check(matches.size == 1 && table.selectedRowCount == 1 && table.selectedRow == matches.single()) {
                "Organizer selection changed before confirmation; inspect UI before capturing"
            }
        }

        fun verifyVisibleRow() {
            verify()
            val idCell = table.getCellRect(table.selectedRow, currentIdColumn(), true)
            check(!idCell.isEmpty && table.visibleRect.intersects(idCell)) {
                "Organizer item ID is outside the visible table area; select the item again before capturing"
            }
        }
    }

    internal fun findOrganizerRow(root: Component, id: Int): OrganizerRow {
        var remainingRows = 10_000
        val matches = visibleDescendants(root).filterIsInstance<JTable>().flatMap { table ->
            val names = (0 until table.columnCount).map { table.getColumnName(it).trim() }
            // Burp uses both a combined URL and a split Host/Path layout across versions.
            val hasRequestLocation = "URL" in names || ("Host" in names && "Path" in names)
            if ("Method" !in names || !hasRequestLocation) return@flatMap emptySequence()
            check(table.rowCount <= 10_000) { "Organizer table exceeds the navigation scan limit; narrow its filters first" }
            val columns = (0 until table.columnCount).filter { table.getColumnName(it).trim() == "#" }
            if (columns.size != 1) emptySequence() else {
                val column = columns.single()
                val rows = (0 until table.rowCount).asSequence().filter {
                    check(remainingRows-- > 0) { "Organizer combined table scan limit reached; narrow its filters first" }
                    nativeId(table.getValueAt(it, column)) == id
                }
                rows.map { OrganizerRow(table, table.columnModel.getColumn(column), id) }
            }
        }.take(2).toList()
        check(matches.size == 1) {
            "Organizer item $id is not uniquely visible in the current collection and filters (matches=${matches.size}). Open its collection/Contents or adjust filters manually; no selection was made."
        }
        return matches.single()
    }

    private fun nativeId(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    internal fun descendants(root: Component): Sequence<Component> = sequence {
        val pending = ArrayDeque<Component>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty()) {
            check(++visited <= 20_000) { "Burp UI traversal limit reached; unsupported layout" }
            val node = pending.removeFirst()
            yield(node)
            if (node is Container) node.components.forEach(pending::addLast)
        }
    }

    private fun visibleDescendants(root: Component): Sequence<Component> = sequence {
        // Ignore the root's visibility: Organizer may be an unselected suite tab. Respect visibility beneath it.
        val pending = ArrayDeque<Component>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty()) {
            check(++visited <= 20_000) { "Organizer UI traversal limit reached" }
            val node = pending.removeFirst()
            yield(node)
            if (node is JTabbedPane) node.selectedComponent?.let(pending::addLast)
            else if (node is Container) node.components.filter { it.isVisible }.forEach(pending::addLast)
        }
    }
}
