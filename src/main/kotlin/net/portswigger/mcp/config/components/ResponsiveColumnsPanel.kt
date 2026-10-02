package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import kotlin.math.max
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

class ResponsiveColumnsPanel(private val leftPanel: JPanel, private val rightPanel: JScrollPane) : JPanel() {
    private val leftColumnRatio = 0.32
    private val minWidthForTwoColumns = 900
    private val minWidthForLargePadding = 700
    private var lastLayout = Layout.SINGLE_COLUMN
    private var lastPaddingSize = PaddingSize.SMALL
    private var isInitialized = false

    enum class Layout { SINGLE_COLUMN, TWO_COLUMNS }
    enum class PaddingSize { SMALL, LARGE }

    init {
        isInitialized = true
        updateLayout()
    }

    override fun updateUI() {
        super.updateUI()
        if (isInitialized) {
            updateLayout() // Reapply layout with updated theme colors
        }
    }

    override fun doLayout() {
        super.doLayout()
        val currentLayout = if (width >= minWidthForTwoColumns) Layout.TWO_COLUMNS else Layout.SINGLE_COLUMN
        val currentPaddingSize = if (width >= minWidthForLargePadding) PaddingSize.LARGE else PaddingSize.SMALL

        if (currentLayout != lastLayout || currentPaddingSize != lastPaddingSize) {
            lastLayout = currentLayout
            lastPaddingSize = currentPaddingSize
            updateLayout()
        }
    }

    private fun updateLayout() {
        removeAll()

        val padding = when (lastPaddingSize) {
            PaddingSize.LARGE -> Design.Spacing.LG
            PaddingSize.SMALL -> Design.Spacing.SM
        }

        if (rightPanel.viewport.view is JPanel) {
            val contentPanel = rightPanel.viewport.view as JPanel
            contentPanel.border = BorderFactory.createEmptyBorder(padding, padding, padding, padding)
        }

        when (lastLayout) {
            Layout.TWO_COLUMNS -> {
                layout = WeightedColumnsLayout(leftColumnRatio)
                add(leftPanel)
                add(rightPanel)
            }

            Layout.SINGLE_COLUMN -> {
                layout = BorderLayout()
                val singleColumnPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = Design.Colors.surface
                }

                val headerWrapper = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = BorderFactory.createEmptyBorder(padding, padding, Design.Spacing.MD, padding)
                    add(leftPanel, BorderLayout.CENTER)
                }

                singleColumnPanel.add(headerWrapper)

                val scrollWrapper = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(rightPanel, BorderLayout.CENTER)
                }
                singleColumnPanel.add(scrollWrapper)

                add(singleColumnPanel, BorderLayout.CENTER)
            }
        }

        revalidate()
        repaint()
    }

    private class WeightedColumnsLayout(private val leftRatio: Double) : LayoutManager {
        override fun addLayoutComponent(name: String?, component: java.awt.Component?) = Unit

        override fun removeLayoutComponent(component: java.awt.Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension = combinedSize(parent, preferred = true)

        override fun minimumLayoutSize(parent: Container): Dimension = combinedSize(parent, preferred = false)

        private fun combinedSize(parent: Container, preferred: Boolean): Dimension {
            if (parent.componentCount < 2) return Dimension(0, 0)
            val left = if (preferred) parent.getComponent(0).preferredSize else parent.getComponent(0).minimumSize
            val right = if (preferred) parent.getComponent(1).preferredSize else parent.getComponent(1).minimumSize
            val insets = parent.insets
            return Dimension(
                left.width + right.width + insets.left + insets.right,
                max(left.height, right.height) + insets.top + insets.bottom
            )
        }

        override fun layoutContainer(parent: Container) {
            if (parent.componentCount < 2) return
            val insets = parent.insets
            val availableWidth = (parent.width - insets.left - insets.right).coerceAtLeast(0)
            val availableHeight = (parent.height - insets.top - insets.bottom).coerceAtLeast(0)
            val leftWidth = (availableWidth * leftRatio).toInt()

            parent.getComponent(0).setBounds(insets.left, insets.top, leftWidth, availableHeight)
            parent.getComponent(1).setBounds(
                insets.left + leftWidth,
                insets.top,
                availableWidth - leftWidth,
                availableHeight
            )
        }
    }
}
