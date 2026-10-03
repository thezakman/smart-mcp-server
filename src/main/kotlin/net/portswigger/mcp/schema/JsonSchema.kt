package net.portswigger.mcp.schema

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

fun getJsonSchemaForProperty(kType: kotlin.reflect.KType, metadata: ToolField? = null): JsonElement {
    val base = when (val classifier = kType.classifier) {
        String::class ->
            JsonObject(mapOf("type" to JsonPrimitive("string")))

        Int::class, Long::class ->
            JsonObject(mapOf("type" to JsonPrimitive("integer")))

        Float::class, Double::class ->
            JsonObject(mapOf("type" to JsonPrimitive("number")))

        Boolean::class ->
            JsonObject(mapOf("type" to JsonPrimitive("boolean")))

        List::class, Array::class -> {
            val argType = kType.arguments.firstOrNull()?.type
            val itemsSchema = when {
                argType != null -> getJsonSchemaForProperty(argType)
                else -> JsonObject(mapOf("type" to JsonPrimitive("object")))
            }
            JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to itemsSchema))
        }

        Map::class -> {
            val valueType = kType.arguments.getOrNull(1)?.type
            val valueSchema = when {
                valueType != null -> getJsonSchemaForProperty(valueType)
                else -> JsonObject(mapOf("type" to JsonPrimitive("object")))
            }
            JsonObject(mapOf("type" to JsonPrimitive("object"), "additionalProperties" to valueSchema))
        }

        else -> if (classifier is KClass<*> && classifier.java.isEnum) {
            JsonObject(mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to kotlinx.serialization.json.JsonArray(
                    classifier.java.enumConstants.map { JsonPrimitive((it as Enum<*>).name) }
                )
            ))
        } else {
            JsonObject(mapOf("type" to JsonPrimitive("object")))
        }
    }

    if (metadata == null) return base
    val enriched = base.toMutableMap()
    enriched["description"] = JsonPrimitive(metadata.description)
    if (metadata.enumValues.isNotEmpty()) {
        val enumValues = kotlinx.serialization.json.JsonArray(metadata.enumValues.map(::JsonPrimitive))
        if (enriched["type"] == JsonPrimitive("array")) {
            val items = (enriched["items"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            items["enum"] = enumValues
            enriched["items"] = JsonObject(items)
        } else {
            enriched["enum"] = enumValues
        }
    }
    if (metadata.minimum != Long.MIN_VALUE) enriched["minimum"] = JsonPrimitive(metadata.minimum)
    if (metadata.maximum != Long.MAX_VALUE) enriched["maximum"] = JsonPrimitive(metadata.maximum)
    if (metadata.pattern.isNotEmpty()) enriched["pattern"] = JsonPrimitive(metadata.pattern)
    if (metadata.example.isNotEmpty()) enriched["examples"] =
        kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(metadata.example)))
    return JsonObject(enriched)
}

fun KClass<*>.asInputSchema(): ToolSchema {
    val properties = mutableMapOf<String, JsonElement>()
    val required = mutableListOf<String>()
    val optionalParameters = primaryConstructor?.parameters.orEmpty()
        .filter { it.isOptional }.mapNotNull { it.name }.toSet()

    for (prop in memberProperties) {
        @Suppress("UNCHECKED_CAST")
        val property = prop as KProperty1<Any, *>
        properties[prop.name] = getJsonSchemaForProperty(prop.returnType, property.findAnnotation<ToolField>())

        if (!prop.returnType.isMarkedNullable && prop.name !in optionalParameters) {
            required.add(prop.name)
        }
    }

    return ToolSchema(
        properties = JsonObject(properties),
        required = required
    )
}
