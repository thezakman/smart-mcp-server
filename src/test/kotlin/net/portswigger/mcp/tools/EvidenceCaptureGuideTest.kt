package net.portswigger.mcp.tools

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EvidenceCaptureGuideTest {
    @Test
    fun `guidance keeps companion optional and preserves standalone operation`() {
        assertEquals("true", EVIDENCE_CAPTURE_GUIDE["optional"]?.jsonPrimitive?.content)
        assertTrue(SERVER_INSTRUCTIONS.contains("OPTIONAL"))
        assertTrue(SERVER_INSTRUCTIONS.contains("Do not fail Burp operations"))
        assertTrue(EVIDENCE_CAPTURE_GUIDE["standalone"].toString().contains("never required"))
    }
    @Test
    fun `navigation handoff never claims screenshot content verification`() {
        val result = UiSelectionResult(tool = "REPEATER", visible = true)
        assertFalse(result.contentVerified)
        assertTrue(result.nextStep.contains("optional"))
        assertTrue(result.nextStep.contains("get_evidence_capture_guide"))
    }
    @Test
    fun `serialized navigation preserves native identity and unverified content`() {
        val json = Json { encodeDefaults = true }
        val result = UiSelectionResult(
            tool = "REPEATER", tabId = "session-tab-42", tabTitle = "20", visible = true
        )
        val response = json.parseToJsonElement(
            json.encodeToString(UiSelectionResult.serializer(), result)
        ).jsonObject
        assertEquals("session-tab-42", response.getValue("tabId").jsonPrimitive.content)
        assertEquals("20", response.getValue("tabTitle").jsonPrimitive.content)
        assertEquals(true, response.getValue("selected").jsonPrimitive.boolean)
        assertEquals(false, response.getValue("contentVerified").jsonPrimitive.boolean)
        assertEquals(SCREENSHOT_HANDOFF, response.getValue("nextStep").jsonPrimitive.content)
        assertFalse(response.containsKey("evidenceId"), "Selection must not invent saved evidence")
    }

    @Test
    fun `guide covers dynamic crops confirmed output and per item verification`() {
        assertTrue(EVIDENCE_CAPTURE_GUIDE["dynamic_crop"].toString().contains("SAME"))
        assertTrue(EVIDENCE_CAPTURE_GUIDE["dynamic_crop"].toString().contains("Omit name/profile"))
        assertTrue(EVIDENCE_CAPTURE_GUIDE["confirmation"].toString().contains("metadata.persisted=true"))
        assertTrue(EVIDENCE_CAPTURE_GUIDE["batch"].toString().contains("reject_same_as"))
        assertTrue(EVIDENCE_CAPTURE_GUIDE["organizer"].toString().contains("PRECONDITION_FAILED"))
    }
    @Test
    fun `guide distinguishes connected version from frozen preview save semantics`() {
        assertEquals(2, EVIDENCE_CAPTURE_GUIDE.getValue("version").jsonPrimitive.int)
        val compatibility = EVIDENCE_CAPTURE_GUIDE.getValue("companion_compatibility").jsonPrimitive.content
        assertTrue(compatibility.contains("get_capture_status"))
        assertTrue(compatibility.contains("get_capture_guide"))
        assertTrue(compatibility.contains("0.8.3"))
        assertTrue(compatibility.contains("exact frozen preview"))
        assertTrue(compatibility.contains("original captured_at"))
        assertTrue(compatibility.contains("unknown versions"))
        val confirmation = EVIDENCE_CAPTURE_GUIDE.getValue("confirmation").jsonPrimitive.content
        assertTrue(confirmation.contains("does not update the saved image"))
        assertFalse(confirmation.contains("subsequent fresh capture"))
        val recovery = EVIDENCE_CAPTURE_GUIDE.getValue("recovery").jsonObject
            .getValue("content_changed").jsonPrimitive.content
        assertTrue(recovery.contains("Atualizar prévia"))
        assertTrue(recovery.contains("discards the previous crop"))
        assertTrue(recovery.contains("adjust/confirm again"))
        assertTrue(recovery.contains("If refresh is unavailable"))
    }
}
