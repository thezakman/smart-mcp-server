package net.portswigger.mcp.config.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.portswigger.mcp.Swing
import net.portswigger.mcp.config.Anchor
import net.portswigger.mcp.config.Design
import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.providers.Provider
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.*
import javax.swing.Box.createVerticalStrut
import javax.swing.JOptionPane.*
import kotlin.concurrent.thread

class InstallationPanel(
    private val config: McpConfig,
    private val providers: List<Provider>,
    private val reinstallNotice: WarningLabel,
    private val parentComponent: JComponent
) : JPanel() {

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

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
        add(Design.createSectionLabel("Installation"))
        add(createVerticalStrut(2))
        add(JLabel("Connect Claude Desktop, Claude CLI or Codex CLI, or export the proxy for manual setup.").apply {
            alignmentX = LEFT_ALIGNMENT
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
        })
        add(createVerticalStrut(Design.Spacing.SM))

        add(createButtonRow())
        add(createVerticalStrut(Design.Spacing.SM))
        add(createManualInstallPanel())
    }

    private fun createButtonRow(): JPanel {
        val buttonRow = JPanel(GridLayout(0, 2, Design.Spacing.SM, Design.Spacing.SM)).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
        }

        providers.forEachIndexed { index, provider ->
            val button = createProviderButton(provider, isPrimary = index < 2)
            buttonRow.add(button)
        }

        buttonRow.maximumSize = Dimension(Int.MAX_VALUE, buttonRow.preferredSize.height)

        return buttonRow
    }

    private fun createProviderButton(provider: Provider, isPrimary: Boolean): JButton {
        val button = if (isPrimary) {
            Design.createFilledButton(provider.installButtonText)
        } else {
            Design.createOutlinedButton(provider.installButtonText)
        }

        return button.apply {
            addActionListener {
                handleProviderInstall(provider)
            }
        }
    }

    private fun handleProviderInstall(provider: Provider) {
        val confirmationText = provider.confirmationText

        if (confirmationText != null) {
            val result = Dialogs.showConfirmDialog(
                parentComponent, confirmationText, YES_NO_OPTION
            )

            if (result != YES_OPTION) {
                return
            }
        }

        thread {
            try {
                val result = provider.install(config)
                CoroutineScope(Dispatchers.Swing).launch {
                    reinstallNotice.isVisible = false

                    if (result != null) {
                        Dialogs.showMessageDialog(
                            parentComponent, result, INFORMATION_MESSAGE
                        )
                    }
                }
            } catch (e: Exception) {
                CoroutineScope(Dispatchers.Swing).launch {
                    Dialogs.showMessageDialog(
                        parentComponent,
                        "Failed to install for ${provider.name}: ${e.message ?: e.javaClass.simpleName}",
                        ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun createManualInstallPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.CENTER, Design.Spacing.SM, 0)).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            add(
                Anchor(
                    text = "Codex CLI setup guide",
                    url = "https://github.com/thezakman/smart-mcp-server#codex-cli-client"
                )
            )
            add(JLabel("•").apply {
                foreground = Design.Colors.onSurfaceVariant
            })
            add(
                Anchor(
                    text = "Claude CLI setup guide",
                    url = "https://github.com/thezakman/smart-mcp-server#claude-cli-client"
                )
            )
            add(JLabel("•").apply {
                foreground = Design.Colors.onSurfaceVariant
            })
            add(
                Anchor(
                    text = "Claude Desktop setup guide",
                    url = "https://github.com/thezakman/smart-mcp-server#claude-desktop-client"
                )
            )
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

}
