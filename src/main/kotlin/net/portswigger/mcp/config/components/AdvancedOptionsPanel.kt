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
        add(createVerticalStrut(2))
        add(JLabel("Set the address used by connected AI clients.").apply {
            alignmentX = LEFT_ALIGNMENT
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
        })
        add(createVerticalStrut(Design.Spacing.MD))

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
        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT

            add(JLabel("Server host:").apply {
                font = Design.Typography.labelMedium
                foreground = Design.Colors.onSurfaceVariant
            }, GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = Insets(0, 0, 0, Design.Spacing.SM)
            })

            configureField(hostField, Design.Spacing.MD * 20)
            add(hostField, GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                anchor = GridBagConstraints.WEST
            })

            add(JLabel("Server port:").apply {
                font = Design.Typography.labelMedium
                foreground = Design.Colors.onSurfaceVariant
            }, GridBagConstraints().apply {
                gridx = 2
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = Insets(0, Design.Spacing.LG, 0, Design.Spacing.SM)
            })

            configureField(portField, Design.Spacing.MD * 8)
            add(portField, GridBagConstraints().apply {
                gridx = 3
                gridy = 0
                anchor = GridBagConstraints.WEST
            })

            add(Box.createHorizontalGlue(), GridBagConstraints().apply {
                gridx = 4
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            })
        }
    }

    private fun configureField(field: JTextField, width: Int) {
        field.apply {
            preferredSize = Dimension(width, 36)
            minimumSize = Dimension(width, 36)
            maximumSize = Dimension(width, 36)
            font = Design.Typography.bodyLarge
        }
    }

    fun setFieldsEnabled(enabled: Boolean) {
        hostField.isEnabled = enabled
        portField.isEnabled = enabled
    }

}
