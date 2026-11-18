package net.portswigger.mcp.schema

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.tools.coerceIntegralArguments
import net.portswigger.mcp.tools.GetScannerIssues
import net.portswigger.mcp.tools.GetProxyHttpHistory
import net.portswigger.mcp.tools.GetOrganizerItems
import net.portswigger.mcp.tools.GetOrganizerItemsRegex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonSchemaContractTest {
    @Test
    fun `legacy page schemas advertise their runtime bounds`() {
        for (type in listOf(GetScannerIssues::class, GetProxyHttpHistory::class,
            GetOrganizerItems::class, GetOrganizerItemsRegex::class)) {
            val properties = type.asInputSchema().properties!!
            val count = properties.getValue("count").jsonObject
            val offset = properties.getValue("offset").jsonObject
            assertEquals("1", count.getValue("minimum").jsonPrimitive.content)
            assertEquals("100", count.getValue("maximum").jsonPrimitive.content)
            assertEquals("0", offset.getValue("minimum").jsonPrimitive.content)
        }
    }

    @Serializable
    private data class LongInput(val id: Long)

    @Test
    fun `large integers and integral decimals retain their exact value`() {
        for ((raw, expected) in listOf(
            "9007199254740993" to 9007199254740993L,
            "9007199254740993.0" to 9007199254740993L,
            "9223372036854775807" to Long.MAX_VALUE,
            "-9223372036854775808.0" to Long.MIN_VALUE,
            "5e2" to 500L
        )) {
            val input = Json.parseToJsonElement("{\"id\":$raw}").jsonObject
            val normalized = coerceIntegralArguments(input, LongInput::class)
            assertEquals(expected, Json.decodeFromJsonElement(LongInput.serializer(), normalized).id, raw)
        }
    }

    @Test
    fun `fractional and overflowing integers are rejected without rounding or clamping`() {
        for (raw in listOf("1.0000000000000001", "9223372036854775808", "-9223372036854775809", "1e1000000000")) {
            val input = Json.parseToJsonElement("{\"id\":$raw}").jsonObject
            val normalized = coerceIntegralArguments(input, LongInput::class)
            assertEquals(input, normalized)
            assertThrows(SerializationException::class.java) {
                Json.decodeFromJsonElement(LongInput.serializer(), normalized)
            }
        }
    }

    @Serializable
    private data class Input(
        @ToolField("Number of items.", minimum = 1, maximum = 100, example = "20")
        val count: Int = 20,
        @ToolField("Display mode.", enumValues = ["SUMMARY", "DETAIL"])
        val mode: String
    )

    @Test
    fun `schema includes semantic field constraints`() {
        val properties = Input::class.asInputSchema().properties!!
        val count = properties["count"]!!.jsonObject
        val mode = properties["mode"]!!.jsonObject

        assertEquals("Number of items.", count["description"]!!.jsonPrimitive.content)
        assertEquals("1", count["minimum"]!!.jsonPrimitive.content)
        assertEquals("100", count["maximum"]!!.jsonPrimitive.content)
        assertEquals(listOf("SUMMARY", "DETAIL"), mode["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `whole decimal values are normalized for integral inputs`() {
        val normalized = coerceIntegralArguments(
            kotlinx.serialization.json.buildJsonObject {
                put("count", kotlinx.serialization.json.JsonPrimitive(5.0))
                put("mode", kotlinx.serialization.json.JsonPrimitive("SUMMARY"))
            },
            Input::class
        )

        assertEquals("5", normalized["count"]!!.jsonPrimitive.content)
    }
}
