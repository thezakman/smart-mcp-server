package net.portswigger.mcp.config.components

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.*
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.ToggleSwitch
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*

class DetailedLoggingToggleTest {
    private fun descendants(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
    }

    @Test
    fun `toggle starts enabled persists clicks and renders in the configuration panel`() {
        val values = mutableMapOf<String, Any>()
        val storage = mockk<PersistedObject>()
        every { storage.getBoolean(any()) } answers { values[firstArg()] as? Boolean }
        every { storage.getString(any()) } answers { values[firstArg()] as? String }
        every { storage.getInteger(any()) } answers { values[firstArg()] as? Int }
        every { storage.setBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>() }
        val config = McpConfig(storage, mockk<Logging>(relaxed = true))
        SwingUtilities.invokeAndWait {
            val panel = ServerConfigurationPanel(config, ToggleSwitch(true) {}, WarningLabel(),
                AdvancedOptionsPanel(JTextField("127.0.0.1"), JTextField("9876"), WarningLabel()))
            val toggle = descendants(panel).single { it.name == "detailedLoggingToggle" } as ToggleSwitch
            assertTrue(config.detailedLogging)
            panel.setSize(1200, panel.preferredSize.height)
            fun layout(component: Component) {
                if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
            }
            layout(panel)
            val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            panel.printAll(graphics)
            graphics.dispose()
            val output = File("build/reports/ui/detailed-logs.png")
            output.parentFile.mkdirs()
            ImageIO.write(image, "png", output)
            fun click() = toggle.dispatchEvent(MouseEvent(toggle, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 20, 20, 1, false))
            click()
            assertFalse(config.detailedLogging)
            assertEquals(false, values["detailedLogging"])
            click()
            assertTrue(config.detailedLogging)
        }
    }
}
