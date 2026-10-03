package net.portswigger.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import net.portswigger.mcp.schema.asInputSchema
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.experimental.ExperimentalTypeInference

data class ToolBehavior(
    val title: String? = null,
    val readOnly: Boolean? = null,
    val destructive: Boolean? = null,
    val idempotent: Boolean? = null,
    val openWorld: Boolean? = null
) {
    fun annotations(): ToolAnnotations? =
        if (title == null && readOnly == null && destructive == null && idempotent == null && openWorld == null) null
        else ToolAnnotations(title, readOnly, destructive, idempotent, openWorld)
}

val READ_ONLY_TOOL = ToolBehavior(readOnly = true, destructive = false, idempotent = true, openWorld = false)
val LOCAL_MUTATION_TOOL = ToolBehavior(readOnly = false, destructive = false, idempotent = false, openWorld = false)
// Burp owns approval for these tools through its UI toggles and target allowlist.
// Omitting client approval hints prevents clients configured with approval_policy="never"
// from rejecting the call before Burp can apply those controls.
val BURP_GATED_TOOL = ToolBehavior()
val OPEN_WORLD_READ_TOOL = ToolBehavior(readOnly = true, destructive = false, idempotent = true, openWorld = true)
val OPEN_WORLD_MUTATION_TOOL = ToolBehavior(readOnly = false, destructive = false, idempotent = false, openWorld = true)

class ToolBusyException(message: String) : IllegalStateException(message)

@OptIn(InternalSerializationApi::class)
inline fun <reified I : Any> Server.mcpTool(
    description: String,
    behavior: ToolBehavior = ToolBehavior(),
    crossinline execute: I.() -> List<ContentBlock>
) {
    val toolName = I::class.simpleName?.toLowerSnakeCase() ?: error("Couldn't find name for ${I::class}")
    val serializer = I::class.serializer()
    val inputSchema = I::class.asInputSchema()

    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, request ->
        val startedAt = System.nanoTime()
        try {
            val result = CallToolResult(
                content = execute(
                    Json.decodeFromJsonElement(
                        serializer,
                        coerceIntegralArguments(request.params.arguments ?: JsonObject(emptyMap()), I::class)
                    )
                ),
                isError = false
            )
            ToolAuditLog.add(toolName, true, (System.nanoTime() - startedAt) / 1_000_000,
                resultChars = result.content.textCharacterCount())
            result
        } catch (e: Exception) {
            ToolAuditLog.add(toolName, false, (System.nanoTime() - startedAt) / 1_000_000, e.message)
            structuredToolError(e)
        }
    }

    addTool(
        name = toolName,
        description = description,
        inputSchema = inputSchema,
        toolAnnotations = behavior.annotations(),
        handler = handler
    )
}

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mcpToolString")
inline fun <reified I : Any> Server.mcpTool(
    description: String,
    behavior: ToolBehavior = ToolBehavior(),
    crossinline execute: I.() -> String
) {
    mcpTool<I>(description, behavior, execute = {
        listOf(TextContent(execute(this)))
    })
}

inline fun <reified I : Any> Server.mcpUnitTool(
    description: String,
    behavior: ToolBehavior = LOCAL_MUTATION_TOOL,
    crossinline execute: I.() -> Unit
) {
    mcpTool<I>(description, behavior, execute = {
        execute(this)

        listOf(TextContent("Executed tool"))
    })
}

inline fun <reified I : Paginated, J : Any> Server.mcpPaginatedTool(
    description: String,
    behavior: ToolBehavior = READ_ONLY_TOOL,
    noinline mapper: (J) -> CharSequence = { it.toString() },
    crossinline execute: I.() -> List<J>
) {
    mcpTool<I>(description, behavior, execute = {

        val items = execute(this)

        when {
            offset >= items.size -> {
                "Reached end of items"
            }

            else -> {
                val upperLimit = (offset + count).coerceAtMost(items.size)

                items.subList(offset, upperLimit)
                    .joinToString(separator = "\n\n", transform = mapper)
            }
        }
    })
}

inline fun <reified I : Paginated> Server.mcpPaginatedTool(
    description: String,
    behavior: ToolBehavior = READ_ONLY_TOOL,
    crossinline execute: I.() -> Sequence<String>
) {
    mcpTool<I>(description, behavior, execute = {
        val seq = execute(this)
        val paginated = seq.drop(offset).take(count).toList()

        if (paginated.isEmpty()) {
            listOf(TextContent("Reached end of items"))
        } else {
            listOf(TextContent(paginated.joinToString(separator = "\n\n")))
        }
    })
}

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mcpNamedToolString")
inline fun Server.mcpTool(
    name: String,
    description: String,
    behavior: ToolBehavior = ToolBehavior(),
    crossinline execute: () -> List<ContentBlock>
) {
    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, _ ->
        val startedAt = System.nanoTime()
        try {
            val result = CallToolResult(content = execute(), isError = false)
            ToolAuditLog.add(name, true, (System.nanoTime() - startedAt) / 1_000_000,
                resultChars = result.content.textCharacterCount())
            result
        } catch (e: Exception) {
            ToolAuditLog.add(name, false, (System.nanoTime() - startedAt) / 1_000_000, e.message)
            structuredToolError(e)
        }
    }
    addTool(
        name = name,
        description = description,
        inputSchema = ToolSchema(),
        toolAnnotations = behavior.annotations(),
        handler = handler
    )
}

inline fun Server.mcpTool(
    name: String,
    description: String,
    behavior: ToolBehavior = ToolBehavior(),
    crossinline execute: () -> String
) {
    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, _ ->
        val startedAt = System.nanoTime()
        try {
            val result = CallToolResult(content = listOf(TextContent(execute())), isError = false)
            ToolAuditLog.add(name, true, (System.nanoTime() - startedAt) / 1_000_000,
                resultChars = result.content.textCharacterCount())
            result
        } catch (e: Exception) {
            ToolAuditLog.add(name, false, (System.nanoTime() - startedAt) / 1_000_000, e.message)
            structuredToolError(e)
        }
    }
    addTool(
        name = name,
        description = description,
        inputSchema = ToolSchema(),
        toolAnnotations = behavior.annotations(),
        handler = handler
    )
}

fun structuredToolError(error: Exception): CallToolResult {
    val code = when (error) {
        is ToolBusyException -> "BUSY"
        is SerializationException, is IllegalArgumentException -> "INVALID_ARGUMENT"
        is IllegalStateException -> "PRECONDITION_FAILED"
        else -> "TOOL_EXECUTION_FAILED"
    }
    val message = error.message?.take(2000) ?: error::class.simpleName ?: "Tool execution failed"
    val payload = buildJsonObject {
        put("ok", false)
        put("code", code)
        put("message", message)
        put("retryable", error is ToolBusyException)
    }
    return CallToolResult(
        content = listOf(TextContent(payload.toString())),
        isError = true,
        structuredContent = payload
    )
}

@PublishedApi
internal fun List<ContentBlock>.textCharacterCount(): Int = sumOf { (it as? TextContent)?.text?.length ?: 0 }

fun coerceIntegralArguments(arguments: JsonObject, type: KClass<*>): JsonObject {
    val integralNames = type.memberProperties.filter {
        it.returnType.classifier == Int::class || it.returnType.classifier == Long::class
    }.map { it.name }.toSet()
    if (integralNames.isEmpty()) return arguments

    return JsonObject(arguments.mapValues { (name, value) ->
        if (name !in integralNames) value else coerceWholeNumber(value)
    })
}

private fun coerceWholeNumber(value: JsonElement): JsonElement {
    val primitive = value as? JsonPrimitive ?: return value
    if (primitive.isString) return value
    val raw = primitive.content
    val number = raw.toDoubleOrNull() ?: return value
    if (!number.isFinite() || number % 1.0 != 0.0) return value
    return JsonPrimitive(number.toLong())
}

fun String.toLowerSnakeCase(): String {
    return this
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .replace(Regex("([A-Z])([A-Z][a-z])"), "$1_$2")
        .replace(Regex("[\\s-]+"), "_")
        .lowercase()
}

interface Paginated {
    val count: Int
    val offset: Int
}
