package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
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
        background = Design.Colors.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
            BorderFactory.createEmptyBorder(Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD)
        )
    }

    private fun buildPanel() {
        add(Design.createSectionLabel("Advanced Options"))
        add(createVerticalStrut(Design.Spacing.MD))

        val formPanel = createFormPanel(
            "Server host:" to hostField, "Server port:" to portField
        )
        add(formPanel)
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

    private fun createFormPanel(vararg fields: Pair<String, JComponent>): JPanel {
        val formPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        fields.forEachIndexed { index, (labelText, field) ->
            val labelColumn = index * 2
            val labelInsets = if (index == 0) {
                Insets(Design.Spacing.SM, 0, Design.Spacing.SM, Design.Spacing.SM)
            } else {
                Insets(Design.Spacing.SM, Design.Spacing.LG, Design.Spacing.SM, Design.Spacing.SM)
            }

            formPanel.add(JLabel(labelText).apply {
                font = Design.Typography.bodyLarge
                foreground = Design.Colors.onSurface
            }, GridBagConstraints().apply {
                gridx = labelColumn
                gridy = 0
                fill = GridBagConstraints.NONE
                weightx = 0.0
                anchor = GridBagConstraints.WEST
                insets = labelInsets
            })

            if (field is JTextField) {
                field.preferredSize = Dimension(if (index == 0) 260 else 120, 32)
                field.font = Design.Typography.bodyLarge
            }

            formPanel.add(field, GridBagConstraints().apply {
                gridx = labelColumn + 1
                gridy = 0
                fill = GridBagConstraints.HORIZONTAL
                weightx = if (index == 0) 0.65 else 0.35
                anchor = GridBagConstraints.WEST
                insets = Insets(Design.Spacing.SM, 0, Design.Spacing.SM, 0)
            })
        }

        return formPanel
    }

    fun setFieldsEnabled(enabled: Boolean) {
        hostField.isEnabled = enabled
        portField.isEnabled = enabled
    }

}
