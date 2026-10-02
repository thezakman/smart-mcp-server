package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*
import javax.swing.Box.createVerticalStrut
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class AdvancedOptionsPanel(
    private val hostField: JTextField,
    private val portField: JTextField,
    private val reinstallNotice: WarningLabel
) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        updateColors()
        alignmentX = LEFT_ALIGNMENT

        buildPanel()
        setupFieldTracking()
    }

    override fun updateUI() {
        super.updateUI()
        updateColors()
    }

    private fun updateColors() {
        background = Design.Colors.listAlternatingBackground
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
            BorderFactory.createEmptyBorder(Design.Spacing.SM, Design.Spacing.SM, Design.Spacing.SM, Design.Spacing.SM)
        )
    }

    private fun buildPanel() {
        add(JLabel("Connection address").apply {
            alignmentX = LEFT_ALIGNMENT
            font = Design.Typography.labelLarge
            foreground = Design.Colors.onSurface
        })
        add(createVerticalStrut(2))
        add(JLabel("Set the address used by connected AI clients.").apply {
            alignmentX = LEFT_ALIGNMENT
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
        })
        add(createVerticalStrut(Design.Spacing.SM))

        add(createFormPanel())
        add(createVerticalStrut(Design.Spacing.SM))
        reinstallNotice.apply {
            font = Design.Typography.labelMedium
            border = BorderFactory.createEmptyBorder(Design.Spacing.SM, 0, 0, 0)
        }
        add(reinstallNotice)
    }

    private fun setupFieldTracking() {
        trackChanges(hostField)
        trackChanges(portField)
    }

    private fun trackChanges(field: JTextField) {
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = handle()
            override fun removeUpdate(e: DocumentEvent?) = handle()
            override fun changedUpdate(e: DocumentEvent?) = handle()
            fun handle() {
                reinstallNotice.isVisible = true
            }
        })
    }

    private fun createFormPanel(): JPanel {
        return JPanel(GridLayout(1, 2, Design.Spacing.MD, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(createFieldGroup("Server host:", hostField))
            add(createFieldGroup("Server port:", portField))
        }
    }

    private fun createFieldGroup(labelText: String, field: JTextField): JPanel {
        configureField(field)
        return JPanel(BorderLayout(Design.Spacing.SM, 0)).apply {
            isOpaque = false
            add(JLabel(labelText).apply {
                font = Design.Typography.labelMedium
                foreground = Design.Colors.onSurfaceVariant
            }, BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }
    }

    private fun configureField(field: JTextField) {
        field.apply {
            preferredSize = Dimension(240, 36)
            minimumSize = Dimension(120, 36)
            maximumSize = Dimension(Int.MAX_VALUE, 36)
            font = Design.Typography.bodyLarge
        }
    }

    fun setFieldsEnabled(enabled: Boolean) {
        hostField.isEnabled = enabled
        portField.isEnabled = enabled
    }

}
