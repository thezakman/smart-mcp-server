package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.ToolProfile
import net.portswigger.mcp.config.ToggleSwitch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.Box.createHorizontalStrut
import javax.swing.Box.createVerticalStrut

class ServerConfigurationPanel(
    private val config: McpConfig,
    private val enabledToggle: ToggleSwitch,
    private val validationErrorLabel: WarningLabel,
    private val advancedOptionsPanel: AdvancedOptionsPanel
) : JPanel() {

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private lateinit var alwaysAllowHttpHistoryCheckBox: JCheckBox
    private lateinit var alwaysAllowWebSocketHistoryCheckBox: JCheckBox
    private lateinit var alwaysAllowOrganizerCheckBox: JCheckBox

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        updateColors()
        alignmentX = LEFT_ALIGNMENT

        buildPanel()
    }

    override fun updateUI() {
        super.updateUI()
        updateColors()
    }

    private fun updateColors() {
        background = Design.Colors.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
            BorderFactory.createEmptyBorder(Design.Spacing.SM, Design.Spacing.SM, Design.Spacing.SM, Design.Spacing.SM)
        )
    }

    private fun buildPanel() {
        add(createHeaderPanel())
        add(validationErrorLabel)
        add(createVerticalStrut(Design.Spacing.SM))

        val configEditingToolingCheckBox = createCheckBoxWithSubtitle(
            "Enable tools that can edit your config",
            "Can execute code",
            config.configEditingTooling,
            warning = true
        ) { config.configEditingTooling = it }

        val httpRequestApprovalCheckBox = createStandardCheckBox(
            "Require approval for HTTP requests", config.requireHttpRequestApproval
        ) { config.requireHttpRequestApproval = it }

        val dataAccessApprovalCheckBox = createDataAccessApprovalCheckBox()

        alwaysAllowHttpHistoryCheckBox = createIndentedCheckBox(
            "HTTP history", config.alwaysAllowHttpHistory, config.requireDataAccessApproval
        ) { config.alwaysAllowHttpHistory = it }

        alwaysAllowWebSocketHistoryCheckBox = createIndentedCheckBox(
            "WebSocket history",
            config.alwaysAllowWebSocketHistory,
            config.requireDataAccessApproval
        ) { config.alwaysAllowWebSocketHistory = it }

        alwaysAllowOrganizerCheckBox = createIndentedCheckBox(
            "Organizer",
            config.alwaysAllowOrganizer,
            config.requireDataAccessApproval
        ) { config.alwaysAllowOrganizer = it }

        val filterConfigCredentialsCheckBox = createCheckBoxWithSubtitle(
            "Filter config credentials",
            "Hides credentials in Burp configuration files.",
            config.filterConfigCredentials
        ) { config.filterConfigCredentials = it }

        val projectDataOptions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(dataAccessApprovalCheckBox)
            add(createVerticalStrut(2))
            add(createGroupHint("Allow without prompting"))
            add(createVerticalStrut(2))
            add(alwaysAllowHttpHistoryCheckBox)
            add(alwaysAllowWebSocketHistoryCheckBox)
            add(alwaysAllowOrganizerCheckBox)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        val accessGroup = createGroupPanel(
            title = "Requests and project data",
            description = "Choose which actions must be approved.",
            components = arrayOf(httpRequestApprovalCheckBox, projectDataOptions)
        )

        val configurationGroup = createGroupPanel(
            title = "Configuration access",
            description = "Protect access to Burp settings.",
            components = arrayOf(
                filterConfigCredentialsCheckBox,
                configEditingToolingCheckBox,
                createToolProfileSelector(),
                createConcurrencySelector()
            )
        )

        add(JPanel(GridLayout(1, 2, Design.Spacing.SM, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(accessGroup)
            add(configurationGroup)
        })
        add(createVerticalStrut(Design.Spacing.SM))
        add(advancedOptionsPanel)
    }

    private fun createHeaderPanel(): JPanel {
        val titlePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(Design.createSectionLabel("Server Configuration"))
            add(createVerticalStrut(2))
            add(JLabel("Control the server and its approval boundaries.").apply {
                font = Design.Typography.bodyMedium
                foreground = Design.Colors.onSurfaceVariant
                alignmentX = LEFT_ALIGNMENT
            })
        }

        val enabledPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            alignmentX = RIGHT_ALIGNMENT
        }
        enabledPanel.add(JLabel("Enabled").apply {
            font = Design.Typography.labelLarge
            foreground = Design.Colors.onSurfaceVariant
        })
        enabledPanel.add(createHorizontalStrut(Design.Spacing.SM))
        enabledPanel.add(enabledToggle)

        return JPanel(BorderLayout(Design.Spacing.MD, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(titlePanel, BorderLayout.CENTER)
            add(enabledPanel, BorderLayout.EAST)
        }
    }

    private fun createGroupPanel(
        title: String,
        description: String,
        components: Array<JComponent>
    ): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Design.Colors.listAlternatingBackground
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
                BorderFactory.createEmptyBorder(
                    Design.Spacing.SM, Design.Spacing.SM, Design.Spacing.SM, Design.Spacing.SM
                )
            )

            add(JLabel(title).apply {
                font = Design.Typography.labelLarge
                foreground = Design.Colors.onSurface
                alignmentX = LEFT_ALIGNMENT
            })
            add(createVerticalStrut(2))
            add(JLabel(description).apply {
                font = Design.Typography.bodyMedium
                foreground = Design.Colors.onSurfaceVariant
                alignmentX = LEFT_ALIGNMENT
            })
            add(createVerticalStrut(Design.Spacing.SM))

            components.forEachIndexed { index, component ->
                component.alignmentX = LEFT_ALIGNMENT
                add(component)
                if (index < components.lastIndex) {
                    add(createVerticalStrut(Design.Spacing.SM))
                }
            }
        }
    }

    private fun createGroupHint(text: String): JLabel {
        return JLabel(text).apply {
            font = Design.Typography.labelMedium
            foreground = Design.Colors.onSurfaceVariant
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, Design.Spacing.MD, 0, 0)
        }
    }

    private fun createToolProfileSelector(): JPanel {
        val selector = JComboBox(ToolProfile.entries.toTypedArray()).apply {
            selectedItem = config.toolProfile
            font = Design.Typography.bodyMedium
            toolTipText = config.toolProfile.description + " Applies after server restart."
            addItemListener { event ->
                if (event.stateChange == ItemEvent.SELECTED) {
                    val profile = event.item as ToolProfile
                    config.toolProfile = profile
                    toolTipText = profile.description + " Applies after server restart."
                }
            }
        }

        return JPanel(BorderLayout(Design.Spacing.SM, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JLabel("Tool catalog").apply {
                font = Design.Typography.labelLarge
                foreground = Design.Colors.onSurface
                toolTipText = "Core reduces MCP context size. Full preserves every compatibility tool."
            }, BorderLayout.WEST)
            add(selector, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun createConcurrencySelector(): JPanel {
        val spinner = JSpinner(SpinnerNumberModel(config.maxConcurrentRequests, 1, 16, 1)).apply {
            font = Design.Typography.bodyMedium
            toolTipText = "Maximum simultaneous target-bound HTTP requests. Applies after server restart."
            addChangeListener { config.maxConcurrentRequests = value as Int }
            preferredSize = Dimension(96, preferredSize.height)
            maximumSize = preferredSize
        }
        return JPanel(BorderLayout(Design.Spacing.SM, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JLabel("Concurrent requests").apply {
                font = Design.Typography.labelLarge
                foreground = Design.Colors.onSurface
                toolTipText = spinner.toolTipText
            }, BorderLayout.WEST)
            add(spinner, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun createDataAccessApprovalCheckBox(): JCheckBox {
        return createStandardCheckBox(
            "Require approval for project data access", config.requireDataAccessApproval
        ) { enabled ->
            config.requireDataAccessApproval = enabled
            if (!enabled) {
                config.alwaysAllowHttpHistory = false
                config.alwaysAllowWebSocketHistory = false
                config.alwaysAllowOrganizer = false
                alwaysAllowHttpHistoryCheckBox.isSelected = false
                alwaysAllowWebSocketHistoryCheckBox.isSelected = false
                alwaysAllowOrganizerCheckBox.isSelected = false
            }
            alwaysAllowHttpHistoryCheckBox.isEnabled = enabled
            alwaysAllowWebSocketHistoryCheckBox.isEnabled = enabled
            alwaysAllowOrganizerCheckBox.isEnabled = enabled
        }
    }

    fun updateDataAccessCheckboxes() {
        SwingUtilities.invokeLater {
            alwaysAllowHttpHistoryCheckBox.isSelected = config.alwaysAllowHttpHistory
            alwaysAllowWebSocketHistoryCheckBox.isSelected = config.alwaysAllowWebSocketHistory
            alwaysAllowOrganizerCheckBox.isSelected = config.alwaysAllowOrganizer
        }
    }

    private fun createStandardCheckBox(
        text: String, initialValue: Boolean, onChange: (Boolean) -> Unit
    ): JCheckBox {
        return JCheckBox(text).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            isSelected = initialValue
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }
    }

    private fun createIndentedCheckBox(
        text: String, initialValue: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit
    ): JCheckBox {
        return JCheckBox(text).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            isSelected = initialValue
            isEnabled = enabled
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            border = BorderFactory.createEmptyBorder(0, Design.Spacing.MD, 0, 0)
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }
    }

    private fun createCheckBoxWithSubtitle(
        mainText: String,
        subtitleText: String,
        initialValue: Boolean,
        warning: Boolean = false,
        onChange: (Boolean) -> Unit
    ): JPanel {
        val checkBox = JCheckBox(mainText).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            isSelected = initialValue
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }

        val subtitleLabel = JLabel(subtitleText).apply {
            font = Design.Typography.labelMedium
            foreground = if (warning) Design.Colors.warning else Design.Colors.onSurfaceVariant
        }

        val subtitlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(createHorizontalStrut(Design.Spacing.MD))
            add(subtitleLabel)
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            add(checkBox)
            add(subtitlePanel)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

}
