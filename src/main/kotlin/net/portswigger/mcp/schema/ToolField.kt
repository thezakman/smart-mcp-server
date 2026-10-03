package net.portswigger.mcp.schema

/** Semantic metadata used to build precise MCP input schemas from Kotlin data classes. */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolField(
    val description: String,
    val enumValues: Array<String> = [],
    val minimum: Long = Long.MIN_VALUE,
    val maximum: Long = Long.MAX_VALUE,
    val pattern: String = "",
    val example: String = ""
)
