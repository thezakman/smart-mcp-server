package net.portswigger.mcp.schema

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.tools.coerceIntegralArguments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonSchemaContractTest {
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
