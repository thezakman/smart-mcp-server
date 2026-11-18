package net.portswigger.mcp.tools

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OutboundRequestGateTest {
    @AfterEach
    fun reset() { OutboundRequestGate.configure(2) }

    private fun active() = OutboundRequestGate.metrics().getValue("active").jsonPrimitive.int

    @Test
    fun `reconfiguration keeps outstanding permits and never makes active negative`() {
        OutboundRequestGate.configure(1)
        OutboundRequestGate.withPermit {
            OutboundRequestGate.configure(1)
            assertEquals(1, active())
            assertThrows(ToolBusyException::class.java) { OutboundRequestGate.withPermit { error("over limit") } }
        }
        assertEquals(0, active())
        OutboundRequestGate.withPermit { assertEquals(1, active()) }
    }

    @Test
    fun `lowering limit waits for existing requests to drain`() {
        OutboundRequestGate.configure(2)
        OutboundRequestGate.withPermit {
            OutboundRequestGate.withPermit {
                OutboundRequestGate.configure(1)
                assertEquals(2, active())
                assertThrows(ToolBusyException::class.java) { OutboundRequestGate.withPermit { error("over limit") } }
            }
            assertThrows(ToolBusyException::class.java) { OutboundRequestGate.withPermit { error("over limit") } }
        }
        assertEquals(0, active())
    }

    @Test
    fun `increased limit accounts for old requests and releases failed operations`() {
        OutboundRequestGate.configure(1)
        OutboundRequestGate.withPermit {
            OutboundRequestGate.configure(2)
            assertThrows(IllegalArgumentException::class.java) {
                OutboundRequestGate.withPermit {
                    assertEquals(2, active())
                    assertThrows(ToolBusyException::class.java) { OutboundRequestGate.withPermit { error("over limit") } }
                    throw IllegalArgumentException("fixture")
                }
            }
            assertEquals(1, active())
        }
        assertEquals(0, active())
    }
}
